package vn.ptit.iot.util;

import java.time.*;
import java.util.regex.Pattern;

/** Local Vietnam calendar interval, inclusive start and exclusive end. */
public record TimeRange(LocalDateTime start, LocalDateTime end) {
    private static final Pattern FORMAT = Pattern.compile("^(\\d{4})(?:-(\\d{2})(?:-(\\d{2})(?: (\\d{2})(?::(\\d{2})(?::(\\d{2}))?)?)?)?)?$");

    public static TimeRange parse(String input) {
        if (input == null || input.isBlank()) return null;
        var match = FORMAT.matcher(input.trim());
        if (!match.matches()) throw new IllegalArgumentException("Invalid time");
        int[] parts = {0, 1, 1, 0, 0, 0};
        int precision = 0;
        for (int i = 0; i < parts.length; i++) {
            if (match.group(i + 1) != null) {
                parts[i] = Integer.parseInt(match.group(i + 1));
                precision = i;
            }
        }
        try {
            var start = LocalDateTime.of(parts[0], parts[1], parts[2], parts[3], parts[4], parts[5]);
            var end = switch (precision) {
                case 0 -> start.plusYears(1);
                case 1 -> start.plusMonths(1);
                case 2 -> start.plusDays(1);
                case 3 -> start.plusHours(1);
                case 4 -> start.plusMinutes(1);
                default -> start.plusSeconds(1);
            };
            return new TimeRange(start, end);
        } catch (DateTimeException ex) {
            throw new IllegalArgumentException("Invalid time", ex);
        }
    }
}
