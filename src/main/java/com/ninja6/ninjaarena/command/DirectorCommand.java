package com.ninja6.ninjaarena.command;

import static com.ninja6.ninjaarena.message.Messages.msg;
import static com.ninja6.ninjaarena.message.Messages.text;

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
                sender.sendMessage(msg("director.no-match", "arena", args[0]));
                return;
            }
        } else {
            match = sender instanceof Player player ? matchOf.apply(player.getUniqueId()) : Optional.empty();
            if (match.isEmpty()) {
                sender.sendMessage(msg("director.usage", "label", label, "usage", NAME + " <arena>"));
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
        sender.sendMessage(msg("director.header", "arena", match.arena(), "round", match.round(),
                "phase", match.phase().name().toLowerCase(Locale.ROOT)));
        Optional<MatchDirector> found = directors.apply(match);
        if (found.isEmpty()) {
            sender.sendMessage(msg("director.not-started"));
            return;
        }
        MatchDirector director = found.get();
        sender.sendMessage(msg("director.budget", "budget", format(director.roundBudget()),
                "intensity", format(director.intensity())));
        StringJoiner kinds = new StringJoiner(", ");
        int count = 0;
        for (MatchDirector.InPlay inPlay : director.inPlay()) {
            count += inPlay.count();
            kinds.add(text("director.kind", "count", inPlay.count(), "kind", inPlay.kind().label(),
                    "threat", inPlay.threat()));
        }
        sender.sendMessage(count == 0
                ? msg("director.enemies-none", "count", count, "threat", format(director.threatInPlay()))
                : msg("director.enemies", "count", count, "threat", format(director.threatInPlay()),
                        "kinds", kinds.toString()));
        sender.sendMessage(msg("director.refusal", "refusal", director.lastRefusal()
                .<Object>map(refusal -> refusal).orElseGet(() -> msg("director.none"))));
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

}
