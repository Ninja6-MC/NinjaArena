package com.ninja6.ninjaarena.lobby;

import static com.ninja6.ninjaarena.message.Messages.msg;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Cushion;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;

/**
 * The lobby build in the lobby world: which one stands there, and when the generated lobby
 * is placed ({@link GeneratedLobby}).
 *
 * <p><b>Generated once.</b> A new lobby world gets the generated lobby for
 * {@code arenas.max-active} pads, and the world records it. Every later start keeps it, with
 * whatever admins changed in edit mode. It is generated again only when the pad count
 * changes, after {@code arenas.max-active} does, or when an admin runs {@code lobby reset},
 * and then only once nobody is in the lobby world: no members, no shop phase, no
 * spectators and no editors. Regenerating clears the old build and everything admins added
 * inside it.
 *
 * <p><b>Earlier servers.</b> A lobby world that has the obsidian build of earlier versions
 * keeps it, with its layout ({@link LobbyLayout#bundled()}), and the first start that finds
 * it logs a notice once. {@code lobby reset} replaces it with the generated lobby; nothing
 * else does.
 *
 * <p><b>Placement.</b> A build runs over ticks within a time budget ({@link LobbyPlacement}).
 * While it runs, the layout is already the new one, nobody can enter the lobby, and the
 * floor is laid first. A build the server stops in the middle is placed again on the next
 * start. Server thread only.
 */
public final class LobbyBuild {

    /** What the lobby world remembers, and its blocks. */
    public interface Site {

        /** The generated lobby recorded in this world, if any ({@link Saved#encode}). */
        Optional<String> saved();

        void save(String saved);

        /** Whether this world has the obsidian build of earlier versions. */
        boolean legacy();

        /** Forgets the obsidian build, once the generated lobby replaces it. */
        void dropLegacy();

        /** Whether the notice about the obsidian build has been logged before. */
        boolean noticed();

        void markNoticed();

        /** The seed the trees are picked with. */
        long seed();

        LobbyPlacement.Blocks blocks();
    }

    /**
     * The generated lobby recorded in the world.
     *
     * @param done false while it is being placed; a start that finds it so places it again
     */
    public record Saved(int pads, long seed, boolean done) {

        private static final String FORMAT = "1";

        public String encode() {
            return FORMAT + ":" + pads + ":" + seed + ":" + (done ? "done" : "building");
        }

