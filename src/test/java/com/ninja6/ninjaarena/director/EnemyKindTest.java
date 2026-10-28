package com.ninja6.ninjaarena.director;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Spider;
import org.bukkit.entity.Zombie;
import org.junit.jupiter.api.Test;

class EnemyKindTest {

    @Test
    void costsComeFromTheCostTable() {
        assertEquals(1, EnemyKind.ZOMBIE.cost(EnemyCosts.DEFAULTS));
        assertEquals(2, EnemyKind.SKELETON.cost(EnemyCosts.DEFAULTS));
        assertEquals(2, EnemyKind.SPIDER.cost(EnemyCosts.DEFAULTS));
        var costs = new EnemyCosts(3, 4, 5, 9);
        assertEquals(3, EnemyKind.ZOMBIE.cost(costs));
        assertEquals(4, EnemyKind.SKELETON.cost(costs));
        assertEquals(5, EnemyKind.SPIDER.cost(costs));
    }

    @Test
    void eachKindIsAVanillaMobWithItsHitbox() {
        assertEquals(Zombie.class, EnemyKind.ZOMBIE.type());
        assertEquals(Skeleton.class, EnemyKind.SKELETON.type());
        assertEquals(Spider.class, EnemyKind.SPIDER.type());
        for (EnemyKind kind : EnemyKind.values()) {
            assertTrue(kind.width() > 0 && kind.height() > 0, kind.name());
        }
        assertTrue(EnemyKind.SPIDER.width() > EnemyKind.ZOMBIE.width());
    }
}
