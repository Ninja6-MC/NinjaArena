package com.ninja6.ninjaarena.config;

import com.ninja6.ninjaarena.event.EventSettings;
import com.ninja6.ninjaarena.kit.KitDefinition;
import com.ninja6.ninjaarena.kit.KitItems;
import com.ninja6.ninjaarena.portal.LobbyMode;
import com.ninja6.ninjaarena.portal.PlayerPortalSettings;
import com.ninja6.ninjaarena.placeholder.StatsPlaceholders;
import com.ninja6.ninjaarena.stats.StatsSettings;
import com.ninja6.ninjaarena.reward.RewardSettings;
import com.ninja6.ninjaarena.resourcepack.ResourcePackSettings;
import com.ninja6.ninjaarena.resourcepack.BedrockPackSettings;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import java.util.Objects;
import java.util.function.Predicate;
import org.bukkit.configuration.file.YamlConfiguration;
import org.yaml.snakeyaml.nodes.MappingNode;

/** Validates both reload files before exposing any candidate values. */
public final class ReloadFiles {

    public record Candidate(YamlConfiguration config, KitDefinition kit) { }

    private ReloadFiles() { }

    public static Candidate read(Path config, Path kits, KitItems items) throws ReloadYaml.Invalid {
        return read(config, kits, items, arena -> true);
    }

    static Candidate read(Path config, Path kits, KitItems items, Predicate<String> arenaExists)
            throws ReloadYaml.Invalid {
        ReloadYaml settings = ReloadYaml.read(config);
        ReloadYaml kit = ReloadYaml.read(kits);
        return new Candidate(config(settings, arenaExists), kit(kit, items));
    }

