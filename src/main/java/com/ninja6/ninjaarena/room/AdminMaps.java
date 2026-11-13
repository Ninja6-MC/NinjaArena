package com.ninja6.ninjaarena.room;

import static com.ninja6.ninjaarena.message.Messages.msg;

import com.ninja6.ninjaarena.arena.Arena;
import com.ninja6.ninjaarena.command.AdminMapCommands;
import com.ninja6.ninjaarena.map.GeneratedMaps;
import com.ninja6.ninjaarena.map.SeedSettings;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /narena admin generate <seed> [difficulty] [teams]}: reproduces the layout of a seed
 * for an admin to look at, and {@code /narena admin generate free} clears it again.
 *
 * <p>The layout is generated and placed exactly as a room's map would be, in a free slot
 * cell, so it matches what a room playing that seed gets. It is never played: no match holds
 * it, nobody is moved there, and it takes no arena slot. One at a time: a second is refused
 * until the first is freed. It is refused while no slot cell is free beyond those leased rooms
 * wait for, so it never holds up a room; while it is placed, it uses up a cell as a room's map
 * would, and arena edits are refused. Its seed does not count under
 * {@code maps.seed-sharing: distinct}, so the layout a room plays now can be reproduced. It
 * is held in memory only and is gone after a restart. Server thread only.
 */
public final class AdminMaps implements AdminMapCommands {

    /** The teams a layout is generated for when none is given, as for a new room. */
    public static final int DEFAULT_TEAMS = RoomService.GENERATED_TEAMS;

    private final GeneratedMaps generated;
    private final BooleanSupplier spareCell;
    private final IntSupplier defaultDifficulty;
    private final Logger logger;
    private String map;

    /**
     * @param spareCell         {@code RoomService#hasSpareCell}
     * @param defaultDifficulty {@code maps.default.difficulty}
     */
    public AdminMaps(GeneratedMaps generated, BooleanSupplier spareCell, IntSupplier defaultDifficulty,
            Logger logger) {
        this.generated = Objects.requireNonNull(generated, "generated");
        this.spareCell = Objects.requireNonNull(spareCell, "spareCell");
        this.defaultDifficulty = Objects.requireNonNull(defaultDifficulty, "defaultDifficulty");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** The name of the admin's layout while it has a cell. */
    public Optional<String> map() {
        return Optional.ofNullable(map);
    }

    @Override
    public void generate(CommandSender sender, long seed, Integer difficulty, Integer teams) {
        int level = difficulty != null ? difficulty : defaultDifficulty.getAsInt();
        if (level < SeedSettings.MIN_DIFFICULTY || level > SeedSettings.MAX_DIFFICULTY) {
            sender.sendMessage(msg("generate.difficulty-invalid", "min", SeedSettings.MIN_DIFFICULTY,
                    "max", SeedSettings.MAX_DIFFICULTY));
            return;
        }
        int sides = teams != null ? teams : DEFAULT_TEAMS;
        if (sides != 2 && sides != 4) {
            sender.sendMessage(msg("generate.teams-invalid"));
            return;
        }
        if (map != null) {
            sender.sendMessage(msg("generate.busy", "map", map));
            return;
        }
        if (!spareCell.getAsBoolean()) {
            sender.sendMessage(msg("generate.no-cell"));
            return;
        }
        String name = generated.newName();
        map = name;
        sender.sendMessage(msg("generate.started", "seed", seed, "difficulty", level, "teams", sides));
        try {
            generated.prepare(name, new GeneratedMaps.Request(seed, level, sides),
                    outcome -> prepared(sender, name, seed, level, sides, outcome));
        } catch (RuntimeException e) {
            map = null;
            logger.log(Level.SEVERE, "Could not generate seed " + seed + " for " + sender.getName() + ".", e);
            sender.sendMessage(msg("generate.no-cell"));
        }
    }

    private void prepared(CommandSender sender, String name, long seed, int level, int sides,
            GeneratedMaps.Prepared outcome) {
        if (!name.equals(map)) {
            return;
        }
        switch (outcome) {
            case GeneratedMaps.Ready ready -> {
                Arena arena = ready.arena();
                logger.info(sender.getName() + " generated seed " + seed + " at difficulty " + level + " for " + sides
                        + " teams in slot cell " + arena.cell().describe() + ", blocks " + arena.bounds().describe()
                        + ".");
                tell(sender, "generate.ready", "seed", seed, "difficulty", level, "teams", sides,
                        "cell", arena.cell().describe(), "bounds", arena.bounds().describe(),
                        "accepted", ready.seed());
            }
            case GeneratedMaps.Failed failed -> {
                map = null;
                generated.free(name);
                tell(sender, "generate.failed", "seed", seed, "attempts", failed.attempts());
            }
        }
    }

    @Override
    public void free(CommandSender sender) {
        if (map == null) {
            sender.sendMessage(msg("generate.none"));
            return;
        }
        generated.free(map);
        map = null;
        sender.sendMessage(msg("generate.freed"));
    }

    private static void tell(CommandSender sender, String key, Object... placeholders) {
        if (!(sender instanceof Player player) || player.isOnline()) {
            sender.sendMessage(msg(key, placeholders));
        }
    }
}
