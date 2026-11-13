package com.ninja6.ninjaarena.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.config.ReloadFiles;
import com.ninja6.ninjaarena.map.SeedPolicy.Request;
import com.ninja6.ninjaarena.map.SeedPolicy.Source;
import com.ninja6.ninjaarena.room.SeedChoice;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** The seed policy, one test for each numbered rule of the seed modes and the timers. */
class SeedPolicyTest {

    private static final long MINUTE = 60_000;
    private static final long HOUR_MS = 60 * MINUTE;

    private long now = 1_000_000;
    /** Random seeds handed out in order; 1000, 1001, ... once these run out. */
    private final Deque<Long> randoms = new ArrayDeque<>();
    private long nextRandom = 1000;
    private final List<String> logged = new ArrayList<>();

    private static SeedSettings settings(String yaml) {
        return SeedSettings.from(YamlConfiguration.loadConfiguration(new StringReader(yaml))
                .getConfigurationSection("maps"));
    }

    private SeedPolicy policy(String yaml) {
        return new SeedPolicy(settings(yaml), () -> now, () -> randoms.isEmpty() ? nextRandom++ : randoms.poll(),
                logged::add);
    }

    private static SeedPolicy.Pick next(SeedPolicy policy, int slot, Long... active) {
        return policy.next(slot, Set.of(active), Request.NONE);
    }

    @Test
    void rule1TheDefaultModeIsRandomSoADefaultInstallGivesNoWarning() {
        SeedSettings bundled = SeedSettings.from(ReloadFiles.defaults().getConfigurationSection("maps"));
        assertEquals(SeedSettings.Mode.RANDOM, bundled.defaults().mode());
        SeedPolicy policy = new SeedPolicy(bundled, () -> now, () -> nextRandom++, logged::add);
        for (int slots = 1; slots <= 100; slots++) {
            assertEquals(List.of(), policy.warnings(slots));
        }
        assertEquals(Source.RANDOM, next(policy, 1).source());
        assertEquals(List.of(), logged);
    }

    @Test
    void rule2UnderDistinctAStaticSeedOnSeveralSlotsWarnsAtStartupAndLaterSlotsFallBackToRandom() {
        SeedPolicy policy = policy("""
                maps:
                  default: {mode: static, seed: 42}
                  slots:
                    4: {mode: static, seed: 7}
                """);
        assertEquals(List.of("Slots 1, 2 and 3 all use static seed 42, but maps.seed-sharing is distinct, so only slot"
                + " 1 plays it and slots 2 and 3 fall back to random seeds."), policy.warnings(4));
        assertEquals(List.of(), policy.warnings(1), "one slot alone is fine");
        assertEquals(new SeedPolicy.Pick(new SeedChoice(42, 2), Source.STATIC), next(policy, 1));
        // Later slots are random even while slot 1 is not active: the fallback is by slot.
        SeedPolicy.Pick second = next(policy, 2);
        assertEquals(Source.RANDOM, second.source());
        assertNotEquals(42, second.seed());
        assertEquals(Source.RANDOM, next(policy, 3, 42L, second.seed()).source());
        assertEquals(new SeedPolicy.Pick(new SeedChoice(7, 2), Source.STATIC), next(policy, 4, 42L));

        SeedPolicy shared = policy("""
                maps:
                  default: {mode: static, seed: 42}
                  seed-sharing: shared
                """);
        assertEquals(List.of(), shared.warnings(4), "shared slots may share a static seed");
        assertEquals(42, next(shared, 3, 42L).seed());
    }

    @Test
    void rule2AStaticSeedAnotherSlotPlaysNowGivesARandomSeedThisTime() {
        SeedPolicy policy = policy("maps: {slots: {2: {mode: static, seed: 9}}}");
        SeedPolicy.Pick pick = next(policy, 2, 9L);
        assertEquals(Source.RANDOM, pick.source());
        assertEquals(1000, pick.seed());
        assertEquals(List.of("Slot 2 uses static seed 9, which another slot plays now, so it gets random seed 1000"
                + " this time."), logged);
    }

    @Test
    void rule3UnderDistinctSlotsBeyondTheRotationsSeedsGetARandomSeedAndItIsLogged() {
        SeedPolicy policy = policy("maps: {default: {mode: rotation, seeds: [11, 22]}}");
        Set<Long> active = new HashSet<>();
        SeedPolicy.Pick first = policy.next(1, Set.copyOf(active), Request.NONE);
        assertEquals(new SeedPolicy.Pick(new SeedChoice(11, 2), Source.ROTATION), first);
        active.add(first.seed());
        SeedPolicy.Pick second = policy.next(2, Set.copyOf(active), Request.NONE);
        assertEquals(new SeedPolicy.Pick(new SeedChoice(22, 2), Source.ROTATION), second, "slot 2 starts on seed 2");
        active.add(second.seed());
        assertEquals(List.of(), logged);
        SeedPolicy.Pick third = policy.next(3, Set.copyOf(active), Request.NONE);
        assertEquals(Source.RANDOM, third.source());
        assertFalse(active.contains(third.seed()));
        assertEquals(List.of("Slot 3 rotates through 2 seed(s), but other active slots play all of them, so it gets"
                + " random seed 1000 this time."), logged);
        // Once a seed is free again, the slot is back on the rotation.
        assertEquals(Source.ROTATION, next(policy, 3, 22L).source());
        assertEquals(11, next(policy, 3, 22L).seed());
    }

