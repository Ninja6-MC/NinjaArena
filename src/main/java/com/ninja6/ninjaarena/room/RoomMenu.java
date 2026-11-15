package com.ninja6.ninjaarena.room;

import static com.ninja6.ninjaarena.message.Messages.msg;
import static com.ninja6.ninjaarena.message.Messages.text;

import com.ninja6.ninjaarena.kit.PluginMenuHolder;
import com.ninja6.ninjaarena.texture.TextureMenu;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/** Chest fallback for every room screen. Holder identity grants the plugin-menu exemption. */
public final class RoomMenu implements RoomScreen.Screens {
    public static final int SIZE = 54;
    public static final int PAGE_SIZE = 45;
    public static final int INPUT = 45;
    public static final int PREVIOUS = 48;
    public static final int NEXT = 50;
    public static final int EXIT = 53;

    public static final class Holder implements PluginMenuHolder {
        private final UUID owner;
        private final RoomScreen screen;
        private final RoomScreen.Press press;
        private Inventory inventory;
        private int page;
        private String value;

        private Holder(UUID owner, RoomScreen screen, RoomScreen.Press press) {
            this.owner = owner;
            this.screen = screen;
            this.press = press;
            this.value = screen.input() == null ? null : screen.input().initial();
        }

        public UUID owner() { return owner; }

        @Override
        public Inventory getInventory() { return inventory; }
    }

    private final TextureMenu.Screens screens;
    private final Executor main;
    private final Map<UUID, Holder> open = new HashMap<>();

    public RoomMenu(TextureMenu.Screens screens, Executor main) {
        this.screens = screens;
        this.main = main;
    }

    @Override
    public void show(Player player, RoomScreen screen, RoomScreen.Press press) {
        Holder holder = new Holder(player.getUniqueId(), screen, press);
        holder.inventory = screens.create(holder, SIZE, screen.title());
        render(holder);
        // The old inventory closes synchronously while opening the new one.
        open.put(holder.owner, holder);
        if (player.openInventory(holder.inventory) == null) {
            open.remove(holder.owner, holder);
            player.sendMessage(msg("room.screen.refused"));
        }
    }

    private void render(Holder holder) {
        Inventory inventory = holder.inventory;
        inventory.clear();
        int start = holder.page * PAGE_SIZE;
        List<RoomScreen.Option> options = holder.screen.options();
        for (int i = start; i < Math.min(options.size(), start + PAGE_SIZE); i++) {
            var option = options.get(i);
            item(inventory, i - start, option.icon(), option.label(), holder.screen.lines());
        }
        if (holder.screen.input() != null) {
            var input = holder.screen.input();
            int index = input.values().indexOf(holder.value);
            item(inventory, INPUT, Material.COMPARATOR,
                    text("room.screen.input-value", "label", input.label(), "value", input.labels().get(index)),
                    List.of(text("room.screen.cycle")));
        }
        if (holder.page > 0) {
            item(inventory, PREVIOUS, Material.ARROW, text("room.screen.previous"), List.of());
        }
        if (start + PAGE_SIZE < options.size()) {
            item(inventory, NEXT, Material.ARROW, text("room.screen.next-page"), List.of());
        }
        item(inventory, EXIT, Material.OAK_DOOR, holder.screen.exit(), List.of());
    }

    private void item(Inventory inventory, int slot, Material icon, String label, List<String> lore) {
        inventory.setItem(slot, screens.item(new TextureMenu.Item(icon, label, NamedTextColor.WHITE, lore, false)));
    }

    /** Called after cancelling the inventory event; window changes must wait until the next tick. */
    public void click(Player player, Holder holder, int slot, boolean backwards) {
        main.execute(() -> {
            if (!player.getUniqueId().equals(holder.owner) || open.get(holder.owner) != holder) {
                return;
            }
            if (slot == INPUT && holder.screen.input() != null) {
                List<String> values = holder.screen.input().values();
                int index = Math.floorMod(values.indexOf(holder.value) + (backwards ? -1 : 1), values.size());
                holder.value = values.get(index);
                render(holder);
            } else if (slot == PREVIOUS && holder.page > 0) {
                holder.page--;
                render(holder);
            } else if (slot == NEXT && (holder.page + 1) * PAGE_SIZE < holder.screen.options().size()) {
                holder.page++;
                render(holder);
            } else if (slot == EXIT) {
                holder.press.run(player, -1, Map.of());
            } else if (slot >= 0 && slot < PAGE_SIZE) {
                int index = holder.page * PAGE_SIZE + slot;
                if (index < holder.screen.options().size()) {
                    holder.press.run(player, index, holder.value == null ? Map.of() : Map.of("value", holder.value));
                }
            }
        });
    }

    public void closed(Holder holder) {
        open.remove(holder.owner, holder);
    }

    public void forget(UUID player) {
        open.remove(player);
    }

    @Override
    public void close(Player player) {
        Holder holder = open.remove(player.getUniqueId());
        if (holder != null && player.getOpenInventory().getTopInventory() == holder.inventory) {
            player.closeInventory();
        }
    }
}
