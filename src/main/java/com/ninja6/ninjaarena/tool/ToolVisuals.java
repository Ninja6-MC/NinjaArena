package com.ninja6.ninjaarena.tool;

import com.ninja6.ninjaarena.match.MatchView;
import com.ninja6.ninjaarena.resourcepack.LoadedPacks;
import java.util.Collection;
import java.util.HashSet;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.inventory.ItemStack;

/** Give-time held models and a single, shared flight decision made at launch. Server thread only. */
public final class ToolVisuals {
    @FunctionalInterface
    public interface Items {
        ItemStack create(NinjaTool tool, boolean custom, int color);
    }

    private final LoadedPacks packs;
    private final java.util.function.Predicate<UUID> customHeld;
    private final BooleanSupplier thrownVisuals;
    private final Function<String, Collection<UUID>> spectators;
    private final Function<UUID, java.util.Optional<MatchView>> matches;
    private final Items items;
    private final java.util.function.Supplier<ItemStack> snowball;

    public ToolVisuals(LoadedPacks packs, BooleanSupplier thrownVisuals,
            Function<String, Collection<UUID>> spectators,
            Function<UUID, java.util.Optional<MatchView>> matches) {
        this(packs, thrownVisuals, spectators, matches, NinjaTool::create, () -> ItemStack.of(Material.SNOWBALL));
    }

    public ToolVisuals(LoadedPacks packs, java.util.function.Predicate<UUID> customHeld, BooleanSupplier thrownVisuals,
            Function<String, Collection<UUID>> spectators,
            Function<UUID, java.util.Optional<MatchView>> matches) {
        this(packs, customHeld, thrownVisuals, spectators, matches, NinjaTool::create, () -> ItemStack.of(Material.SNOWBALL));
    }

    ToolVisuals(LoadedPacks packs, BooleanSupplier thrownVisuals,
            Function<String, Collection<UUID>> spectators,
            Function<UUID, java.util.Optional<MatchView>> matches, Items items,
            java.util.function.Supplier<ItemStack> snowball) {
        this(packs, packs::loaded, thrownVisuals, spectators, matches, items, snowball);
    }

    ToolVisuals(LoadedPacks packs, java.util.function.Predicate<UUID> customHeld, BooleanSupplier thrownVisuals,
            Function<String, Collection<UUID>> spectators,
            Function<UUID, java.util.Optional<MatchView>> matches, Items items,
            java.util.function.Supplier<ItemStack> snowball) {
        this.packs = packs;
        this.customHeld = customHeld;
        this.thrownVisuals = thrownVisuals;
        this.spectators = spectators;
        this.matches = matches;
        this.items = items;
        this.snowball = snowball;
    }

    public ItemStack give(NinjaTool tool, Player player) {
        int team = matches.apply(player.getUniqueId()).map(match -> match.teamOf(player.getUniqueId())).orElse(0);
        return items.create(tool, customHeld.test(player.getUniqueId()), teamColor(team));
    }

    /** Includes the roster and spectators even if they are temporarily outside the arena world. */
    public boolean customFlight(MatchView match, Collection<UUID> worldPlayers) {
        if (!thrownVisuals.getAsBoolean()) {
            return false;
        }
        var viewers = new HashSet<>(match.players());
        // A player away within the rejoin grace sees nothing until they are back.
        viewers.removeAll(match.disconnectedPlayers());
        viewers.addAll(spectators.apply(match.arena()));
        viewers.addAll(worldPlayers);
        return viewers.stream().allMatch(packs::loaded);
    }

    public void flight(Snowball projectile, NinjaTool tool, MatchView match, UUID thrower) {
        boolean custom = customFlight(match, projectile.getWorld().getPlayers().stream()
                .map(Player::getUniqueId).toList());
        projectile.setItem(custom ? items.create(tool, true, teamColor(match.teamOf(thrower))) : snowball.get());
    }

    /** The four arena teams use red, blue, green and yellow; unassigned items have a white accent. */
    public static int teamColor(int team) {
        return switch (team) {
            case 1 -> 0xFF5555;
            case 2 -> 0x5555FF;
            case 3 -> 0x55FF55;
            case 4 -> 0xFFFF55;
            default -> 0xFFFFFF;
        };
    }
}