        /** Empty for a value this version cannot read. */
        public static Optional<Saved> decode(String value) {
            String[] parts = value.split(":");
            if (parts.length != 4 || !parts[0].equals(FORMAT)) {
                return Optional.empty();
            }
            try {
                int pads = Integer.parseInt(parts[1]);
                if (pads < 1 || pads > GeneratedLobby.MAX_PADS) {
                    return Optional.empty();
                }
                return Optional.of(new Saved(pads, Long.parseLong(parts[2]), parts[3].equals("done")));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
    }

    /** Logged once by the first start that finds the obsidian build. */
    public static final String LEGACY_NOTICE = "The lobby world has the obsidian lobby of an earlier version. It stays"
            + " as it is. /ninjaarena admin lobby reset replaces it, and every change made in it, with the generated"
            + " lobby that has one room pad per arena slot (arenas.max-active, at most " + GeneratedLobby.MAX_PADS
            + "). This notice is not shown again.";

    /** Why the lobby is generated next. */
    private enum Request { NONE, CONFIG, ADMIN }

    private final LobbyPieces pieces;
    private final Site site;
    private final Logger logger;
    private final IntSupplier occupants;
    private final Runnable afterBuild;
    private LobbyLayout layout;
    private boolean generated;
    private int pads;
    private int wanted;
    private Request request = Request.NONE;
    private CommandSender requester;
    private LobbyPlacement placement;
    private CommandSender building;

    /**
     * @param pieces     the bundled pieces; null when they could not be read, so nothing is
     *                   generated
     * @param occupants  how many players are in the lobby world: members, shop phases,
     *                   spectators and editors
     * @param afterBuild runs once a build is placed: saves the world and puts the boards back
     */
    public LobbyBuild(LobbyPieces pieces, Site site, Logger logger, IntSupplier occupants, Runnable afterBuild) {
        this.pieces = pieces;
        this.site = Objects.requireNonNull(site, "site");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.occupants = Objects.requireNonNull(occupants, "occupants");
        this.afterBuild = Objects.requireNonNull(afterBuild, "afterBuild");
    }

    /**
     * Reads what the world has and picks the layout. Nothing is placed yet: {@link #poll()}
     * starts a build that is due once the lobby world is empty.
     *
     * @param maxActive {@code arenas.max-active}
     */
    public void start(int maxActive) {
        wanted = GeneratedLobby.padsFor(maxActive);
        Optional<Saved> saved = site.saved().flatMap(Saved::decode);
        if (saved.isPresent() && pieces != null) {
            Saved lobby = saved.get();
            useGenerated(lobby.pads(), lobby.seed());
            if (!lobby.done()) {
                logger.warning("The generated lobby was not finished when the server stopped; it is placed again"
                        + " once nobody is in the lobby world.");
                request = Request.CONFIG;
            } else if (lobby.pads() != wanted) {
                logger.info("arenas.max-active asks for " + wanted + " lobby pads and the lobby has " + lobby.pads()
                        + "; it is generated again once nobody is in the lobby world.");
                request = Request.CONFIG;
            }
            return;
        }
        if (site.legacy() || pieces == null) {
            layout = LobbyLayout.bundled();
            generated = false;
            if (pieces == null) {
                logger.severe("The generated lobby pieces could not be read, so the lobby stays as it is.");
            } else if (!site.noticed()) {
                logger.warning(LEGACY_NOTICE);
                site.markNoticed();
            }
            return;
        }
        useGenerated(wanted, site.seed());
        request = Request.CONFIG;
    }

    private void useGenerated(int count, long seed) {
        pads = count;
        layout = GeneratedLobby.plan(count, seed, pieces.trees().size()).layout();
        generated = true;
    }

    /** The layout in use: of the build standing there, or of the one being placed. */
    public LobbyLayout layout() {
        if (layout == null) {
            throw new IllegalStateException("start first");
        }
        return layout;
    }

    /** Whether the lobby is the generated one, standing or being placed. */
    public boolean generated() {
        return generated;
    }

    /** Whether a build is being placed; nobody may enter the lobby meanwhile. */
    public boolean building() {
        return placement != null;
    }

    /** Whether a build waits for the lobby world to empty. */
    public boolean pending() {
        return request != Request.NONE;
    }

    /**
     * A new {@code arenas.max-active}: a generated lobby follows its pad count once the lobby
     * world is empty. The obsidian build stays until {@code lobby reset}.
     */
    public void maxActive(int maxActive) {
        int next = GeneratedLobby.padsFor(maxActive);
        if (next == wanted) {
            return;
        }
        wanted = next;
        if (generated && next != pads && request == Request.NONE) {
            request = Request.CONFIG;
            logger.info("arenas.max-active now asks for " + next + " lobby pads; the lobby is generated again once"
                    + " nobody is in the lobby world.");
        }
    }

    /**
     * {@code lobby reset}: generates the lobby again, or replaces the obsidian build, once
     * the lobby world is empty. The sender is told when it starts and when it is done.
     */
    public void reset(CommandSender sender) {
        if (pieces == null) {
            sender.sendMessage(msg("lobby.reset-failed", "reason", "the generated lobby pieces could not be read"));
            return;
        }
        if (building()) {
            sender.sendMessage(msg("lobby.reset-busy"));
            return;
        }
        request = Request.ADMIN;
        requester = sender;
        if (!poll()) {
            sender.sendMessage(msg("lobby.reset-queued", "count", String.valueOf(occupants.getAsInt())));
        }
    }

    /**
     * Starts the build that is due, if the lobby world is empty. Called every second.
     *
     * @return whether a build started
     */
    public boolean poll() {
        if (request == Request.NONE || building() || occupants.getAsInt() > 0) {
            return false;
        }
        CommandSender sender = request == Request.ADMIN ? requester : null;
        request = Request.NONE;
        requester = null;
        begin(sender);
        return true;
    }

    private void begin(CommandSender sender) {
        long seed = site.seed();
        GeneratedLobby next = GeneratedLobby.plan(wanted, seed, pieces.trees().size());
        ArenaBounds build = next.layout().build();
        // The old build and everything admins added in its bounds go, with the new build's box.
        ArenaBounds before = layout.bounds();
        ArenaBounds sweep = new ArenaBounds(Math.min(before.minX(), build.minX()),
                Math.min(before.minY(), build.minY()), Math.min(before.minZ(), build.minZ()),
                Math.max(before.maxX(), build.maxX()), Math.max(before.maxY(), build.maxY()),
                Math.max(before.maxZ(), build.maxZ()));
        site.save(new Saved(wanted, seed, false).encode());
        if (site.legacy()) {
            site.dropLegacy();
        }
        placement = new LobbyPlacement(site.blocks(), next.blueprint(pieces), next.floor(), sweep);
        building = sender;
        layout = next.layout();
        pads = wanted;
        generated = true;
        logger.info("Generating the lobby for " + wanted + " pads; clearing " + sweep.describe() + " first.");
        if (sender != null) {
            sender.sendMessage(msg("lobby.reset-started", "pads", String.valueOf(wanted)));
        }
    }

    /**
     * Works the build in progress for up to this tick's budget.
     *
     * @param budgetNanos nanoseconds this tick may use
     * @param clock       nanoseconds, for measuring
     * @return whether a build finished this tick
     */
    public boolean tick(long budgetNanos, LongSupplier clock) {
        if (placement == null) {
            return false;
        }
        try {
            placement.tick(budgetNanos, clock);
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, "Could not place the generated lobby; the next start places it again.", e);
            if (building != null) {
                building.sendMessage(msg("lobby.reset-failed", "reason", String.valueOf(e.getMessage())));
            }
            placement = null;
            building = null;
            return false;
        }
        if (!placement.finished()) {
            return false;
        }
        site.save(new Saved(pads, site.seed(), true).encode());
        logger.info("Placed the generated lobby for " + pads + " pads at " + layout.build().describe() + ".");
        CommandSender told = building;
        placement = null;
        building = null;
        afterBuild.run();
        if (told != null) {
            told.sendMessage(msg("lobby.reset", "pads", String.valueOf(pads)));
        }
        if (wanted != pads && request == Request.NONE) {
            request = Request.CONFIG;
        }
        return true;
    }

