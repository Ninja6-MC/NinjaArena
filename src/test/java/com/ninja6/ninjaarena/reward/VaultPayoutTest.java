package com.ninja6.ninjaarena.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.Test;

class VaultPayoutTest {

    public interface Economy {
        Result depositPlayer(OfflinePlayer player, double amount);
    }

    public record Result(boolean transactionSuccess) {
    }

    @Test
    void depositsUsingTheOfflinePlayerSignatureAndCurrencyUnits() {
        AtomicReference<Double> deposited = new AtomicReference<>();
        Economy economy = (player, amount) -> {
            deposited.set(amount);
            return new Result(true);
        };
        assertTrue(VaultPayout.deposit(Economy.class, economy, null, 125));
        assertEquals(1.25, deposited.get());
    }

    @Test
    void missingProvidersDeclinesAndProviderExceptionsFailClosed() {
        Economy declined = (player, amount) -> new Result(false);
        Economy failed = (player, amount) -> { throw new IllegalStateException("unavailable"); };
        assertFalse(VaultPayout.deposit(Economy.class, null, null, 100));
        assertFalse(VaultPayout.deposit(Economy.class, declined, null, 100));
        assertFalse(VaultPayout.deposit(Economy.class, failed, null, 100));
        assertFalse(VaultPayout.deposit(Object.class, new Object(), null, 100));
    }
}
