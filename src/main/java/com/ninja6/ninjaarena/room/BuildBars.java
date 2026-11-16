package com.ninja6.ninjaarena.room;

import static com.ninja6.ninjaarena.message.Messages.msg;

import com.ninja6.ninjaarena.map.GeneratedMaps;
import java.util.Collection;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * The boss bar a room's members see while its generated map is being built, one per room.
 * Each update only reads the progress the placement already keeps
 * ({@link GeneratedMaps#progress}); the bar's text changes only when the whole percent does.
 * A room's bar goes when its map is placed, when no map came from the seed, and when the
 * room closes; a member who leaves the room stops seeing it. Server thread only.
 */
final class BuildBars {

    private static final class Shown {
        final BossBar bar = BossBar.bossBar(Component.empty(), 0, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
        final Set<UUID> viewers = new HashSet<>();
        int percent = -1;
    }

    private final Function<UUID, Player> online;
    private final Map<Room, Shown> shown = new IdentityHashMap<>();

    BuildBars(Function<UUID, Player> online) {
        this.online = Objects.requireNonNull(online, "online");
    }

    /**
     * Shows each room that is building its map the progress, and takes the bar from rooms
     * that are not.
     *
     * @param progress how far the room's map has got, empty when it is not being built
     */
    void update(Collection<Room> rooms, Function<Room, Optional<GeneratedMaps.Progress>> progress) {
        Set<Room> building = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (Room room : rooms) {
            Optional<GeneratedMaps.Progress> done = progress.apply(room);
            if (done.isEmpty()) {
                continue;
            }
            building.add(room);
            Shown bar = shown.computeIfAbsent(room, ignored -> new Shown());
            float fraction = (float) Math.clamp(done.get().fraction(), 0, 1);
            if (bar.bar.progress() != fraction) {
                bar.bar.progress(fraction);
            }
            int percent = percent(done.get());
            if (percent != bar.percent) {
                bar.percent = percent;
                bar.bar.name(msg("room.build-progress", "percent", percent));
            }
            Set<UUID> members = Set.copyOf(room.members());
            for (UUID viewer : List.copyOf(bar.viewers)) {
                Player player = online.apply(viewer);
                if (!members.contains(viewer) || player == null) {
                    bar.viewers.remove(viewer);
                    if (player != null) {
                        player.hideBossBar(bar.bar);
                    }
                }
            }
            for (UUID member : members) {
                Player player = online.apply(member);
                if (player != null && bar.viewers.add(member)) {
                    player.showBossBar(bar.bar);
                }
            }
        }
        for (Room room : List.copyOf(shown.keySet())) {
            if (!building.contains(room)) {
                remove(room);
            }
        }
    }

    /** Whether the room's members are shown a bar. */
    boolean isShown(Room room) {
        return shown.containsKey(room);
    }

    /** Takes the room's bar away from everyone who sees it. */
    void remove(Room room) {
        Shown bar = shown.remove(room);
        if (bar == null) {
            return;
        }
        for (UUID viewer : bar.viewers) {
            Player player = online.apply(viewer);
            if (player != null) {
                player.hideBossBar(bar.bar);
            }
        }
    }

    /** Takes every bar away. */
    void close() {
        for (Room room : List.copyOf(shown.keySet())) {
            remove(room);
        }
    }

    /** The whole percent done, 100 only once complete. */
    static int percent(GeneratedMaps.Progress progress) {
        if (progress.isComplete()) {
            return 100;
        }
        return (int) Math.min(99, Math.floor(progress.fraction() * 100));
    }
}
