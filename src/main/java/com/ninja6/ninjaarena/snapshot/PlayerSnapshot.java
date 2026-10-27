package com.ninja6.ninjaarena.snapshot;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.GameMode;

/**
 * Everything needed to put a player back the way they were before arena entry.
 *
 * <p>Item stacks are held as the bytes produced by
 * {@link org.bukkit.inventory.ItemStack#serializeItemsAsBytes(org.bukkit.inventory.ItemStack[])},
 * which carry the data version and survive server upgrades. Keeping them as bytes leaves
 * the model and its file format independent of a running server.
 *
 * @param storage   the 36 main inventory slots, hotbar included
 * @param armour    boots, leggings, chestplate, helmet
 * @param offhand   a single-element array holding the offhand item
 * @param exp       progress towards the next level, from 0 to 1
 */
public record PlayerSnapshot(
        UUID playerId,
        String playerName,
        Instant capturedAt,
        SnapshotLocation location,
        byte[] storage,
        byte[] armour,
        byte[] offhand,
        int level,
        float exp,
        int totalExperience,
        double health,
        int foodLevel,
        float saturation,
        float exhaustion,
        List<SnapshotEffect> effects,
        GameMode gameMode,
        int fireTicks,
        float fallDistance) {

    public PlayerSnapshot {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(playerName, "playerName");
        Objects.requireNonNull(capturedAt, "capturedAt");
        Objects.requireNonNull(location, "location");
        storage = Objects.requireNonNull(storage, "storage").clone();
        armour = Objects.requireNonNull(armour, "armour").clone();
        offhand = Objects.requireNonNull(offhand, "offhand").clone();
        effects = List.copyOf(Objects.requireNonNull(effects, "effects"));
        Objects.requireNonNull(gameMode, "gameMode");
    }

    @Override
    public byte[] storage() {
        return storage.clone();
    }

    @Override
    public byte[] armour() {
        return armour.clone();
    }

    @Override
    public byte[] offhand() {
        return offhand.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PlayerSnapshot that
                && playerId.equals(that.playerId)
                && playerName.equals(that.playerName)
                && capturedAt.equals(that.capturedAt)
                && location.equals(that.location)
                && Arrays.equals(storage, that.storage)
                && Arrays.equals(armour, that.armour)
                && Arrays.equals(offhand, that.offhand)
                && level == that.level
                && Float.compare(exp, that.exp) == 0
                && totalExperience == that.totalExperience
                && Double.compare(health, that.health) == 0
                && foodLevel == that.foodLevel
                && Float.compare(saturation, that.saturation) == 0
                && Float.compare(exhaustion, that.exhaustion) == 0
                && effects.equals(that.effects)
                && gameMode == that.gameMode
                && fireTicks == that.fireTicks
                && Float.compare(fallDistance, that.fallDistance) == 0;
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(playerId, playerName, capturedAt, location, level, exp,
                totalExperience, health, foodLevel, saturation, exhaustion, effects, gameMode,
                fireTicks, fallDistance);
        result = 31 * result + Arrays.hashCode(storage);
        result = 31 * result + Arrays.hashCode(armour);
        return 31 * result + Arrays.hashCode(offhand);
    }

    @Override
    public String toString() {
        return "PlayerSnapshot[playerId=" + playerId + ", playerName=" + playerName
                + ", capturedAt=" + capturedAt + ", location=" + location + "]";
    }
}
