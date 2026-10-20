package vn.ptit.iot.util;
import java.time.*;
import java.util.regex.*;
/** Inclusive start, exclusive end, in the same local timezone as MySQL DATETIME. */
public record TimeRange(LocalDateTime start, LocalDateTime end) {
    private static final Pattern FORMAT = Pattern.compile(
        "^(\\d{4})(?:-(\\d{2})(?:-(\\d{2})(?: (\\d{2})(?::(\\d{2})(?::(\\d{2}))?)?)?)?)?$");
    private static final LocalDateTime MYSQL_MIN = LocalDateTime.of(1000, 1, 1, 0, 0);
    private static final LocalDateTime MYSQL_LIMIT = LocalDateTime.of(10000, 1, 1, 0, 0);
    public static TimeRange parse(String input) {
        if (input == null || input.isBlank()) return new TimeRange(null, null);
        Matcher m = FORMAT.matcher(input.trim());
        if (!m.matches()) throw new IllegalArgumentException("Invalid time");
        try {
            int count = 1;
            while (count < 6 && m.group(count + 1) != null) count++;
            LocalDateTime start = LocalDateTime.of(Integer.parseInt(m.group(1)),
                part(m, 2, 1), part(m, 3, 1), part(m, 4, 0), part(m, 5, 0), part(m, 6, 0));
            LocalDateTime end = switch (count) {
                case 1 -> start.plusYears(1);
                case 2 -> start.plusMonths(1);
                case 3 -> start.plusDays(1);
                case 4 -> start.plusHours(1);
                case 5 -> start.plusMinutes(1);
                default -> start.plusSeconds(1);
            };
            // Valid years outside stored MySQL DATETIME range simply match no rows.
            if (start.isBefore(MYSQL_MIN)) start = MYSQL_MIN;
            if (end.isBefore(MYSQL_MIN)) end = MYSQL_MIN;
            if (!end.isBefore(MYSQL_LIMIT)) end = null;
            return new TimeRange(start, end);
        } catch (DateTimeException ex) { throw new IllegalArgumentException("Invalid time", ex); }
    }
    private static int part(Matcher m, int group, int fallback) {
        return m.group(group) == null ? fallback : Integer.parseInt(m.group(group));
    }
    public static Double exactNumber(String input) {
        if (input == null || input.isBlank()) return null;
        String s = input.trim();
        if (!s.matches("^[+-]?(?:\\d+(?:\\.\\d+)?|\\.\\d+)$")) {
            throw new IllegalArgumentException("Invalid number");
        }
        double value = Double.parseDouble(s);
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Invalid number");
        return value;
    }
}
