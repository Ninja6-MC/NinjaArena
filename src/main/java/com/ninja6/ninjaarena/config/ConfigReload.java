package com.ninja6.ninjaarena.config;

import static com.ninja6.ninjaarena.message.Messages.msg;
import static com.ninja6.ninjaarena.message.Messages.text;

import com.ninja6.ninjaarena.kit.KitDefinition;
import com.ninja6.ninjaarena.kit.KitItems;
import com.ninja6.ninjaarena.match.MatchSettings;
import com.ninja6.ninjaarena.match.PointsSettings;
import com.ninja6.ninjaarena.match.ScoringSettings;
import com.ninja6.ninjaarena.message.Messages;
import com.ninja6.ninjaarena.portal.PlayerPortalSettings;
import com.ninja6.ninjaarena.spectate.SpectatorSettings;
import com.ninja6.ninjaarena.tool.ToolSettings;
import com.ninja6.ninjaarena.waiting.WaitingRoomSettings;
import com.ninja6.ninjaarena.world.ArenaWorldSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Validates config.yml, kits.yml and messages.yml first, then applies the reload policy on the
 * server thread.
 */
public final class ConfigReload {

    public interface Target {
        boolean canResize();
        /** View and simulation distances only; border changes belong to the guarded grid update. */
        void world(ArenaWorldSettings settings);
        void grid(int cellSize, double borderSize);
        void waiting(WaitingRoomSettings settings);
        void match(MatchSettings settings, boolean friendlyFire, ScoringSettings scoring);
        default void points(PointsSettings settings) {
        }
        void tools(ToolSettings settings);
        default void spectating(SpectatorSettings settings) {
        }
        /** New {@code player-portals} settings, for the next portal lit and the curtain colour. */
        default void playerPortals(PlayerPortalSettings settings) {
        }
        void kit(KitDefinition definition);
        /** A new {@code stats.season}: archive the current season and start this one. */
        default void season(String name) {
        }
        default boolean eventArenaExists(String arena) {
            return true;
        }
        default void configuration(ConfigurationSection settings) {
        }
        default void bedrockEnforcement() {
        }
        /** The reloaded messages.yml; by default it becomes the text in use. */
        default void messages(Messages messages) {
            Messages.use(messages);
        }
        /** A messages.yml key that falls back to its default when first shown. */
        default void warning(String message) {
        }
    }

    private final Path configFile;
    private final Path kitFile;
    private final Path messagesFile;
    private final KitItems items;
    private final Target target;
    private YamlConfiguration effective;
    private KitDefinition kit;

    public ConfigReload(Path folder, ConfigurationSection initial, KitDefinition kit, KitItems items, Target target) {
        this.configFile = folder.resolve("config.yml");
        this.kitFile = folder.resolve("kits.yml");
        this.messagesFile = folder.resolve(Messages.FILE);
        this.items = items;
        this.target = target;
        this.kit = kit;
        effective = ReloadFiles.defaults();
        for (String path : effective.getKeys(true)) {
            if (!effective.isConfigurationSection(path) && initial.contains(path)) {
                effective.set(path, initial.get(path));
            }
        }
    }

    public void execute(CommandSender sender) {
        for (String message : reload()) {
            sender.sendMessage(msg("reload.line", "line", message));
        }
    }

