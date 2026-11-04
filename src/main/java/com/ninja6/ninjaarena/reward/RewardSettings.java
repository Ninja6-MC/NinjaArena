package com.ninja6.ninjaarena.reward;

import com.ninja6.ninjaarena.stats.Leaderboards;
import com.ninja6.ninjaarena.stats.Stat;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;

public record RewardSettings(List<Rule> milestones, List<Rule> placings, Payout payout) {

    public static final RewardSettings OFF = new RewardSettings(List.of(), List.of(), new Payout(false, 0, 0));

    public record Rule(String id, Stat stat, int target, String title, String tag, String trophy) {
    }

    /** Amounts in hundredths of a currency unit. */
    public record Payout(boolean enabled, long amount, long dailyCap) {
    }

    public RewardSettings {
        milestones = List.copyOf(milestones);
        placings = List.copyOf(placings);
    }

    public static RewardSettings from(ConfigurationSection config) {
        Object enabled = config.get("rewards.vault.enabled", false);
        if (!(enabled instanceof Boolean on)) {
            throw new IllegalArgumentException("rewards.vault.enabled must be true or false");
        }
        long amount = money(config, "rewards.vault.amount-per-match");
        long cap = money(config, "rewards.vault.daily-cap");
        if (on && (amount == 0 || cap == 0)) {
            throw new IllegalArgumentException("enabled Vault payouts require positive amount-per-match and daily-cap");
        }
        return new RewardSettings(rules(config, "milestones", false), rules(config, "placings", true),
                new Payout(on, amount, cap));
    }

    private static long money(ConfigurationSection config, String path) {
        try {
            if (config.get(path, 0) instanceof Number number) {
                long cents = new BigDecimal(number.toString()).movePointRight(2).longValueExact();
                if (cents >= 0 && cents <= 100_000_000) {
                    return cents;
                }
            }
        } catch (IllegalArgumentException | ArithmeticException ignored) {
            // Report the configuration path below.
        }
        throw new IllegalArgumentException(path + " must be from 0 to 1000000 with at most two decimal places");
    }

    private static List<Rule> rules(ConfigurationSection config, String key, boolean seasonal) {
        String path = "rewards." + key;
        if (!(config.get(path, List.of()) instanceof List<?> entries) || entries.size() > 100) {
            throw new IllegalArgumentException(path + " must be a list of at most 100 rules");
        }
        Set<String> ids = new HashSet<>();
        List<Rule> rules = new ArrayList<>();
        String targetKey = seasonal ? "rank" : "threshold";
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> rule)
                    || !Set.of("id", "stat", targetKey, "title", "tag", "trophy").containsAll(rule.keySet())
                    || !(rule.get("id") instanceof String id) || !id.matches("[a-z0-9_-]{1,48}") || !ids.add(id)
                    || !(rule.get("stat") instanceof String stat) || Leaderboards.stat(stat) == null
                    || !(rule.get(targetKey) instanceof Integer target) || target < 1 || seasonal && target > 1000
                    || !seasonal && rule.containsKey("trophy")) {
                throw new IllegalArgumentException(path + " contains an invalid or duplicate rule");
            }
            String title = label(rule, "title", path);
            String tag = label(rule, "tag", path);
            String trophy = label(rule, "trophy", path);
            if (title.isEmpty() && tag.isEmpty() && trophy.isEmpty()) {
                throw new IllegalArgumentException(path + " rule " + id + " needs a reward");
            }
            rules.add(new Rule(id, Leaderboards.stat(stat), target, title, tag, trophy));
        }
        return rules;
    }

    private static String label(Map<?, ?> rule, String key, String path) {
        if (!rule.containsKey(key)) {
            return "";
        }
        if (rule.get(key) instanceof String label && !label.isBlank() && label.length() <= 80
                && label.codePoints().noneMatch(Character::isISOControl)) {
            return label;
        }
        throw new IllegalArgumentException(path + " " + key + " must contain 1 to 80 printable characters");
    }
}