    @Test
    void rule4UnderSharedPerSlotOverridesAreIgnoredWithAWarning() {
        SeedPolicy policy = policy("""
                maps:
                  default: {mode: rotation, seeds: [5, 6, 7], rotate-every: 1h}
                  slots:
                    2: {mode: static, seed: 99, difficulty: 5}
                    4: {difficulty: 1}
                  seed-sharing: shared
                """);
        assertEquals(List.of("maps.slots sets 2, 4, but maps.seed-sharing is shared, so every slot follows"
                + " maps.default and the overrides are ignored."), policy.warnings(4));
        SeedPolicy.Pick slot2 = next(policy, 2);
        assertEquals(new SeedPolicy.Pick(new SeedChoice(5, 2), Source.ROTATION), slot2, "not static 99");
        // Every slot follows one rotation, and may play what another plays.
        assertEquals(5, next(policy, 4, 5L).seed());
        assertEquals(5, next(policy, 1, 5L).seed());
    }

    @Test
    void rule4UnderSharedWithZeroTimersActiveSlotsPlayOneRandomSeed() {
        SeedPolicy policy = policy("maps: {seed-sharing: shared}");
        long first = next(policy, 1).seed();
        assertEquals(first, next(policy, 2, first).seed(), "slot 2 joins the seed slot 1 plays");
        assertEquals(first, next(policy, 3, first).seed());
        // Slot 1 generates again while slots 2 and 3 still play it: it stays with them.
        assertEquals(first, next(policy, 1, first).seed());
        assertEquals(List.of(), logged);
    }

    @Test
    void rule4UnderSharedWithZeroTimersActiveSlotsPlayOneRotationSeed() {
        SeedPolicy policy = policy("maps: {seed-sharing: shared, default: {mode: rotation, seeds: [1, 2, 3]}}");
        assertEquals(1, next(policy, 1).seed());
        assertEquals(1, next(policy, 2, 1L).seed());
        assertEquals(1, next(policy, 1, 1L).seed(), "still played by slot 2");
        // Once no other slot plays it, the next generation moves the rotation on, for everyone.
        assertEquals(2, next(policy, 2).seed());
        assertEquals(2, next(policy, 1, 2L).seed());
    }

    @Test
    void rule4UnderSharedALoneSlotMovesOnAndARequestMovesEverySlotOn() {
        SeedPolicy rotation = policy("maps: {seed-sharing: shared, default: {mode: rotation, seeds: [1, 2, 3]}}");
        assertEquals(List.of(1L, 2L, 3L, 1L), List.of(next(rotation, 1).seed(), next(rotation, 1).seed(),
                next(rotation, 1).seed(), next(rotation, 1).seed()), "a slot on its own gets the next one each time");
        assertEquals(2, rotation.next(2, Set.of(1L), Request.NEXT).seed(), "map next moves on though slot 1 plays 1");
        assertEquals(2, next(rotation, 1, 2L).seed(), "and slot 1 follows at its next generation");
        assertEquals(new SeedPolicy.Pick(new SeedChoice(3, 2), Source.ROTATION),
                rotation.next(2, Set.of(2L), Request.REROLL), "a reroll moves a shared rotation on for all");
        assertEquals(3, next(rotation, 1, 3L).seed());

        SeedPolicy random = policy("maps: {seed-sharing: shared}");
        long first = next(random, 1).seed();
        assertNotEquals(first, next(random, 1).seed(), "a lone random slot gets a new seed each time");
        long held = next(random, 2).seed();
        long rolled = random.next(1, Set.of(held), Request.REROLL).seed();
        assertNotEquals(held, rolled, "map reroll picks a new shared seed");
        assertEquals(rolled, next(random, 2, rolled).seed());

        SeedPolicy timed = policy("maps: {seed-sharing: shared, default: {reroll-every: 1h}}");
        long kept = next(timed, 1).seed();
        assertEquals(kept, next(timed, 2, kept).seed());
        now += HOUR_MS;
        assertNotEquals(kept, next(timed, 2, kept).seed(), "with a timer, it moves on once due, as before");
    }

