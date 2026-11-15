package com.ninja6.ninjaarena.shop;

import static com.ninja6.ninjaarena.message.Messages.msg;
import static com.ninja6.ninjaarena.message.Messages.text;

import com.ninja6.ninjaarena.match.MatchPhase;
import com.ninja6.ninjaarena.match.MatchService;
import com.ninja6.ninjaarena.match.MatchView;
import com.ninja6.ninjaarena.match.PlayerPoints;
import com.ninja6.ninjaarena.resourcepack.BedrockIntegration;
import com.ninja6.ninjaarena.shop.ShopCatalogue.Category;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bukkit.entity.Player;

/** Shop-only screen state and actions. All entry points run on the server thread. */
public final class ShopMenus {
    public enum Kind { BUY, CATEGORY, BACK, PREVIOUS, NEXT, READY, CLOSE, DISMISS }
    public record Action(Kind kind, String value) {
        public Action(Kind kind) { this(kind, ""); }
    }
    public record Button(String label, Action action) { }
    public record Content(String title, List<String> lines, List<Button> buttons) { }
    @FunctionalInterface
    public interface Press {
        void pressed(Player player, Action action);
    }
    public interface Surface {
        void show(Player player, Content content, Press press);
        void close(Player player);
    }

    public static final int PAGE_SIZE = 36;
    private record Active(MatchView match, int round, Category category, int page, Surface surface,
            long token, List<Action> actions) { }
    private final MatchService matches;
    private final Shop shop;
    private final PlayerPoints points;
    private final Supplier<Object> mode;
    private final BedrockIntegration integration;
    private final Surface dialog;
    private final Surface chest;
    private final java.util.function.Consumer<String> log;
    private final Map<UUID, Active> open = new HashMap<>();
    private long tokens;
    private boolean warnedMode;
    private boolean warnedDialog;

    public ShopMenus(MatchService matches, Shop shop, PlayerPoints points, Supplier<Object> mode,
            BedrockIntegration integration, Surface dialog, Surface chest, java.util.function.Consumer<String> log) {
        this.matches = matches;
        this.shop = shop;
        this.points = points;
        this.mode = mode;
        this.integration = integration;
        this.dialog = dialog;
        this.chest = chest;
        this.log = log;
    }

    public void open(Player player) {
        MatchView match = allowed(player, "shop.closed");
        if (match == null) { return; }
        Object setting = mode.get();
        boolean useChest = setting instanceof String value && value.equalsIgnoreCase("chest");
        if (setting != null && !useChest && !(setting instanceof String value && value.equalsIgnoreCase("dialog"))) {
            if (!warnedMode) {
                log.accept("shop.menu must be dialog or chest. Using dialog.");
                warnedMode = true;
            }
        }
        render(player, match, match.round(), null, 0, useChest ? chest : dialog, "");
    }

    private MatchView allowed(Player player, String closedMessage) {
        if (!player.hasPermission("ninjaarena.play")) {
            player.sendMessage(msg("command.no-permission"));
            return null;
        }
        MatchView match = matches.matchOf(player.getUniqueId()).orElse(null);
        if (match == null || !match.contains(player.getUniqueId()) || match.isDisconnected(player.getUniqueId())
                || match.phase() != MatchPhase.SHOP || match.result().isPresent()) {
            player.sendMessage(msg(closedMessage));
            return null;
        }
        return match;
    }

    private void press(Player player, UUID owner, long token, Action action) {
        Active active = open.get(owner);
        // Ignore callbacks from old forms, including a second delivery of the same purchase.
        if (!owner.equals(player.getUniqueId()) || active == null || active.token() != token
                || !active.actions().contains(action)) { return; }
        open.remove(owner);
        if (action.kind() == Kind.CLOSE || action.kind() == Kind.DISMISS) {
            if (action.kind() == Kind.CLOSE) { active.surface().close(player); }
            return;
        }
        MatchView match = allowed(player, action.kind() == Kind.READY ? "shop.not-in-shop" : "shop.closed");
        if (match != active.match() || match.round() != active.round()) {
            active.surface().close(player);
            if (match != null) { player.sendMessage(msg("shop.menu.stale")); }
            return;
        }
        Category category = active.category();
        int page = active.page();
        String note = "";
        switch (action.kind()) {
            case BUY -> {
                Shop.Result result = shop.buy(match, player, action.value());
                player.sendMessage(msg(result.messageKey()));
                note = text(result.messageKey());
            }
            case READY -> {
                player.sendMessage(msg(readyKey(matches.ready(player))));
                active.surface().close(player);
                return;
            }
            case CATEGORY -> { category = Category.valueOf(action.value()); page = 0; }
            case BACK -> { category = null; page = 0; }
            case PREVIOUS -> page--;
            case NEXT -> page++;
            default -> { return; }
        }
        // Exactly one fresh render after a purchase; never send an intermediate form.
        render(player, match, active.round(), category, page, active.surface(), note);
    }

