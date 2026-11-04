package com.ninja6.ninjaarena.director;

import java.util.Locale;
import java.util.Objects;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Spider;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.entity.Zombie;

/**
 * The enemies the director spawns, with the vanilla mob each is and its hitbox at scale 1 for
 * spawn placement. The Shadow Stalker is a wither skeleton readied by {@link ShadowStalker}.
 */
public enum EnemyKind {

    ZOMBIE(Zombie.class, 0.6, 1.95),
    SKELETON(Skeleton.class, 0.6, 1.99),
    SPIDER(Spider.class, 1.4, 0.9),
    SHADOW_STALKER(WitherSkeleton.class, 0.7, 2.4);

    private final Class<? extends Mob> type;
    private final double width;
    private final double height;

    EnemyKind(Class<? extends Mob> type, double width, double height) {
        this.type = type;
        this.width = width;
        this.height = height;
    }

    public Class<? extends Mob> type() {
        return type;
    }

    public double width() {
        return width;
    }

    public double height() {
        return height;
    }

    /** How the kind reads in the log and the director status, such as {@code shadow stalker}. */
    public String label() {
        return name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    /** Threat points from the cost table. */
    public int cost(EnemyCosts costs) {
        Objects.requireNonNull(costs, "costs");
        return switch (this) {
            case ZOMBIE -> costs.zombie();
            case SKELETON -> costs.skeleton();
            case SPIDER -> costs.spider();
            case SHADOW_STALKER -> costs.shadowStalker();
        };
    }

    /** How many of the kind may be alive at once in one match. */
    public int maxAlive(StalkerSettings stalker) {
        Objects.requireNonNull(stalker, "stalker");
        return this == SHADOW_STALKER ? stalker.maxAlive() : Integer.MAX_VALUE;
    }

    /** Whether enemies of the kind go only for the leading team while one leads. */
    public boolean huntsLeaders() {
        return this == SHADOW_STALKER;
    }
}
