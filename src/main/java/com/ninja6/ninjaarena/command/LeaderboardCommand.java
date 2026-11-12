package com.ninja6.ninjaarena.command;

import static com.ninja6.ninjaarena.message.Messages.msg;

import com.ninja6.ninjaarena.message.Messages;
import com.ninja6.ninjaarena.stats.Leaderboards;
import com.ninja6.ninjaarena.stats.Stat;
import com.ninja6.ninjaarena.stats.StatsScope;
import com.ninja6.ninjaarena.stats.StatsStore;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;

public final class LeaderboardCommand {

    static final String NAME = "leaderboard";
    static final String PERMISSION = "ninjaarena.leaderboard";
    static final String USAGE = NAME + " <stat> [all_time|season]";
    private static final List<String> PERIODS = List.of("all_time", "season");

    private final JavaPlugin plugin;
    private final Supplier<StatsStore> stats;

    public LeaderboardCommand(JavaPlugin plugin, Supplier<StatsStore> stats) {
        this.plugin = plugin;
        this.stats = stats;
    }

    void execute(CommandSender sender, String label, String[] args) {
        Stat stat = args.length >= 1 ? Leaderboards.stat(args[0]) : null;
        String period = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "all_time";
        if (args.length > 2 || stat == null || !PERIODS.contains(period)) {
            sender.sendMessage(msg("leaderboard.usage", "label", label, "usage", USAGE));
            sender.sendMessage(msg("leaderboard.stats", "stats", String.join(", ", Leaderboards.STAT_NAMES)));
            return;
        }
        StatsStore store = stats.get();
        if (store == null) {
            sender.sendMessage(msg("leaderboard.disabled"));
            return;
        }
        CompletableFuture<List<Component>> result = period.equals("season")
                ? store.currentSeason().thenCompose(season -> season
                        .map(current -> read(store, stat, StatsScope.season(current.id()), current.name()))
                        .orElseGet(() -> CompletableFuture.completedFuture(List.of(msg("leaderboard.no-season")))))
                : read(store, stat, StatsScope.ALL_TIME, Messages.text("leaderboard.all-time"));
        result.whenComplete((lines, error) -> {
            try {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (sender instanceof Player player && !player.isConnected()) {
                        return;
                    }
                    if (error != null) {
                        sender.sendMessage(msg("leaderboard.unavailable"));
                    } else {
                        lines.forEach(sender::sendMessage);
                    }
                });
            } catch (IllegalPluginAccessException ignored) {
                // The plugin was disabled while the read was in flight.
            }
        });
    }

    private static CompletableFuture<List<Component>> read(StatsStore store, Stat stat, StatsScope scope,
            String period) {
        return store.top(stat, scope, Leaderboards.LIMIT).thenApply(entries -> Leaderboards.lines(stat, period, entries));
    }

    static List<String> complete(String[] args) {
        List<String> options = switch (args.length) {
            case 1 -> Leaderboards.STAT_NAMES;
            case 2 -> Leaderboards.stat(args[0]) == null ? List.of() : PERIODS;
            default -> List.of();
        };
        return options.stream().filter(option -> option.startsWith(args[args.length - 1].toLowerCase(Locale.ROOT)))
                .toList();
    }
}