    public List<String> reload() {
        ReloadFiles.Candidate candidate;
        try {
            candidate = ReloadFiles.read(configFile, kitFile, items, target::eventArenaExists);
        } catch (ReloadYaml.Invalid e) {
            return List.of(text("reload.rejected", "reason", e.getMessage()));
        }
        Messages messages;
        try {
            messages = messages();
        } catch (IOException e) {
            return List.of(text("reload.rejected", "reason",
                    text("reload.messages-unreadable", "file", Messages.FILE, "reason", e.getMessage())));
        }
        YamlConfiguration next = candidate.config();
        List<String> changed = new ArrayList<>();
        List<String> postponed = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        boolean resize = target.canResize();
        for (String path : effective.getKeys(true)) {
            if (effective.isConfigurationSection(path) || same(effective.get(path), next.get(path))) {
                continue;
            }
            if (path.startsWith("director.") || path.startsWith("stability.")
                    || path.startsWith("resource-pack.") && !path.equals("resource-pack.thrown-visuals")
                    || path.startsWith("stats.") && !path.equals("stats.season")) {
                refused.add(text("reload.restart-required", "path", path));
                next.set(path, effective.get(path));
            } else if (path.equals("arena-grid.cell-size") || path.equals("arena-world.border-size")) {
                if (resize) {
                    changed.add(path);
                } else {
                    refused.add(text("reload.resize-refused", "path", path));
                    next.set(path, effective.get(path));
                }
            } else if (path.startsWith("match.") || path.startsWith("scoring.") || path.startsWith("points.")) {
                postponed.add(text("reload.next-match", "path", path));
            } else {
                changed.add(path);
            }
        }
        boolean kitChanged = !kit.equals(candidate.kit());
        if (kitChanged) {
            postponed.add(text("reload.next-round", "path", "kits.yml"));
        }
        boolean messagesChanged = !messages.equals(Messages.active());
        if (messagesChanged) {
            changed.add(Messages.FILE);
        }
        boolean worldChanged = !same(effective.get("arena-world.view-distance"), next.get("arena-world.view-distance"))
                || !same(effective.get("arena-world.simulation-distance"), next.get("arena-world.simulation-distance"));
        boolean gridChanged = !same(effective.get("arena-grid.cell-size"), next.get("arena-grid.cell-size"))
                || !same(effective.get("arena-world.border-size"), next.get("arena-world.border-size"));
        if (worldChanged || gridChanged) {
            try {
                if (worldChanged) {
                    target.world(ArenaWorldSettings.from(next.getConfigurationSection("arena-world")));
                }
                if (gridChanged) {
                    target.grid(next.getInt("arena-grid.cell-size"), next.getDouble("arena-world.border-size"));
                }
            } catch (RuntimeException e) {
                try {
                    if (worldChanged) {
                        target.world(ArenaWorldSettings.from(effective.getConfigurationSection("arena-world")));
                    }
                    if (gridChanged) {
                        target.grid(effective.getInt("arena-grid.cell-size"),
                                effective.getDouble("arena-world.border-size"));
                    }
                } catch (RuntimeException restoreError) {
                    return List.of(text("reload.world-failed", "reason", e.getMessage(),
                            "restore", restoreError.getMessage()));
                }
                return List.of(text("reload.world-rejected", "reason", e.getMessage()));
            }
        }
        if (differs("waiting-room.", next)) {
            target.waiting(WaitingRoomSettings.from(next.getConfigurationSection("waiting-room")));
        }
        if (differs("match.", next) || differs("scoring.", next)) {
            target.match(MatchSettings.from(next.getConfigurationSection("match")), next.getBoolean("match.friendly-fire"),
                    ScoringSettings.from(next.getConfigurationSection("scoring")));
        }
        if (differs("points.", next)) {
            target.points(PointsSettings.from(next.getConfigurationSection("points")));
        }
        if (differs("tools.", next)) {
            target.tools(ToolSettings.from(next.getConfigurationSection("tools")));
        }
        if (differs(PlayerPortalSettings.SECTION + ".", next)) {
            target.playerPortals(PlayerPortalSettings.from(next.getConfigurationSection(PlayerPortalSettings.SECTION)));
        }
        // Apply validated admission settings even if startup fell back to disabling spectating.
        target.spectating(SpectatorSettings.from(next.getConfigurationSection("spectating")));
        if (kitChanged) {
            target.kit(candidate.kit());
        }
        if (!same(effective.get("stats.season"), next.get("stats.season"))) {
            target.season(next.getString("stats.season"));
        }
        target.configuration(next);
        target.bedrockEnforcement();
        if (messagesChanged) {
            target.messages(messages);
        }
        effective = next;
        kit = candidate.kit();
        return List.of(text("reload.complete"), report("reload.changed", changed),
                report("reload.postponed", postponed), report("reload.refused", refused));
    }

    private boolean differs(String prefix, ConfigurationSection next) {
        return effective.getKeys(true).stream().filter(path -> path.startsWith(prefix))
                .filter(path -> !effective.isConfigurationSection(path))
                .anyMatch(path -> !same(effective.get(path), next.get(path)));
    }

    private static boolean same(Object first, Object second) {
        if (first instanceof Number a && second instanceof Number b) {
            return Double.compare(a.doubleValue(), b.doubleValue()) == 0;
        }
        return Objects.equals(first, second);
    }

    private static String report(String key, List<String> values) {
        return text(key, "values", values.isEmpty() ? text("reload.none") : String.join(", ", values));
    }

    /**
     * The server's messages.yml, or the bundled text when there is none. Read exactly as at
     * startup, so reload accepts and rejects the same files.
     */
    private Messages messages() throws IOException {
        return Files.exists(messagesFile) ? Messages.read(messagesFile, target::warning) : Messages.bundled();
    }
}