    public static String readyKey(MatchService.Ready ready) {
        return switch (ready) {
            case READY -> "shop.ready";
            case ALREADY_READY -> "shop.already-ready";
            case NOT_IN_SHOP -> "shop.not-in-shop";
        };
    }

    private void render(Player player, MatchView match, int round, Category category, int page,
            Surface surface, String note) {
        UUID owner = player.getUniqueId();
        Content content = content(match, owner, category, page, note);
        long token = ++tokens;
        open.put(player.getUniqueId(), new Active(match, round, category, page, surface, token,
                java.util.stream.Stream.concat(content.buttons().stream().map(Button::action),
                        java.util.stream.Stream.of(new Action(Kind.CLOSE), new Action(Kind.DISMISS))).toList()));
        try {
            surface.show(player, content, (presser, action) -> press(presser, owner, token, action));
        } catch (RuntimeException | LinkageError e) {
            open.remove(player.getUniqueId());
            if (surface == dialog) {
                if (!warnedDialog) {
                    log.accept("The shop dialog could not be shown: " + e.getMessage() + "; using chest.");
                    warnedDialog = true;
                }
                try {
                    dialog.close(player);
                } catch (RuntimeException | LinkageError ignored) {
                    // A missing dialog API must not prevent the inventory fallback.
                }
                render(player, match, round, category, page, chest, note);
            } else {
                chest.close(player);
                player.sendMessage(msg("shop.menu.failed"));
            }
        }
    }

    Content content(MatchView match, UUID player, Category category, int page, String note) {
        List<ShopCatalogue.Entry> entries = List.copyOf(shop.catalogue(match).items().values());
        List<Category> categories = entries.stream().map(ShopCatalogue.Entry::category).distinct().toList();
        List<Button> buttons = new ArrayList<>();
        if (category == null && categories.size() > 1) {
            categories.forEach(value -> buttons.add(new Button(text("shop.menu.category."
                    + value.name().toLowerCase(Locale.ROOT)), new Action(Kind.CATEGORY, value.name()))));
        } else {
            List<ShopCatalogue.Entry> listed = entries.stream()
                    .filter(entry -> category == null || entry.category() == category).toList();
            int from = Math.min(Math.max(0, page) * PAGE_SIZE, listed.size());
            listed.subList(from, Math.min(from + PAGE_SIZE, listed.size())).forEach(entry -> {
                String name = name(entry.id());
                buttons.add(new Button(text(integration.isBedrock(player) ? "shop.menu.buy-bedrock" : "shop.menu.buy",
                        "name", name, "price", entry.price(),
                        "left", shop.remaining(match, player, entry.id())), new Action(Kind.BUY, entry.id())));
            });
            if (page > 0) { buttons.add(new Button(text("shop.menu.previous"), new Action(Kind.PREVIOUS))); }
            if (from + PAGE_SIZE < listed.size()) {
                buttons.add(new Button(text("shop.menu.next"), new Action(Kind.NEXT)));
            }
            if (category != null) { buttons.add(new Button(text("shop.menu.back"), new Action(Kind.BACK))); }
        }
        buttons.add(new Button(text("shop.menu.ready"), new Action(Kind.READY)));
        List<String> lines = new ArrayList<>();
        lines.add(text("shop.menu.balance", "points", points.points(match, player)));
        if (entries.isEmpty()) { lines.add(text("shop.menu.empty")); }
        if (!note.isEmpty()) { lines.add(note); }
        return new Content(text("shop.menu.title"), List.copyOf(lines), List.copyOf(buttons));
    }

    /** Catalogue ids are the names in the format; do not change the catalogue schema. */
    private static String name(String id) {
        String words = id.replace('-', ' ').replace('_', ' ');
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    public void forget(UUID id) { open.remove(id); }

    public void close(UUID id, Function<UUID, Player> players) {
        Active active = open.remove(id);
        Player player = players.apply(id);
        if (active != null && player != null) { active.surface().close(player); }
    }

    public void closeAll(Function<UUID, Player> players) {
        List.copyOf(open.keySet()).forEach(id -> close(id, players));
    }
}