    /** The lobby world itself, which keeps its records in its own data. */
    public static Site site(World world) {
        Objects.requireNonNull(world, "world");
        PersistentDataContainer data = world.getPersistentDataContainer();
        // Set by earlier versions once the obsidian build was placed.
        NamespacedKey legacy = new NamespacedKey("ninjaarena", "lobby_build");
        NamespacedKey generated = new NamespacedKey("ninjaarena", "lobby_generated");
        NamespacedKey noticed = new NamespacedKey("ninjaarena", "lobby_obsidian_notice");
        Map<String, BlockData> states = new HashMap<>();
        Map<String, Material> materials = new HashMap<>();
        LobbyPlacement.Blocks blocks = new LobbyPlacement.Blocks() {
            @Override
            public boolean is(int x, int y, int z, String state) {
                Material type = world.getType(x, y, z);
                if (state == null) {
                    return type.isAir();
                }
                // The pieces hold default block states, so the block type decides.
                return type == materials.computeIfAbsent(state, name -> data(name).getMaterial());
            }

            @Override
            public void set(int x, int y, int z, String state) {
                world.getBlockAt(x, y, z).setBlockData(state == null ? Material.AIR.createBlockData() : data(state),
                        false);
            }

            private BlockData data(String state) {
                return states.computeIfAbsent(state, Bukkit::createBlockData);
            }

            @Override
            public void removeEntities(ArenaBounds box) {
                BoundingBox area = new BoundingBox(box.minX(), box.minY(), box.minZ(), box.maxX() + 1,
                        box.maxY() + 1, box.maxZ() + 1);
                for (Entity entity : world.getNearbyEntities(area)) {
                    if (!(entity instanceof Player)) {
                        entity.remove();
                    }
                }
            }

            @Override
            public void spawn(LobbyPiece.Cushion cushion) {
                DyeColor color;
                try {
                    color = DyeColor.valueOf(cushion.color().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    color = DyeColor.WHITE;
                }
                DyeColor dye = color;
                world.spawn(new Location(world, cushion.x(), cushion.y(), cushion.z(), cushion.yaw(), 0),
                        Cushion.class, spawned -> spawned.setColor(dye));
            }
        };
        return new Site() {
            @Override
            public Optional<String> saved() {
                return Optional.ofNullable(data.get(generated, PersistentDataType.STRING));
            }

            @Override
            public void save(String saved) {
                data.set(generated, PersistentDataType.STRING, saved);
            }

            @Override
            public boolean legacy() {
                return data.has(legacy, PersistentDataType.BYTE);
            }

            @Override
            public void dropLegacy() {
                data.remove(legacy);
            }

            @Override
            public boolean noticed() {
                return data.has(noticed, PersistentDataType.BYTE);
            }

            @Override
            public void markNoticed() {
                data.set(noticed, PersistentDataType.BYTE, (byte) 1);
            }

            @Override
            public long seed() {
                return world.getSeed();
            }

            @Override
            public LobbyPlacement.Blocks blocks() {
                return blocks;
            }
        };
    }
}
