package com.ninja6.ninjaarena.texture;

import static com.ninja6.ninjaarena.message.Messages.text;

import com.ninja6.ninjaarena.resourcepack.BedrockSelections.Quality;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * The "Arena Texture Quality" chest menu. It uses vanilla icons and plain text so it works
 * without either pack, and it is identified by its holder and a per-open token, never by its
 * title. Every choice rechecks ownership and permission.
 */
public final class TextureMenu {

    public static final String PERMISSION = "ninjaarena.play";
    public static final int SIZE = 27;
    public static final int STANDARD_SLOT = 10;
    public static final int DETAILED_SLOT = 12;
    public static final int STATUS_SLOT = 14;
    public static final int CLOSE_SLOT = 16;

    /** One icon: plain text, with the colour only as a second cue to the words. */
    public record Item(Material icon, String name, NamedTextColor color, List<String> lore, boolean glint) {
    }

    /** Builds the inventory and its items; the server-backed implementation lives in the plugin. */
    public interface Screens {
        Inventory create(InventoryHolder holder, int size, String title);

        ItemStack item(Item item);
    }

    /** Owns one opening of the menu. A stale holder is any that is not the player's current one. */
    public static final class Holder implements InventoryHolder {
        private final UUID owner;
        private final long token;
        private Inventory inventory;

        private Holder(UUID owner, long token) {
            this.owner = owner;
            this.token = token;
        }

        public UUID owner() {
            return owner;
        }

