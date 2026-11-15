package com.ninja6.ninjaarena.room;

import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Platform-neutral room screen; actions receive the submitted input values. */
public record RoomScreen(String title, List<String> lines, List<Option> options, Input input, String exit) {
    public record Option(String label, Material icon, Action action) { }
    public record Input(String label, List<String> values, List<String> labels, String initial) { }

    @FunctionalInterface
    public interface Action {
        void run(Player player, Map<String, String> values);
    }

    @FunctionalInterface
    public interface Press {
        void run(Player player, int option, Map<String, String> values);
    }

    public interface Screens {
        void show(Player player, RoomScreen screen, Press press);
        void close(Player player);
    }
}
