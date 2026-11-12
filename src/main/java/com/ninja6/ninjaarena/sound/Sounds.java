package com.ninja6.ninjaarena.sound;

import com.ninja6.ninjaarena.match.MatchView;
import com.ninja6.ninjaarena.room.Room;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

/** Live, personal sound cues. Call only on the server thread. */
public final class Sounds {
    public enum Cue {
        ROOM_JOIN("block.note_block.pling", 0.3f, 1.2f),
        READY("block.note_block.hat", 0.3f, 1.2f),
        MATCH_START("block.note_block.chime", 0.4f, 1f),
        SHOP_BUY("entity.experience_orb.pickup", 0.3f, 1.2f),
        COOLDOWN_READY("block.note_block.hat", 0.2f, 1.5f),
        ENEMY_SPAWN("entity.enderman.teleport", 0.35f, 1.2f),
        ROUND_END("entity.player.levelup", 0.6f, 1f),
        HAZARD_WARNING("block.note_block.bell", 0.4f, 0.8f);

        private final Sound fallback;

        Cue(String key, float volume, float pitch) {
            fallback = Sound.sound(Key.key(key), name().equals("ENEMY_SPAWN")
                    ? Sound.Source.HOSTILE : Sound.Source.MASTER, volume, pitch);
        }

        public String key() {
            return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        }

        public Sound defaultSound() {
            return fallback;
        }
    }

    private final Function<UUID, Player> players;
    private final Function<String, ? extends Collection<UUID>> spectators;
    private final Map<Cue, Sound> settings = new EnumMap<>(Cue.class);

    public Sounds(Function<UUID, Player> players) {
        this(players, arena -> java.util.List.of());
    }

    public Sounds(Function<UUID, Player> players, Function<String, ? extends Collection<UUID>> spectators) {
        this.players = players;
        this.spectators = spectators;
        for (Cue cue : Cue.values()) {
            settings.put(cue, cue.fallback);
        }
    }

    /** Invalid fields warn once per load and fall back independently to their bundled value. */
    public void load(ConfigurationSection config, Consumer<String> warning) {
        for (Cue cue : Cue.values()) {
            String path = "sounds." + cue.key() + ".";
            Object enabled = config.get(path + "enabled", true);
            if (!(enabled instanceof Boolean)) {
                warning.accept(path + "enabled: expected a boolean; using true");
                enabled = true;
            }
            String key = cue.fallback.name().asString();
            Object raw = config.get(path + "sound", key);
            if (raw instanceof String text && known(text)) {
                key = text;
            } else {
                warning.accept(path + "sound: unknown vanilla sound; using " + key);
            }
            float volume = number(config, path + "volume", cue.fallback.volume(), 0, 1, warning);
            float pitch = number(config, path + "pitch", cue.fallback.pitch(), 0.5f, 2, warning);
            settings.put(cue, Boolean.FALSE.equals(enabled) ? null
                    : Sound.sound(Key.key(key), cue.fallback.source(), volume, pitch));
        }
    }

    private static boolean known(String key) {
        org.bukkit.NamespacedKey parsed = org.bukkit.NamespacedKey.fromString(key);
        return parsed != null && parsed.getNamespace().equals("minecraft")
                && io.papermc.paper.registry.RegistryAccess.registryAccess()
                        .getRegistry(io.papermc.paper.registry.RegistryKey.SOUND_EVENT).get(parsed) != null;
    }

    private static float number(ConfigurationSection config, String path, float fallback, float min, float max,
            Consumer<String> warning) {
        Object value = config.get(path, fallback);
        if (value instanceof Number number && Double.isFinite(number.doubleValue())
                && number.doubleValue() >= min && number.doubleValue() <= max) {
            return number.floatValue();
        }
        warning.accept(path + ": expected " + min + " to " + max + "; using " + fallback);
        return fallback;
    }

    public void player(Cue cue, Player player) {
        Sound sound = settings.get(cue);
        if (sound != null && player != null && player.isOnline()) {
            player.playSound(sound);
        }
    }

    public void room(Cue cue, Room room) {
        room.members().forEach(id -> player(cue, players.apply(id)));
    }

    public void match(Cue cue, MatchView match) {
        var audience = new LinkedHashSet<>(match.players());
        audience.addAll(spectators.apply(match.arena()));
        audience.forEach(id -> player(cue, players.apply(id)));
    }

    /** Entry point for arena hazards when those features are installed. */
    public void hazardWarning(MatchView match) {
        match(Cue.HAZARD_WARNING, match);
    }
}