        public long token() {
            return token;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class Active {
        private final Holder holder;
        private Quality saving;
        private String note = "";

        private Active(Holder holder) {
            this.holder = holder;
        }
    }

    private final TextureService service;
    private final Screens screens;
    private final TextureService.Notifier notifier;
    private final Map<UUID, Active> open = new HashMap<>();
    private long tokens;

    public TextureMenu(TextureService service, Screens screens, TextureService.Notifier notifier) {
        this.service = Objects.requireNonNull(service, "service");
        this.screens = Objects.requireNonNull(screens, "screens");
        this.notifier = Objects.requireNonNull(notifier, "notifier");
    }

    public void open(Player player) {
        UUID id = player.getUniqueId();
        if (!player.hasPermission(PERMISSION)) {
            notifier.tell(id, text("command.no-permission"));
            return;
        }
        Holder holder = new Holder(id, ++tokens);
        holder.inventory = screens.create(holder, SIZE, text("texture.title"));
        Active active = new Active(holder);
        open.put(id, active);
        render(active);
        if (player.openInventory(holder.inventory) == null) {
            // Another rule, such as a match's inventory lockdown, refused the window.
            open.remove(id, active);
            notifier.tell(id, text("texture.locked"));
        }
    }

    /** The holder of the player's current menu, if any. */
    public boolean isCurrent(UUID player, Holder holder) {
        Active active = open.get(player);
        return active != null && active.holder == holder;
    }

    /** A click on {@code slot} of the menu's own (top) inventory. */
    public void click(Player player, Holder holder, int slot) {
        UUID id = player.getUniqueId();
        Active active = open.get(id);
        if (active == null || active.holder != holder || !holder.owner.equals(id)) {
            player.closeInventory();
            notifier.tell(id, text("texture.menu-stale"));
            return;
        }
        if (!player.hasPermission(PERMISSION)) {
            open.remove(id);
            player.closeInventory();
            notifier.tell(id, text("texture.menu-no-permission"));
            return;
        }
        switch (slot) {
            case STANDARD_SLOT -> choose(id, active, Quality.STANDARD);
            case DETAILED_SLOT -> choose(id, active, Quality.DETAILED);
            case STATUS_SLOT -> {
                active.note = "";
                render(active);
            }
            case CLOSE_SLOT -> player.closeInventory();
            default -> { }
        }
    }

    private void choose(UUID id, Active active, Quality quality) {
        if (active.saving != null) {
            notifier.tell(id, text("texture.still-saving"));
            return;
        }
        active.saving = quality;
        render(active);
        service.choose(id, quality, result -> {
            if (open.get(id) == active) {
                active.saving = null;
                active.note = result.message();
                render(active);
            }
            notifier.tell(id, result.message());
        });
    }

    public void closed(UUID player, Holder holder) {
        Active active = open.get(player);
        if (active != null && active.holder == holder) {
            open.remove(player);
        }
    }

    public void forget(UUID player) {
        open.remove(player);
    }

    public void closeAll(java.util.function.Function<UUID, Player> players) {
        for (UUID id : List.copyOf(open.keySet())) {
            Player player = players.apply(id);
            open.remove(id);
            if (player != null) {
                player.closeInventory();
            }
        }
    }

    private void render(Active active) {
        Inventory inventory = active.holder.inventory;
        Map<Integer, Item> items = layout(service.describe(active.holder.owner), active.saving, active.note);
        for (var entry : items.entrySet()) {
            inventory.setItem(entry.getKey(), screens.item(entry.getValue()));
        }
    }

    /** The icons for the current state. Pure, so the wording is tested without a server. */
    static Map<Integer, Item> layout(TextureService.View view, Quality saving, String note) {
        Map<Integer, Item> items = new LinkedHashMap<>();
        items.put(STANDARD_SLOT, standard(view, saving));
        items.put(DETAILED_SLOT, detailed(view, saving));
        items.put(STATUS_SLOT, status(view, note));
        items.put(CLOSE_SLOT, new Item(Material.OAK_DOOR, text("texture.menu.close"), NamedTextColor.WHITE,
                List.of(text("texture.menu.close-lore")), false));
        return items;
    }

    private static Item standard(TextureService.View view, Quality saving) {
        boolean selected = view.saved() == Quality.STANDARD;
        String line = saving == Quality.STANDARD ? text("texture.menu.saving")
                : selected ? selectedLine(view) : text("texture.menu.choose-standard");
        return new Item(Material.PAPER, text("texture.menu.standard"),
                selected ? NamedTextColor.GREEN : NamedTextColor.WHITE,
                List.of(text("texture.menu.standard-lore"), line), selected);
    }

    private static Item detailed(TextureService.View view, Quality saving) {
        boolean selected = view.saved() == Quality.DETAILED;
        if (!view.detailedAvailable()) {
            return new Item(Material.BARRIER, text("texture.menu.detailed-unavailable"), NamedTextColor.GRAY,
                    selected
                            ? List.of(text("texture.menu.unavailable-lore"), text("texture.menu.selected-unavailable"))
                            : List.of(text("texture.menu.unavailable-lore")),
                    false);
        }
        String line = saving == Quality.DETAILED ? text("texture.menu.saving")
                : selected ? selectedLine(view) : text("texture.menu.choose-detailed");
        return new Item(Material.DIAMOND, text("texture.menu.detailed"),
                selected ? NamedTextColor.GREEN : NamedTextColor.WHITE,
                List.of(text("texture.menu.detailed-lore"),
                        view.bedrock() ? text("texture.menu.detailed-bedrock")
                                : view.sessionOnly()
                                        ? text("texture.menu.detailed-session", "note", text("texture.session-note"))
                                        : text("texture.menu.detailed-java"),
                        line),
                selected);
    }

    private static String selectedLine(TextureService.View view) {
        return view.sessionOnly() ? text("texture.menu.selected-session", "note", text("texture.session-note"))
                : text(view.explicit().isPresent() ? "texture.menu.selected-saved" : "texture.menu.selected-default");
    }

    static String choiceLine(TextureService.View view) {
        String quality = TextureService.display(view.saved());
        if (view.sessionOnly()) {
            return text("texture.choice.session", "quality", quality);
        }
        return view.explicit().isPresent() ? text("texture.choice.saved", "quality", quality)
                : text("texture.choice.default");
    }

    private static Item status(TextureService.View view, String note) {
        List<String> lore = new java.util.ArrayList<>();
        lore.add(choiceLine(view));
        lore.add(text("texture.menu.right-now", "delivery", view.delivery()));
        if (!view.storage().isEmpty()) {
            lore.add(view.storage());
        }
        if (!note.isEmpty()) {
            lore.add(note);
        }
        return new Item(Material.BOOK, text("texture.menu.status"), NamedTextColor.YELLOW, List.copyOf(lore), false);
    }

    /** Splits a line at spaces so item text stays inside the tooltip. */
    static List<String> wrap(String line, int width) {
        List<String> lines = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : line.split(" ")) {
            if (current.length() > 0 && current.length() + 1 + word.length() > width) {
                lines.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append(' ');
            }
            current.append(word);
        }
        lines.add(current.toString());
        return lines;
    }

    /** Renders items with Paper's component API. */
    public static final class BukkitScreens implements Screens {
        @Override
        public Inventory create(InventoryHolder holder, int size, String title) {
            return org.bukkit.Bukkit.createInventory(holder, size, Component.text(title));
        }

        @Override
        public ItemStack item(Item item) {
            ItemStack stack = new ItemStack(item.icon());
            stack.editMeta(meta -> {
                meta.displayName(Component.text(item.name(), item.color())
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
                meta.lore(item.lore().stream().flatMap(line -> wrap(line, 44).stream())
                        .map(line -> (Component) Component.text(line, NamedTextColor.GRAY)
                                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)).toList());
                if (item.glint()) {
                    meta.setEnchantmentGlintOverride(true);
                }
            });
            return stack;
        }
    }
}
