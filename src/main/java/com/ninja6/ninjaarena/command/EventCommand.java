package com.ninja6.ninjaarena.command;

import static com.ninja6.ninjaarena.message.Messages.msg;

import com.ninja6.ninjaarena.event.ArenaEvents;
import com.ninja6.ninjaarena.event.EventSettings.Night;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
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
                send(sender, "event.none-configured");
            }
            for (Night night : events.settings().schedule()) {
                send(sender, "event.list-line", "id", night.id(), "day", night.day(), "time", night.time(),
                        "zone", events.settings().zone(), "arena", night.arena(),
                        "minutes", night.duration().toMinutes());
            }
        } else if (args.length == 1 && args[0].equalsIgnoreCase("next")) {
            events.active().ifPresent(window -> send(sender, "event.active", "id", window.night().id()));
            events.next().ifPresentOrElse(window -> send(sender, "event.next", "id", window.night().id(), "start",
                    DateTimeFormatter.RFC_1123_DATE_TIME.format(window.start().atZone(events.settings().zone()))),
                    () -> send(sender, "event.no-upcoming"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("start")) {
            send(sender, events.start(args[1]) ? "event.started" : "event.start-failed");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("cancel")) {
            send(sender, events.cancel(args[1]) ? "event.cancelled" : "event.cancel-failed");
        } else {
            send(sender, "event.usage", "label", label, "usage", USAGE);
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

    private static void send(CommandSender sender, String key, Object... placeholders) {
        sender.sendMessage(msg(key, placeholders));
    }
}
