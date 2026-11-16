package com.ninja6.ninjaarena.lobby;

import static com.ninja6.ninjaarena.message.Messages.msg;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.List;
import java.util.function.BooleanSupplier;
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
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;

/**
 * The lobby build in the lobby world: which one stands there, and when the generated lobby
 * is placed ({@link GeneratedLobby}).
 *
 * <p><b>Generated once.</b> A new lobby world gets the generated lobby for
 * {@code arenas.max-active} pads, and the world records it. Every later start keeps it, with
 * whatever admins changed in edit mode. It is generated again only when the pad count
 * changes, after {@code arenas.max-active} does, or when an admin runs {@code lobby reset}.
 *
 * <p><b>When.</b> A build starts only while nobody is in the lobby world (members, shop
 * phases, spectators, editors) and nothing can bring anyone there: no match is running
 * and no room has a player away from the lobby, such as one previewing a map. At enable no
 * player can be there yet, so a new lobby or a build the server stopped in the middle starts
 * before anyone joins. While a build runs, nobody may enter the lobby or edit it
 * ({@link #building()}); players the plugin would send there go home instead. A queued
 * {@code lobby reset} is kept in the world, so a restart runs it too.
 *
 * <p><b>Earlier servers.</b> A lobby world that has the obsidian build of earlier versions
 * keeps it, with its layout ({@link LobbyLayout#bundled()}), and the first start that finds
 * it logs a notice once. {@code lobby reset} replaces it with the generated lobby; nothing
 * else does.
 *
 * <p><b>Placement.</b> A build runs over ticks within the placement budget
 * ({@link LobbyPlacement}), sweeping the old bounds and the new build. The swept box is
 * recorded with the build, so a build the server stops in the middle sweeps the same box
 * when it is placed again. Server thread only.
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

        /** Whether an admin's {@code lobby reset} waits to run. */
        boolean resetQueued();

        void queueReset(boolean queued);

        /** The seed the trees are picked with. */
        long seed();

        LobbyPlacement.Blocks blocks();
    }

    /**
     * The generated lobby recorded in the world.
     *
     * @param done  false while it is being placed; a start that finds it so places it again
     * @param sweep the box a build in progress clears; null once done
     */
    public record Saved(int pads, long seed, boolean done, ArenaBounds sweep) {

        private static final String FORMAT = "2";

        public Saved(int pads, long seed, boolean done) {
            this(pads, seed, done, null);
        }

        public String encode() {
            String text = FORMAT + ":" + pads + ":" + seed + ":" + (done ? "done" : "building");
            if (sweep != null) {
                text += ":" + sweep.minX() + "," + sweep.minY() + "," + sweep.minZ() + "," + sweep.maxX() + ","
                        + sweep.maxY() + "," + sweep.maxZ();
            }
            return text;
        }

        /** Empty for a value this version cannot read. */
        public static Optional<Saved> decode(String value) {
            String[] parts = value.split(":");
            if (parts.length < 4 || parts.length > 5 || !parts[0].equals(FORMAT)) {
                return Optional.empty();
            }
            try {
                int pads = Integer.parseInt(parts[1]);
                if (pads < 1 || pads > GeneratedLobby.MAX_PADS) {
                    return Optional.empty();
                }
                ArenaBounds sweep = null;
                if (parts.length == 5) {
                    String[] box = parts[4].split(",");
                    if (box.length != 6) {
                        return Optional.empty();
                    }
                    int[] at = new int[6];
                    for (int i = 0; i < 6; i++) {
                        at[i] = Integer.parseInt(box[i]);
                    }
                    sweep = new ArenaBounds(at[0], at[1], at[2], at[3], at[4], at[5]);
                }
                return Optional.of(new Saved(pads, Long.parseLong(parts[2]), parts[3].equals("done"), sweep));
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
    private final BooleanSupplier held;
    private final Runnable afterBuild;
    private LobbyLayout layout;
    private boolean generated;
    private int pads;
    private int wanted;
    private Request request = Request.NONE;
    /** The admins waiting for a queued reset, told when it starts and when it is done. */
    private final List<CommandSender> requesters = new ArrayList<>();
    /** The box an unfinished build was clearing before the server stopped. */
    private ArenaBounds resumeSweep;
    private LobbyPlacement placement;
    private ArenaBounds sweeping;
    private List<CommandSender> building = List.of();

    /**
     * @param pieces     the bundled pieces; null when they could not be read, so nothing is
     *                   generated
     * @param occupants  how many players are in the lobby world: members, shop phases,
     *                   spectators and editors
     * @param held       whether anything could bring players to the lobby world meanwhile:
     *                   a running match, or a room member away from the lobby
     * @param afterBuild runs once a build is placed: saves the world and puts the boards back
     */
    public LobbyBuild(LobbyPieces pieces, Site site, Logger logger, IntSupplier occupants, BooleanSupplier held,
            Runnable afterBuild) {
        this.pieces = pieces;
        this.site = Objects.requireNonNull(site, "site");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.occupants = Objects.requireNonNull(occupants, "occupants");
        this.held = Objects.requireNonNull(held, "held");
        this.afterBuild = Objects.requireNonNull(afterBuild, "afterBuild");
    }

    /**
     * Reads what the world has and picks the layout. Nothing is placed yet: {@link #poll()}
     * starts a build that is due. Call it once at enable, before any player can join, so a
     * new lobby or an unfinished build starts at once.
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
                        + " before anyone can enter the lobby.");
                resumeSweep = lobby.sweep();
                request = Request.CONFIG;
            } else if (lobby.pads() != wanted) {
                logger.info("arenas.max-active asks for " + wanted + " lobby pads and the lobby has " + lobby.pads()
                        + "; it is generated again once nobody is in the lobby world and no match is running.");
                request = Request.CONFIG;
            }
        } else if (site.legacy() || pieces == null) {
            layout = LobbyLayout.bundled();
            generated = false;
            if (pieces == null) {
                logger.severe("The generated lobby pieces could not be read, so the lobby stays as it is.");
            } else if (!site.noticed()) {
                logger.warning(LEGACY_NOTICE);
                site.markNoticed();
            }
        } else {
            useGenerated(wanted, site.seed());
            request = Request.CONFIG;
        }
        if (site.resetQueued() && pieces != null) {
            logger.info("A lobby reset queued before the restart runs once nobody is in the lobby world and no match"
                    + " is running.");
            request = Request.ADMIN;
        }
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

    /** Whether a build is being placed; nobody may enter or edit the lobby meanwhile. */
    public boolean building() {
        return placement != null;
    }

    /** Whether a build waits to start. */
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
                    + " nobody is in the lobby world and no match is running.");
        }
    }

    /**
     * {@code lobby reset}: generates the lobby again, or replaces the obsidian build, once
     * the lobby world is empty and no match is running. Every admin who asked is told when
     * it starts and when it is done. A queued reset is kept across a restart.
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
        // Told once each; a sender is compared by identity, as a player object is one per login.
        if (requesters.stream().noneMatch(queued -> queued == sender)) {
            requesters.add(sender);
        }
        site.queueReset(true);
        if (!poll()) {
            sender.sendMessage(msg("lobby.reset-queued", "count", String.valueOf(occupants.getAsInt())));
        }
    }

    /**
     * Starts the build that is due, if the lobby world is empty and nothing can bring players
     * there. Called at enable and then every second.
     *
     * @return whether a build started
     */
    public boolean poll() {
        if (request == Request.NONE || building() || occupants.getAsInt() > 0 || held.getAsBoolean()) {
            return false;
        }
        List<CommandSender> told = List.copyOf(requesters);
        request = Request.NONE;
        requesters.clear();
        site.queueReset(false);
        begin(told);
        return true;
    }

    private void begin(List<CommandSender> told) {
        long seed = site.seed();
        GeneratedLobby next = GeneratedLobby.plan(wanted, seed, pieces.trees().size());
        // The old build and everything admins added in its bounds go, with the new build's
        // box and whatever an unfinished build was clearing.
        ArenaBounds sweep = hull(layout.bounds(), next.layout().build());
        if (resumeSweep != null) {
            sweep = hull(sweep, resumeSweep);
            resumeSweep = null;
        }
        site.save(new Saved(wanted, seed, false, sweep).encode());
        if (site.legacy()) {
            site.dropLegacy();
        }
        LobbyPlacement.Blocks blocks = site.blocks();
        blocks.hold(sweep);
        placement = new LobbyPlacement(blocks, next.blueprint(pieces), next.floor(), sweep);
        sweeping = sweep;
        building = told;
        layout = next.layout();
        pads = wanted;
        generated = true;
        logger.info("Generating the lobby for " + wanted + " pads; clearing " + sweep.describe() + " first.");
        for (CommandSender sender : told) {
            sender.sendMessage(msg("lobby.reset-started", "pads", String.valueOf(wanted)));
        }
    }

    private static ArenaBounds hull(ArenaBounds a, ArenaBounds b) {
        return new ArenaBounds(Math.min(a.minX(), b.minX()), Math.min(a.minY(), b.minY()),
                Math.min(a.minZ(), b.minZ()), Math.max(a.maxX(), b.maxX()), Math.max(a.maxY(), b.maxY()),
                Math.max(a.maxZ(), b.maxZ()));
    }

    /**
     * Works the build in progress for up to this tick's share of the placement budget.
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
            for (CommandSender sender : building) {
                sender.sendMessage(msg("lobby.reset-failed", "reason", String.valueOf(e.getMessage())));
            }
            site.blocks().release(sweeping);
            placement = null;
            building = List.of();
            return false;
        }
        if (!placement.finished()) {
            return false;
        }
        site.save(new Saved(pads, site.seed(), true).encode());
        site.blocks().release(sweeping);
        logger.info("Placed the generated lobby for " + pads + " pads at " + layout.build().describe() + ".");
        List<CommandSender> told = building;
        placement = null;
        building = List.of();
        afterBuild.run();
        for (CommandSender sender : told) {
            sender.sendMessage(msg("lobby.reset", "pads", String.valueOf(pads)));
        }
        if (wanted != pads && request == Request.NONE) {
            request = Request.CONFIG;
        }
        return true;
    }

    /** Logs a queued reset or an unfinished build on disable; both run on the next start. */
    public void close() {
        if (building()) {
            logger.info("The generated lobby is not finished; the next start places it again.");
        } else if (request == Request.ADMIN) {
            logger.info("A lobby reset is still queued; the next start runs it once the lobby world is empty.");
        }
    }

    /**
     * The lobby world itself, which keeps its records in its own data.
     *
     * @param plugin holds the chunk tickets of a build in progress
     */
    public static Site site(World world, Plugin plugin) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(plugin, "plugin");
        PersistentDataContainer data = world.getPersistentDataContainer();
        // Set by earlier versions once the obsidian build was placed.
        NamespacedKey legacy = new NamespacedKey("ninjaarena", "lobby_build");
        NamespacedKey generated = new NamespacedKey("ninjaarena", "lobby_generated");
        NamespacedKey noticed = new NamespacedKey("ninjaarena", "lobby_obsidian_notice");
        NamespacedKey queued = new NamespacedKey("ninjaarena", "lobby_reset_queued");
        // Chunks the build added a ticket to, so tickets held for anything else stay.
        java.util.List<long[]> tickets = new java.util.ArrayList<>();
        Map<String, BlockData> states = new HashMap<>();
        Map<String, Material> materials = new HashMap<>();
        LobbyPlacement.Blocks blocks = new LobbyPlacement.Blocks() {
            @Override
            public void hold(ArenaBounds box) {
                for (int x = box.minChunkX(); x <= box.maxChunkX(); x++) {
                    for (int z = box.minChunkZ(); z <= box.maxChunkZ(); z++) {
                        if (world.addPluginChunkTicket(x, z, plugin)) {
                            tickets.add(new long[] {x, z});
                        }
                    }
                }
            }

            @Override
            public void release(ArenaBounds box) {
                for (long[] chunk : tickets) {
                    world.removePluginChunkTicket((int) chunk[0], (int) chunk[1], plugin);
                }
                tickets.clear();
            }

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
            public boolean resetQueued() {
                return data.has(queued, PersistentDataType.BYTE);
            }

            @Override
            public void queueReset(boolean value) {
                if (value) {
                    data.set(queued, PersistentDataType.BYTE, (byte) 1);
                } else {
                    data.remove(queued);
                }
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
