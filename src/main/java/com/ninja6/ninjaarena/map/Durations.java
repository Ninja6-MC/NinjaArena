package com.ninja6.ninjaarena.map;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Durations written as in {@code config.yml}: one or more amounts with a unit, largest
 * first, such as {@code 30s}, {@code 30m}, {@code 2h}, {@code 1d} or {@code 1h30m}, or
 * {@code 0}. The longest is {@link #MAX}.
 */
public final class Durations {

    /** The longest duration accepted. */
    public static final Duration MAX = Duration.ofDays(365);

    private static final Pattern WHOLE = Pattern.compile("(?:[0-9]{1,6}[dhms])+");
    private static final Pattern PART = Pattern.compile("([0-9]{1,6})([dhms])");
    private static final String UNITS = "dhms";

    private Durations() {
    }

    /**
     * The duration a setting's value stands for: the text, or the whole number 0.
     *
     * @return empty when the value is not a duration up to {@link #MAX}
     */
    public static Optional<Duration> parse(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue() == 0 ? Optional.of(Duration.ZERO) : Optional.empty();
        }
        if (!(value instanceof String text)) {
            return Optional.empty();
        }
        String trimmed = text.trim().toLowerCase(Locale.ROOT);
        if (trimmed.equals("0")) {
            return Optional.of(Duration.ZERO);
        }
        if (!WHOLE.matcher(trimmed).matches()) {
            return Optional.empty();
        }
        Duration total = Duration.ZERO;
        int lastUnit = -1;
        Matcher part = PART.matcher(trimmed);
        while (part.find()) {
            int unit = UNITS.indexOf(part.group(2).charAt(0));
            if (unit <= lastUnit) {
                // Each unit once, largest first: 1h30m, never 30m1h or 1h1h.
                return Optional.empty();
            }
            lastUnit = unit;
            long amount = Long.parseLong(part.group(1));
            total = total.plus(switch (part.group(2)) {
                case "d" -> Duration.ofDays(amount);
                case "h" -> Duration.ofHours(amount);
                case "m" -> Duration.ofMinutes(amount);
                default -> Duration.ofSeconds(amount);
            });
        }
        return total.compareTo(MAX) <= 0 ? Optional.of(total) : Optional.empty();
    }

    /** The duration as {@link #parse} reads it back: {@code 0}, or units largest first. */
    public static String format(Duration duration) {
        if (duration.isZero()) {
            return "0";
        }
        StringBuilder text = new StringBuilder();
        long days = duration.toDays();
        if (days > 0) {
            text.append(days).append('d');
        }
        if (duration.toHoursPart() > 0) {
            text.append(duration.toHoursPart()).append('h');
        }
        if (duration.toMinutesPart() > 0) {
            text.append(duration.toMinutesPart()).append('m');
        }
        if (duration.toSecondsPart() > 0) {
            text.append(duration.toSecondsPart()).append('s');
        }
        return text.toString();
    }
}
