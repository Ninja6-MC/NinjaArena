package com.ninja6.ninjaarena.config;

import com.ninja6.ninjaarena.kit.KitDefinition;
import com.ninja6.ninjaarena.kit.KitItems;
import com.ninja6.ninjaarena.match.MatchSettings;
import com.ninja6.ninjaarena.match.ScoringSettings;
import com.ninja6.ninjaarena.tool.ToolSettings;
import com.ninja6.ninjaarena.waiting.WaitingRoomSettings;
import com.ninja6.ninjaarena.world.ArenaWorldSettings;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Validates both files first, then applies the reload policy on the server thread. */
public final class ConfigReload {

    public interface Target {
        boolean canResize();
        /** View and simulation distances only; border changes belong to the guarded grid update. */
        void world(ArenaWorldSettings settings);
        void grid(int cellSize, double borderSize);
        void waiting(WaitingRoomSettings settings);
        void match(MatchSettings settings, boolean friendlyFire, ScoringSettings scoring);
        void tools(ToolSettings settings);
        void kit(KitDefinition definition);
        default void configuration(ConfigurationSection settings) {
        }
    }

    private final Path configFile;
    private final Path kitFile;
    private final KitItems items;
    private final Target target;
    private YamlConfiguration effective;
    private KitDefinition kit;

    public ConfigReload(Path folder, ConfigurationSection initial, KitDefinition kit, KitItems items, Target target) {
        this.configFile = folder.resolve("config.yml");
        this.kitFile = folder.resolve("kits.yml");
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
            sender.sendMessage(Component.text(message, NamedTextColor.YELLOW));
        }
    }

    public List<String> reload() {
        ReloadFiles.Candidate candidate;
        try {
            candidate = ReloadFiles.read(configFile, kitFile, items);
        } catch (ReloadYaml.Invalid e) {
            return List.of("The Arena reload was rejected: " + e.getMessage() + ". Nothing changed.");
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
            if (path.startsWith("director.") || path.startsWith("stability.")) {
                refused.add(path + " (restart required)");
                next.set(path, effective.get(path));
            } else if (path.equals("arena-grid.cell-size") || path.equals("arena-world.border-size")) {
                if (resize) {
                    changed.add(path);
                } else {
                    refused.add(path + " (arenas exist or a match runs; restart required)");
                    next.set(path, effective.get(path));
                }
            } else if (path.startsWith("match.") || path.startsWith("scoring.")) {
                postponed.add(path + " (next match)");
            } else {
                changed.add(path);
            }
        }
        boolean kitChanged = !kit.equals(candidate.kit());
        if (kitChanged) {
            postponed.add("kits.yml (next round)");
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
                    return List.of("The Arena reload failed while applying world settings: " + e.getMessage()
                            + ". World settings could not be restored: " + restoreError.getMessage());
                }
                return List.of("The Arena reload was rejected: world settings could not be applied: "
                        + e.getMessage() + ". Old settings were restored.");
            }
        }
        if (differs("waiting-room.", next)) {
            target.waiting(WaitingRoomSettings.from(next.getConfigurationSection("waiting-room")));
        }
        if (differs("match.", next) || differs("scoring.", next)) {
            target.match(MatchSettings.from(next.getConfigurationSection("match")), next.getBoolean("match.friendly-fire"),
                    ScoringSettings.from(next.getConfigurationSection("scoring")));
        }
        if (differs("tools.", next)) {
            target.tools(ToolSettings.from(next.getConfigurationSection("tools")));
        }
        if (kitChanged) {
            target.kit(candidate.kit());
        }
        target.configuration(next);
        effective = next;
        kit = candidate.kit();
        return List.of("The Arena reload complete.", report("Changed", changed), report("Postponed", postponed),
                report("Refused", refused));
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

    private static String report(String category, List<String> values) {
        return category + ": " + (values.isEmpty() ? "none" : String.join(", ", values)) + ".";
    }
}
