package com.ninja6.ninjaarena.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.map.GeneratedMaps.Progress;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class BuildBarsTest {

    private static final UUID HOST = new UUID(0, 1);
    private static final UUID GUEST = new UUID(0, 2);

    private final Map<UUID, List<BossBar>> shown = new HashMap<>();
    private final Map<UUID, Player> online = new HashMap<>();

    private Player player(UUID id) {
        shown.put(id, new ArrayList<>());
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "showBossBar" -> { shown.get(id).add((BossBar) args[0]); yield null; }
                    case "hideBossBar" -> { shown.get(id).remove((BossBar) args[0]); yield null; }
                    case "getUniqueId" -> id;
                    case "hashCode" -> id.hashCode();
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        online.put(id, player);
        return player;
    }

    @Test
    void percentIsWholeAndOnlyAHundredWhenComplete() {
        assertEquals(0, BuildBars.percent(new Progress(0, 10)));
        assertEquals(42, BuildBars.percent(new Progress(42, 100)));
        assertEquals(99, BuildBars.percent(new Progress(9999, 10000)));
        assertEquals(100, BuildBars.percent(new Progress(5, 5)));
        assertEquals(100, BuildBars.percent(new Progress(0, 0)));
    }

    @Test
    void theBarIsUpdatedInPlaceAndGoesWhenTheRoomStopsBuilding() {
        player(HOST);
        player(GUEST);
        Room room = new Room(HOST, "generated:1", new RoomSettings(false, 2, 2, 0));
        room.join(GUEST);
        BuildBars bars = new BuildBars(online::get);
        Map<Room, Optional<Progress>> progress = new HashMap<>();
        progress.put(room, Optional.of(new Progress(25, 100)));

        bars.update(List.of(room), progress::get);
        BossBar bar = shown.get(HOST).getFirst();
        assertEquals(List.of(bar), shown.get(GUEST));
        assertEquals(0.25f, bar.progress());
        assertEquals("Building the room's map: 25%",
                PlainTextComponentSerializer.plainText().serialize(bar.name()));

        progress.put(room, Optional.of(new Progress(50, 100)));
        bars.update(List.of(room), progress::get);
        assertEquals(List.of(bar), shown.get(HOST), "the same bar, not a new one");
        assertEquals(0.5f, bar.progress());

        // A member who goes offline is dropped without a call; one who comes back sees it again.
        online.remove(GUEST);
        bars.update(List.of(room), progress::get);
        online.put(GUEST, player(GUEST));
        bars.update(List.of(room), progress::get);
        assertEquals(List.of(bar), shown.get(GUEST));

        progress.put(room, Optional.empty());
        bars.update(List.of(room), progress::get);
        assertFalse(bars.isShown(room));
        assertTrue(shown.get(HOST).isEmpty());
        assertTrue(shown.get(GUEST).isEmpty());

        progress.put(room, Optional.of(new Progress(1, 100)));
        bars.update(List.of(room), progress::get);
        assertTrue(bars.isShown(room));
        bars.update(List.of(), progress::get);
        assertFalse(bars.isShown(room), "a room no longer open loses its bar");
        assertTrue(shown.get(HOST).isEmpty());
    }
}
