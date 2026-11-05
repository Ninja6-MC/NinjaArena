package com.ninja6.ninjaarena.stats;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** A real database behind connections that fail on demand. */
final class Faults implements ConnectionSource {

    private final ConnectionSource real;
    final AtomicInteger connects = new AtomicInteger();
    /** Connection attempts still to fail. */
    final AtomicInteger connectFailures = new AtomicInteger();
    /** The name write fails as if the connection dropped. */
    volatile boolean failPlayers;
    /** A stats batch holding this player is refused as bad data. */
    volatile UUID reject;
    /** While set, connecting waits for it, holding the stats thread. */
    volatile CountDownLatch gate;

    Faults(ConnectionSource real) {
        this.real = real;
    }

    @Override
    public Connection connect() throws SQLException {
        connects.incrementAndGet();
        CountDownLatch waiting = gate;
        if (waiting != null) {
            try {
                waiting.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (connectFailures.getAndUpdate(left -> Math.max(0, left - 1)) > 0) {
            throw new SQLException("Communications link failure");
        }
        Connection connection = real.connect();
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    Object result = call(connection, method, args);
                    if (method.getName().equals("prepareStatement") && args.length == 1) {
                        return statement((PreparedStatement) result, (String) args[0]);
                    }
                    return result;
                });
    }

    private PreparedStatement statement(PreparedStatement statement, String sql) {
        List<String> bound = new ArrayList<>();
        return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class}, (proxy, method, args) -> {
                    if (method.getName().equals("setString")) {
                        bound.add((String) args[1]);
                    }
                    if (method.getName().equals("executeBatch")) {
                        if (failPlayers && sql.contains("players (player, name")) {
                            throw new SQLException("Communications link failure");
                        }
                        UUID refused = reject;
                        if (refused != null && sql.contains("player_stats (player") && bound.contains(refused.toString())) {
                            throw new SQLDataException("Data too long");
                        }
                        bound.clear();
                    }
                    return call(statement, method, args);
                });
    }

    private static Object call(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
