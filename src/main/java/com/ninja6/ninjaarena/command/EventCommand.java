package com.ninja6.ninjaarena.command;

import com.ninja6.ninjaarena.event.ArenaEvents;
import com.ninja6.ninjaarena.event.EventSettings.Night;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;

public final class EventCommand {

    public static final String NAME = "event";
    public static final String PERMISSION = "ninjaarena.event.manage";
    public static final String USAGE = "event <list|next|start <id>|cancel <id>>";

    private final ArenaEvents events;

    public EventCommand(ArenaEvents events) {
        this.events = events;
    }

    void execute(CommandSender sender, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("list")) {
            if (events.settings().schedule().isEmpty()) {
                send(sender, "No arena events are configured.");
            }
            for (Night night : events.settings().schedule()) {
                send(sender, night.id() + " • " + night.day() + " " + night.time() + " "
                        + events.settings().zone() + " • " + night.arena() + " • " + night.duration().toMinutes() + " min");
            }
        } else if (args.length == 1 && args[0].equalsIgnoreCase("next")) {
            events.active().ifPresent(window -> send(sender, "Active arena event: " + window.night().id()));
            send(sender, events.next().map(window -> "Next arena event: " + window.night().id() + " at "
                    + DateTimeFormatter.RFC_1123_DATE_TIME.format(window.start().atZone(events.settings().zone())))
                    .orElse("No upcoming arena event."));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("start")) {
            send(sender, events.start(args[1]) ? "Arena event started." : "Unknown event or an event is already active.");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("cancel")) {
            send(sender, events.cancel(args[1]) ? "Arena event occurrence cancelled." : "No occurrence of that event to cancel.");
        } else {
            send(sender, "Usage: /" + label + " " + USAGE);
        }
    }

    List<String> complete(String[] args) {
        if (args.length == 1) {
            return matching(List.of("list", "next", "start", "cancel"), args[0]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("start") || args[0].equalsIgnoreCase("cancel"))) {
            return matching(events.settings().schedule().stream().map(Night::id).toList(), args[1]);
        }
        return List.of();
    }

    private static List<String> matching(List<String> options, String prefix) {
        return options.stream().filter(value -> value.startsWith(prefix.toLowerCase(Locale.ROOT))).toList();
    }

    private static void send(CommandSender sender, String message) {
        sender.sendMessage(Component.text(message, NamedTextColor.YELLOW));
    }
}
