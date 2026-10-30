package com.ninja6.ninjaarena.command;

import com.ninja6.ninjaarena.director.MatchDirector;
import com.ninja6.ninjaarena.match.MatchView;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /ninjaarena director [arena]}: the enemy director's state for the match the admin is
 * in, or the match running in the named arena. The root checks the admin node first.
 */
public final class DirectorCommand {

    public static final String NAME = "director";

    private final Supplier<? extends Collection<? extends MatchView>> matches;
    private final Function<UUID, Optional<MatchView>> matchOf;
    private final Function<MatchView, Optional<MatchDirector>> directors;

    /**
     * @param matches   the running matches; {@code MatchService#matches}
     * @param matchOf   the match a player is in; {@code MatchService#matchOf}
     * @param directors a match's director; {@code DirectorService#director}
     */
    public DirectorCommand(Supplier<? extends Collection<? extends MatchView>> matches,
            Function<UUID, Optional<MatchView>> matchOf, Function<MatchView, Optional<MatchDirector>> directors) {
        this.matches = Objects.requireNonNull(matches, "matches");
        this.matchOf = Objects.requireNonNull(matchOf, "matchOf");
        this.directors = Objects.requireNonNull(directors, "directors");
    }

    void execute(CommandSender sender, String label, String[] args) {
        Optional<? extends MatchView> match;
        if (args.length > 0) {
            match = running(args[0]);
            if (match.isEmpty()) {
                error(sender, "No match is running in arena " + args[0] + ".");
                return;
            }
        } else {
            match = sender instanceof Player player ? matchOf.apply(player.getUniqueId()) : Optional.empty();
            if (match.isEmpty()) {
                error(sender, "Usage: /" + label + " " + NAME + " <arena>, or run it while in a match.");
                return;
            }
        }
        show(sender, match.get());
    }

    List<String> complete(CommandSender sender, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (MatchView match : matches.get()) {
            if (match.arena().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                out.add(match.arena());
            }
        }
        return out;
    }

    private Optional<? extends MatchView> running(String arena) {
        return matches.get().stream().filter(match -> match.arena().equalsIgnoreCase(arena)).findFirst();
    }

    private void show(CommandSender sender, MatchView match) {
        sender.sendMessage(Component.text("Director of arena " + match.arena() + ", round " + match.round() + " ("
                + match.phase().name().toLowerCase(Locale.ROOT) + "):", NamedTextColor.GOLD));
        Optional<MatchDirector> found = directors.apply(match);
        if (found.isEmpty()) {
            line(sender, "Not started", "it starts with the match's first fight.");
            return;
        }
        MatchDirector director = found.get();
        line(sender, "Round budget", format(director.roundBudget()) + ", intensity " + format(director.intensity()));
        StringJoiner kinds = new StringJoiner(", ");
        int count = 0;
        for (MatchDirector.InPlay inPlay : director.inPlay()) {
            count += inPlay.count();
            kinds.add(inPlay.count() + " " + inPlay.kind().name().toLowerCase(Locale.ROOT) + " (threat "
                    + inPlay.threat() + ")");
        }
        line(sender, "Enemies in play", count + ", threat " + format(director.threatInPlay())
                + (count == 0 ? "" : ": " + kinds));
        line(sender, "Last spawn refusal", director.lastRefusal().orElse("none"));
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static void line(CommandSender sender, String name, String value) {
        sender.sendMessage(Component.text(name + ": ", NamedTextColor.YELLOW)
                .append(Component.text(value, NamedTextColor.GRAY)));
    }

    private static void error(CommandSender sender, String message) {
        sender.sendMessage(Component.text(message, NamedTextColor.RED));
    }
}
