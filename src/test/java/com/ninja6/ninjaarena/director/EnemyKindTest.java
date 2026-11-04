package com.ninja6.ninjaarena.director;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Spider;
import org.bukkit.entity.WitherSkeleton;
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
        assertEquals(8, EnemyKind.SHADOW_STALKER.cost(EnemyCosts.DEFAULTS));
        assertEquals(9, EnemyKind.SHADOW_STALKER.cost(costs));
    }

    @Test
    void eachKindIsAVanillaMobWithItsHitbox() {
        assertEquals(Zombie.class, EnemyKind.ZOMBIE.type());
        assertEquals(Skeleton.class, EnemyKind.SKELETON.type());
        assertEquals(Spider.class, EnemyKind.SPIDER.type());
        assertEquals(WitherSkeleton.class, EnemyKind.SHADOW_STALKER.type());
        for (EnemyKind kind : EnemyKind.values()) {
            assertTrue(kind.width() > 0 && kind.height() > 0, kind.name());
        }
        assertTrue(EnemyKind.SPIDER.width() > EnemyKind.ZOMBIE.width());
        assertTrue(EnemyKind.SHADOW_STALKER.height() > EnemyKind.SKELETON.height());
    }

    @Test
    void onlyTheShadowStalkerIsCappedAndHuntsTheLeaders() {
        var stalker = new StalkerSettings(3, 40, 6, 0.3, 1.1, 8, 20, 8, 24, 2, 3);
        for (EnemyKind kind : EnemyKind.values()) {
            boolean signature = kind == EnemyKind.SHADOW_STALKER;
            assertEquals(signature ? 3 : Integer.MAX_VALUE, kind.maxAlive(stalker), kind.name());
            assertEquals(signature, kind.huntsLeaders(), kind.name());
        }
    }

    @Test
    void labelsReadAsWords() {
        assertEquals("zombie", EnemyKind.ZOMBIE.label());
        assertEquals("shadow stalker", EnemyKind.SHADOW_STALKER.label());
    }
}
