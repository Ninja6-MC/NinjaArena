package com.ninja6.ninjaarena.director;

import org.bukkit.configuration.ConfigurationSection;

/** Positive threat points per enemy, from the {@code director.enemy-costs} config section. */
public record EnemyCosts(int zombie, int skeleton, int spider, int shadowStalker) {

    public static final EnemyCosts DEFAULTS = new EnemyCosts(1, 2, 2, 8);

    public EnemyCosts {
        zombie = Math.max(1, zombie);
        skeleton = Math.max(1, skeleton);
        spider = Math.max(1, spider);
        shadowStalker = Math.max(1, shadowStalker);
    }

    public static EnemyCosts from(ConfigurationSection section) {
        if (section == null) {
            return DEFAULTS;
        }
        return new EnemyCosts(section.getInt("zombie", DEFAULTS.zombie()),
                section.getInt("skeleton", DEFAULTS.skeleton()),
                section.getInt("spider", DEFAULTS.spider()),
                section.getInt("shadow-stalker", DEFAULTS.shadowStalker()));
    }
}
