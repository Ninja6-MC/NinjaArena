package com.ninja6.ninjaarena.event;

import com.ninja6.ninjaarena.arena.Arena;
import com.ninja6.ninjaarena.reward.RewardSettings;
import java.math.BigDecimal;
import java.net.URI;
import java.time.DayOfWeek;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;

public record EventSettings(ZoneId zone, List<Integer> leads, URI webhook, List<Night> schedule) {

    public record Night(String id, DayOfWeek day, LocalTime time, String arena, Duration duration, Bonus bonus) {
    }

    public record Bonus(long cents, RewardSettings.Rule cosmetic, boolean seasonal) {
        public static final Bonus NONE = new Bonus(0, null, false);
    }

    public static final EventSettings OFF = new EventSettings(ZoneId.of("UTC"), List.of(), null, List.of());

    public EventSettings {
        leads = List.copyOf(leads);
        schedule = List.copyOf(schedule);
    }

    public static EventSettings from(ConfigurationSection config, RewardSettings rewards) {
        if (config.contains("events") && !config.isConfigurationSection("events")) {
            throw new IllegalArgumentException("events must be a section");
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(string(config.get("events.time-zone", "UTC")));
        } catch (DateTimeException | IllegalArgumentException e) {
            throw new IllegalArgumentException("events.time-zone must be a valid time zone");
        }
        List<Integer> leads = new ArrayList<>();
        Set<Integer> unique = new HashSet<>();
        for (Object lead : list(config, "events.announcement-lead-minutes", List.of(30, 10, 1))) {
            if (!(lead instanceof Integer minutes) || minutes < 1 || minutes > 1440 || !unique.add(minutes)) {
                throw new IllegalArgumentException("event lead times must be distinct integers from 1 to 1440");
            }
            leads.add(minutes);
        }
        URI webhook = webhook(config.get("events.webhook-url", ""));
        List<Night> schedule = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Object entry : list(config, "events.schedule", List.of())) {
            try {
                if (!(entry instanceof Map<?, ?> rule)
                        || !Set.of("id", "day", "time", "arena", "duration-minutes", "bonus").containsAll(rule.keySet())) {
                    throw new IllegalArgumentException("expected an event mapping with known keys");
                }
                String id = string(rule.get("id"));
                if (!id.matches("[a-z0-9_-]{1,48}") || !ids.add(id)) {
                    throw new IllegalArgumentException("invalid or duplicate id");
                }
                DayOfWeek day = DayOfWeek.valueOf(string(rule.get("day")).toUpperCase(Locale.ROOT));
                String time = string(rule.get("time"));
                if (!time.matches("[0-9]{2}:[0-9]{2}")) {
                    throw new IllegalArgumentException("time must use HH:mm");
                }
                String arena = Arena.normalizeName(string(rule.get("arena")));
                if (!Arena.isValidName(arena)) {
                    throw new IllegalArgumentException("invalid arena name");
                }
                if (!(rule.get("duration-minutes") instanceof Integer duration) || duration < 1 || duration > 1440) {
                    throw new IllegalArgumentException("duration-minutes must be an integer from 1 to 1440");
                }
                Bonus bonus = rule.containsKey("bonus") ? bonus(rule.get("bonus"), rewards) : Bonus.NONE;
                schedule.add(new Night(id, day, LocalTime.parse(time), arena, Duration.ofMinutes(duration), bonus));
            } catch (DateTimeException | IllegalArgumentException e) {
                throw new IllegalArgumentException("invalid events.schedule entry " + (schedule.size() + 1)
                        + ": " + e.getMessage());
            }
        }
        return new EventSettings(zone, leads, webhook, schedule);
    }

    private static List<?> list(ConfigurationSection config, String path, List<?> fallback) {
        if (config.get(path, fallback) instanceof List<?> entries && entries.size() <= 100) {
            return entries;
        }
        throw new IllegalArgumentException(path + " must be a list of at most 100 entries");
    }

    private static String string(Object value) {
        if (value instanceof String text) {
            return text;
        }
        throw new IllegalArgumentException("expected text");
    }

    private static URI webhook(Object value) {
        try {
            String text = string(value);
            if (text.isEmpty()) {
                return null;
            }
            URI uri = URI.create(text);
            if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getUserInfo() == null && uri.getFragment() == null) {
                return uri;
            }
        } catch (IllegalArgumentException ignored) {
            // The endpoint may contain a secret.
        }
        throw new IllegalArgumentException("events.webhook-url must be empty or an HTTPS URL");
    }

    private static Bonus bonus(Object value, RewardSettings rewards) {
        if (!(value instanceof Map<?, ?> bonus)
                || !Set.of("vault-per-match", "reward-id").containsAll(bonus.keySet())) {
            throw new IllegalArgumentException("bonus must contain only vault-per-match and reward-id");
        }
        long cents = 0;
        if (bonus.containsKey("vault-per-match")) {
            try {
                if (!(bonus.get("vault-per-match") instanceof Number amount)) {
                    throw new IllegalArgumentException();
                }
                cents = new BigDecimal(amount.toString()).movePointRight(2).longValueExact();
                if (cents < 0 || cents > 100_000_000) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException | ArithmeticException e) {
                throw new IllegalArgumentException("vault-per-match must be from 0 to 1000000 with at most two decimal places");
            }
        }
        if (!bonus.containsKey("reward-id")) {
            return new Bonus(cents, null, false);
        }
        String id = string(bonus.get("reward-id"));
        List<RewardSettings.Rule> milestones = rewards.milestones().stream().filter(rule -> rule.id().equals(id)).toList();
        List<RewardSettings.Rule> placings = rewards.placings().stream().filter(rule -> rule.id().equals(id)).toList();
        if (milestones.size() + placings.size() != 1) {
            throw new IllegalArgumentException("reward-id must identify exactly one reward rule");
        }
        RewardSettings.Rule rule = milestones.isEmpty() ? placings.getFirst() : milestones.getFirst();
        if (rule.title().isEmpty() && rule.trophy().isEmpty()) {
            throw new IllegalArgumentException("reward-id must include a title or trophy");
        }
        return new Bonus(cents, rule, milestones.isEmpty());
    }
}
