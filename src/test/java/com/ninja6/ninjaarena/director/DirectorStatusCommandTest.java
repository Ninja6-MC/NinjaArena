package com.ninja6.ninjaarena.director;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ninja6.ninjaarena.arena.ArenaGrid;
import com.ninja6.ninjaarena.arena.ArenaRegistry;
import com.ninja6.ninjaarena.arena.ArenaStore;
import com.ninja6.ninjaarena.arena.ChunkTickets;
import com.ninja6.ninjaarena.arena.MemoryCopies;
import com.ninja6.ninjaarena.command.ArenaCommand;
import com.ninja6.ninjaarena.command.DirectorCommand;
import com.ninja6.ninjaarena.command.NinjaArenaCommand;
import com.ninja6.ninjaarena.command.PortalCommand;
import com.ninja6.ninjaarena.match.MatchView;
import com.ninja6.ninjaarena.portal.PortalRegistry;
import com.ninja6.ninjaarena.portal.PortalStore;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code /narena director}, run through the root command against a real director. */
class DirectorStatusCommandTest {

    private static final UUID A = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);

    @TempDir
    Path dir;

    private final List<String> messages = new ArrayList<>();
    private final List<MatchView> matches = new ArrayList<>();
    private final Map<MatchView, MatchDirector> directors = new IdentityHashMap<>();
    private final FakeMatch match = new FakeMatch(List.of(List.of(A), List.of(B))).combat(0);
    private NinjaArenaCommand command;

    @BeforeEach
    void setUp() throws Exception {
        ChunkTickets noTickets = new ChunkTickets() {
            @Override
            public void add(int chunkX, int chunkZ) {
            }

            @Override
            public void remove(int chunkX, int chunkZ) {
            }
        };
        ArenaRegistry registry = new ArenaRegistry(new ArenaStore(dir.resolve("arenas.yml")),
                new ArenaGrid(256, 2048, -64, 320), enabled -> { }, noTickets, new MemoryCopies());
        registry.load();
        PortalRegistry portals = new PortalRegistry(new PortalStore(dir.resolve("portals.yml")));
        portals.load();
        command = new NinjaArenaCommand(new ArenaCommand(registry, () -> null),
                new PortalCommand(portals, registry, world -> true),
                new DirectorCommand(() -> matches,
                        id -> matches.stream().filter(view -> view.contains(id)).findFirst(),
                        view -> Optional.ofNullable(directors.get(view))),
                player -> false);
        matches.add(match);
    }

    private <T extends CommandSender> T sender(Class<T> type, UUID id) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hasPermission" -> NinjaArenaCommand.ADMIN.equals(args[0]);
                    case "sendMessage" -> {
                        messages.add(PlainTextComponentSerializer.plainText().serialize((Component) args[0]));
                        yield null;
                    }
                    case "getUniqueId" -> id;
                    case "getName" -> "Admin";
                    default -> throw new UnsupportedOperationException(method.getName());
                }));
    }

    private void run(CommandSender sender, String... args) {
        command.onCommand(sender, null, "narena", args);
    }

    @Test
    void showsTheBudgetIntensityEnemiesAndLastRefusal() {
        MatchDirector.Spawner spawner = new MatchDirector.Spawner() {
            @Override
            public Optional<MatchDirector.Enemy> spawn(EnemyKind kind, UUID target) {
                return Optional.of(new MatchDirectorTest.FakeEnemy(kind, target));
            }

            @Override
            public Optional<String> lastRefusal() {
                return Optional.of("round 1, no enemy spawn point for a spider: point 1 in view");
            }
        };
        MatchDirector director = new MatchDirector(match, IntensitySettings.DEFAULTS, new ThreatBudget(20),
                EnemyCosts.DEFAULTS, (view, team) -> 0, spawner, SpawnGate.OPEN, TargetWeigher.NONE, () -> 0);
        director.tick();
        directors.put(match, director);

        run(sender(ConsoleCommandSender.class, null), "director", "DOJO");
        // Two players: budget 20 x 1 x 0.5; the fight opens at the relax level, 0.15.
        assertEquals(List.of("Director of arena dojo, round 1 (combat):",
                "Round budget: 10.00, intensity 0.15",
                "Enemies in play: 1, threat 1.00: 1 zombie (threat 1)",
                "Last placement refusal: round 1, no enemy spawn point for a spider: point 1 in view"), messages);
        assertEquals(List.of("dojo"), command.onTabComplete(sender(ConsoleCommandSender.class, null), null, "narena",
                new String[] {"director", "d"}));
    }

    @Test
    void anAdminInAMatchNeedsNoArenaName() {
        run(sender(Player.class, A), "director");
        assertEquals(List.of("Director of arena dojo, round 1 (combat):",
                "Not started: it starts with the match's first fight."), messages);
    }
}