    @Test
    void rule5RotateEveryAndRerollEveryTakeEffectOnlyAtTheSlotsNextGeneration() {
        SeedPolicy rotation = policy("maps: {default: {mode: rotation, seeds: [1, 2, 3], rotate-every: 30m}}");
        assertEquals(1, next(rotation, 1).seed());
        now += 29 * MINUTE;
        assertEquals(1, next(rotation, 1).seed(), "before the timer runs out");
        now += 2 * MINUTE;
        // The timer ran out: nothing happens until the slot generates its next map.
        assertEquals(Optional.of(new SeedChoice(2, 2)), rotation.preview(1, Set.of()));
        now += 120 * MINUTE;
        assertEquals(Optional.of(new SeedChoice(2, 2)), rotation.preview(1, Set.of()), "one step, not four");
        assertEquals(2, next(rotation, 1).seed(), "the next generation moves on by one");
        assertEquals(2, next(rotation, 1).seed(), "and the timer starts again");

        SeedPolicy random = policy("maps: {default: {reroll-every: 2h}}");
        long first = next(random, 1).seed();
        now += 119 * MINUTE;
        assertEquals(first, next(random, 1).seed());
        assertEquals(Optional.of(new SeedChoice(first, 2)), random.preview(1, Set.of()));
        now += MINUTE;
        assertEquals(Optional.empty(), random.preview(1, Set.of()), "a new seed is due");
        assertNotEquals(first, next(random, 1).seed());

        SeedPolicy everyTime = policy("maps: {default: {mode: rotation, seeds: [1, 2]}}");
        assertEquals(List.of(1L, 2L, 1L), List.of(next(everyTime, 1).seed(), next(everyTime, 1).seed(),
                next(everyTime, 1).seed()), "0 moves on at every generation");
    }

    @Test
    void rule5ANextOrRerollRequestWaitsForTheNextGeneration() {
        SeedPolicy policy = policy("maps: {default: {mode: rotation, seeds: [1, 2, 3], rotate-every: 1d}}");
        assertEquals(1, next(policy, 1).seed());
        policy.request(1, Request.NEXT);
        assertEquals(Optional.of(new SeedChoice(2, 2)), policy.preview(1, Set.of()));
        assertEquals(2, next(policy, 1).seed());
        assertEquals(2, next(policy, 1).seed(), "used up");
        assertEquals(3, policy.next(1, Set.of(), Request.NEXT).seed());
        SeedPolicy.Pick rerolled = policy.next(1, Set.of(), Request.REROLL);
        assertEquals(Source.RANDOM, rerolled.source());
        assertEquals(3, next(policy, 1).seed(), "a reroll is one map; the rotation stays where it was");

        SeedPolicy random = policy("maps: {default: {reroll-every: 1d}}");
        long kept = next(random, 1).seed();
        assertEquals(kept, next(random, 1).seed());
        random.request(1, Request.REROLL);
        long rolled = next(random, 1).seed();
        assertNotEquals(kept, rolled);
        assertEquals(rolled, next(random, 1).seed(), "the new seed is kept");
        assertNotEquals(rolled, random.next(1, Set.of(), Request.NEXT).seed(), "next rerolls a random slot");
    }

    @Test
    void rule7UnderDistinctARandomSeedNeverCollidesWithAnActiveSlot() {
        SeedPolicy policy = policy("maps: {}");
        randoms.addAll(List.of(5L, 6L, 5L, 7L));
        assertEquals(7, next(policy, 1, 5L, 6L).seed(), "seeds other slots play are skipped");
        // The random seed a slot keeps is given up when another slot has it now.
        SeedPolicy kept = policy("maps: {default: {reroll-every: 1d}}");
        long seed = next(kept, 1).seed();
        assertEquals(seed, next(kept, 1).seed());
        assertNotEquals(seed, next(kept, 1, seed).seed());
        assertTrue(kept.collides(seed, Set.of(seed)));
        assertFalse(policy("maps: {seed-sharing: shared}").collides(seed, Set.of(seed)));
    }

    @Test
    void reloadKeepsARotationsPlaceWhileItsSeedsStayTheSame() {
        SeedPolicy policy = policy("maps: {default: {mode: rotation, seeds: [1, 2, 3]}}");
        assertEquals(1, next(policy, 1).seed());
        assertEquals(2, next(policy, 1).seed());
        policy.setSettings(settings("maps: {default: {mode: rotation, seeds: [1, 2, 3], difficulty: 5}}"));
        assertEquals(new SeedChoice(3, 5), next(policy, 1).choice());
        policy.setSettings(settings("maps: {default: {mode: rotation, seeds: [8, 9]}}"));
        assertEquals(8, next(policy, 1).seed(), "a new list starts again");
    }
}