    public static YamlConfiguration defaults() {
        try (var stream = ReloadFiles.class.getClassLoader().getResourceAsStream("config.yml")) {
            if (stream == null) {
                throw new IllegalStateException("missing bundled config.yml");
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static YamlConfiguration config(ReloadYaml document, Predicate<String> arenaExists) throws ReloadYaml.Invalid {
        YamlConfiguration defaults = defaults();
        YamlConfiguration result = new YamlConfiguration();
        for (var entry : document.nodes().entrySet()) {
            String path = entry.getKey();
            String removed = BedrockPackSettings.removedKeyProblem(path);
            if (removed != null) {
                throw document.invalidKey(path, removed);
            }
            if (path.startsWith("events.schedule.") || path.startsWith("rewards.milestones.") || path.startsWith("rewards.placings.")) {
                continue; // RewardSettings validates the contents of these lists.
            }
            int dot = path.lastIndexOf('.');
            if (dot > 0 && defaults.isList(path.substring(0, dot))) {
                // An item of a list setting, validated with the list below.
                continue;
            }
            if (!path.isEmpty() && !defaults.contains(path)) {
                throw document.invalidKey(path, "unknown setting");
            }
            if (!path.isEmpty() && defaults.isConfigurationSection(path)
                    && !(entry.getValue() instanceof MappingNode)) {
                throw document.invalid(path, "expected a section");
            }
        }
        for (String path : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(path)) {
                continue;
            }
            Object fallback = defaults.get(path);
            Object value = document.has(path) ? document.value(path) : fallback;
            if (fallback instanceof Boolean) {
                if (!(value instanceof Boolean)) {
                    throw document.invalid(path, "expected a boolean");
                }
            } else if (fallback instanceof String) {
                if (!(value instanceof String text)) {
                    throw document.invalid(path, "expected text; quote it if it looks like a number");
                }
                String problem;
                if (path.equals("waiting-room.lobby")) {
                    problem = LobbyMode.parse(text).isPresent() ? null : "expected dedicated or shared";
                } else if (path.startsWith(PlayerPortalSettings.SECTION + ".")) {
                    problem = PlayerPortalSettings.problem(path, text);
                } else if (path.startsWith("resource-pack.")) {
                    problem = null; // Validated together below, including enabled-pack completeness.
                } else if (path.startsWith("events.messages.")) {
                    problem = text.isBlank() || text.length() > 1000 ? "expected 1 to 1000 characters" : null;
                } else if (path.startsWith("events.")) {
                    problem = null; // EventSettings validates the time zone and HTTPS endpoint.
                } else {
                    problem = StatsSettings.problem(path, text);
                }
                if (problem != null) {
                    throw document.invalid(path, problem);
                }
            } else if (fallback instanceof List<?>) {
                String problem = switch (path) {
                    case PlayerPortalSettings.SECTION + ".worlds" -> PlayerPortalSettings.worldsProblem(value);
                    case "events.schedule", "events.announcement-lead-minutes", "rewards.milestones", "rewards.placings" ->
                            value instanceof List<?> entries && entries.stream().noneMatch(Objects::isNull)
                                    ? null : "expected a list without null entries";
                    default -> throw new IllegalStateException("No validation for list " + path);
                };
                if (problem != null) {
                    throw document.invalid(path, problem);
                }
                value = List.copyOf((List<?>) value);
            } else if (fallback instanceof Number) {
                if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
                    throw document.invalid(path, "expected a finite number");
                }
                if (fallback instanceof Integer && (value instanceof Float || value instanceof Double
                        || number.doubleValue() != Math.rint(number.doubleValue())
                        || number.doubleValue() > Integer.MAX_VALUE)) {
                    throw document.invalid(path, "expected an integer");
                }
                double[] range = range(path);
                double numeric = number.doubleValue();
                if (numeric < range[0] || numeric > range[1]) {
                    throw document.invalid(path, "must be from " + range[0] + " to " + range[1]);
                }
                if (path.equals("arena-grid.cell-size") && number.intValue() % 16 != 0) {
                    throw document.invalid(path, "must be a multiple of 16");
                }
                if (fallback instanceof Integer) {
                    value = Integer.valueOf(number.intValue());
                } else {
                    value = Double.valueOf(numeric);
                }
            }
            result.set(path, value);
        }
        try {
            ResourcePackSettings.from(result.getConfigurationSection("resource-pack"));
            BedrockPackSettings.from(result.getConfigurationSection("resource-pack"));
        } catch (IllegalArgumentException e) {
            throw document.invalid("resource-pack", e.getMessage());
        }
        RewardSettings rewards;
        try {
            rewards = RewardSettings.from(result);
        } catch (IllegalArgumentException e) {
            throw document.invalid("rewards", e.getMessage());
        }
        try {
            EventSettings events = EventSettings.from(result, rewards);
            for (EventSettings.Night night : events.schedule()) {
                if (!arenaExists.test(night.arena())) {
                    throw new IllegalArgumentException("unknown arena " + night.arena() + " for event " + night.id());
                }
            }
        } catch (IllegalArgumentException e) {
            throw document.invalid("events", e.getMessage());
        }
        int maxPlayers = result.getInt("waiting-room.max-players");
        if (maxPlayers != 0 && maxPlayers < result.getInt("waiting-room.min-players")) {
            throw document.invalid(document.has("waiting-room.max-players") ? "waiting-room.max-players"
                    : "waiting-room.min-players", "max-players must be 0 or at least min-players");
        }
        double pause = result.getDouble("stability.pause-spawning-above-mspt");
        if (result.getDouble("stability.resume-below-mspt") > pause) {
            throw document.invalid(document.has("stability.resume-below-mspt") ? "stability.resume-below-mspt"
                    : "stability.pause-spawning-above-mspt", "resume-below-mspt must not exceed pause-spawning-above-mspt");
        }
        if (result.getDouble("stability.drop-tier-above-mspt") < pause) {
            throw document.invalid(document.has("stability.drop-tier-above-mspt") ? "stability.drop-tier-above-mspt"
                    : "stability.pause-spawning-above-mspt", "drop-tier-above-mspt must not be below pause-spawning-above-mspt");
        }
        if (result.getDouble("director.shadow-stalker.blink.range")
                < result.getDouble("director.shadow-stalker.blink.distance")) {
            throw document.invalid(document.has("director.shadow-stalker.blink.range")
                    ? "director.shadow-stalker.blink.range" : "director.shadow-stalker.blink.distance",
                    "blink range must not be below blink distance");
        }
        return result;
    }

    private static double[] range(String path) {
        return switch (path) {
            case "resource-pack.timeout-seconds" -> new double[] {1, 600};
            case "arena-world.border-size" -> new double[] {16, 2048};
            case "arena-world.view-distance", "arena-world.simulation-distance" -> new double[] {2, 32};
            case "arena-grid.cell-size" -> new double[] {32, 4096};
            case "spectating.max-per-match", "waiting-room.min-players" -> new double[] {1, 64};
            case "waiting-room.countdown-seconds" -> new double[] {1, 600};
            case "waiting-room.radius" -> new double[] {2, 64};
            case "waiting-room.max-players" -> new double[] {0, 256};
            case "waiting-room.team-size" -> new double[] {0, 64};
            case "match.prep-seconds", "match.round-seconds", "match.round-end-seconds", "match.match-end-seconds"
                    -> new double[] {1, 3600};
            case "match.overrun-limit-seconds" -> new double[] {0, 3600};
            case "match.rounds" -> new double[] {1, 25};
            case "match.extra-rounds" -> new double[] {0, 10};
            case "points.player-kill", "points.enemy-kill-per-cost", "points.round-survive", "points.round-win"
                    -> new double[] {Integer.MIN_VALUE, Integer.MAX_VALUE};
            case "scoring.round-win-points", "scoring.player-kill-points" -> new double[] {0, Integer.MAX_VALUE};
            case "stability.max-concurrent-matches", "stability.spawns-per-tick" -> new double[] {1, 100};
            case "stability.match-mob-cap", "stability.global-mob-cap", "stability.pause-spawning-above-mspt",
                    "stability.drop-tier-above-mspt", "stability.resume-below-mspt" -> new double[] {1, 1000};
            case "stability.intensity-tier", "director.stealth.hidden-weight", "director.intensity.first-round-peak",
                    "director.intensity.round-step", "director.intensity.relax-level", "director.intensity.time-ramp",
                    "director.intensity.teams-weight", "director.intensity.damage-relief" -> new double[] {0, 1};
            case "stability.tier-recovery-seconds" -> new double[] {0, 600};
            case "director.spawn.view-angle" -> new double[] {0, 180};
            case "director.threat-budget.base" -> new double[] {0, Double.MAX_VALUE};
            case "director.enemy-costs.zombie", "director.enemy-costs.skeleton", "director.enemy-costs.spider",
                    "director.enemy-costs.shadow-stalker" -> new double[] {1, Integer.MAX_VALUE};
            case "director.stealth.low-light" -> new double[] {-1, 15};
            case "director.stealth.lose-track-seconds", "director.intensity.damage-window-seconds",
                    "tools.smoke-bomb.cloud-seconds", "tools.smoke-bomb.blindness-seconds",
                    "tools.shuriken.glow-seconds", "tools.dash.cooldown-seconds" -> new double[] {1, 60};
            case "director.stealth.marked-weight", "director.intensity.leader-bias" -> new double[] {1, 10};
            case "director.intensity.build-up-seconds", "director.intensity.peak-seconds",
                    "director.intensity.relax-seconds" -> new double[] {1, 600};
            case "director.intensity.damage-full-stress" -> new double[] {1, 1000};
            case "director.intensity.budget-round-step", "director.intensity.budget-per-player" -> new double[] {0, 10};
            case "director.shadow-stalker.max-alive" -> new double[] {0, 5};
            case "director.shadow-stalker.health" -> new double[] {1, 1024};
            case "director.shadow-stalker.damage" -> new double[] {0, 100};
            case "director.shadow-stalker.speed" -> new double[] {0.1, 0.5};
            case "director.shadow-stalker.scale" -> new double[] {0.5, 2};
            case "director.shadow-stalker.blink.distance" -> new double[] {2, 32};
            case "director.shadow-stalker.blink.range" -> new double[] {4, 64};
            case "director.shadow-stalker.blink.tell-ticks" -> new double[] {5, 100};
            case "director.shadow-stalker.blink.behind" -> new double[] {1, 6};
            case "director.shadow-stalker.blink.cooldown-seconds" -> new double[] {1, 120};
            case "director.shadow-stalker.glow-seconds" -> new double[] {1, 60};
            case "tools.smoke-bomb.radius" -> new double[] {1, 8};
            case "tools.shuriken.damage" -> new double[] {0, 20};
            case "tools.dash.strength" -> new double[] {0.1, 2};
            case "tools.dash.lift" -> new double[] {0, 0.6};
            case StatsPlaceholders.CACHE_SECONDS ->
                    new double[] {StatsPlaceholders.MIN_SECONDS, StatsPlaceholders.MAX_SECONDS};
            case "rewards.vault.amount-per-match", "rewards.vault.daily-cap" -> new double[] {0, 1_000_000};
            case "stats.mysql.port" -> new double[] {1, 65535};
            case PlayerPortalSettings.SECTION + ".max-per-player" -> new double[] {0, PlayerPortalSettings.MAX_PER_PLAYER};
            case PlayerPortalSettings.SECTION + ".min-distance" -> new double[] {0, PlayerPortalSettings.MAX_DISTANCE};
            default -> throw new IllegalStateException("No validation range for " + path);
        };
    }

    private static KitDefinition kit(ReloadYaml document, KitItems providers) throws ReloadYaml.Invalid {
        for (String path : document.nodes().keySet()) {
            if (!path.isEmpty() && !path.equals("format") && !path.equals("items")
                    && !path.matches("items\\.[0-9]+(\\.(slot|material|id|amount))?")) {
                throw document.invalidKey(path, "unknown kit setting");
            }
        }
        if (!Integer.valueOf(1).equals(document.value("format"))) {
            throw document.invalid("format", "expected format: 1");
        }
        if (!(document.value("items") instanceof List<?> entries)) {
            throw document.invalid("items", "expected an items list");
        }
        List<KitDefinition.Entry> kit = new ArrayList<>();
        Set<Integer> slots = new HashSet<>();
        for (int i = 0; i < entries.size(); i++) {
            String path = "items." + i;
            if (!(entries.get(i) instanceof Map<?, ?> entry)) {
                throw document.invalid(path, "expected an item entry");
            }
            if (!(entry.get("slot") instanceof Integer slot) || slot < 0 || slot > 40 || !slots.add(slot)) {
                throw document.invalid(path + ".slot", "invalid or duplicate slot (0 to 40)");
            }
            Object amountValue = entry.containsKey("amount") ? entry.get("amount") : Integer.valueOf(1);
            if (!(amountValue instanceof Integer amount) || amount < 1 || amount > 99) {
                throw document.invalid(path + ".amount", "expected an integer from 1 to 99");
            }
            if (entry.containsKey("material") == entry.containsKey("id")) {
                throw document.invalid(path, "needs exactly one of material or id");
            }
            Material material = null;
            String id = null;
            int limit;
            if (entry.containsKey("material")) {
                if (!(entry.get("material") instanceof String name)
                        || (material = Material.matchMaterial(name)) == null || !material.isItem()
                        || material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR) {
                    throw document.invalid(path + ".material", "expected an item material");
                }
                limit = material.getMaxStackSize();
            } else {
                if (!(entry.get("id") instanceof String name) || !name.matches("[a-z0-9._-]+:[a-z0-9/._-]+")
                        || providers.stackLimit(name) == 0) {
                    throw document.invalid(path + ".id", "unknown or invalid kit item id");
                }
                id = name;
                limit = providers.stackLimit(name);
            }
            if (amount > limit) {
                throw document.invalid(path + ".amount", "exceeds the item's stack limit of " + limit);
            }
            kit.add(new KitDefinition.Entry(slot, material, id, amount));
        }
        return new KitDefinition(kit);
    }
}
