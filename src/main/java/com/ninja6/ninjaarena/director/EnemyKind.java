package com.ninja6.ninjaarena.director;

import java.util.Objects;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Spider;
import org.bukkit.entity.Zombie;

/**
 * The enemies the director spawns, with the vanilla mob each is and its hitbox for spawn
 * placement. The Shadow Stalker has a cost in {@link EnemyCosts} but is not spawned until its
 * mob exists.
 */
public enum EnemyKind {

    ZOMBIE(Zombie.class, 0.6, 1.95),
    SKELETON(Skeleton.class, 0.6, 1.99),
    SPIDER(Spider.class, 1.4, 0.9);

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

    /** Threat points from the cost table. */
    public int cost(EnemyCosts costs) {
        Objects.requireNonNull(costs, "costs");
        return switch (this) {
            case ZOMBIE -> costs.zombie();
            case SKELETON -> costs.skeleton();
            case SPIDER -> costs.spider();
        };
    }
}
