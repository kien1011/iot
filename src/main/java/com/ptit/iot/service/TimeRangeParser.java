package com.ptit.iot.service;

import com.ptit.iot.exception.BadRequestException;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TimeRangeParser {
    private static final Pattern TIME_PREFIX = Pattern.compile(
            "^(\\d{4})(?:-(\\d{2})(?:-(\\d{2})(?: (\\d{2})(?::(\\d{2})(?::(\\d{2}))?)?)?)?)?$"
    );

    public TimeRange parse(String input) {
        String value = input == null ? "" : input.trim();
        Matcher matcher = TIME_PREFIX.matcher(value);
        if (!matcher.matches()) {
            throw new BadRequestException("Invalid time filter.");
        }

        int year = Integer.parseInt(matcher.group(1));
        Integer month = numberOrNull(matcher.group(2));
        Integer day = numberOrNull(matcher.group(3));
        Integer hour = numberOrNull(matcher.group(4));
        Integer minute = numberOrNull(matcher.group(5));
        Integer second = numberOrNull(matcher.group(6));

        try {
            if (month == null) {
                LocalDateTime start = LocalDate.of(year, 1, 1).atStartOfDay();
                return new TimeRange(start, start.plusYears(1));
            }
            if (day == null) {
                YearMonth ym = YearMonth.of(year, month);
                LocalDateTime start = ym.atDay(1).atStartOfDay();
                return new TimeRange(start, start.plusMonths(1));
            }

            LocalDate date = LocalDate.of(year, month, day);
            if (hour == null) {
                LocalDateTime start = date.atStartOfDay();
                return new TimeRange(start, start.plusDays(1));
            }
            if (hour < 0 || hour > 23) throw new DateTimeException("hour");

            if (minute == null) {
                LocalDateTime start = date.atTime(hour, 0);
                return new TimeRange(start, start.plusHours(1));
            }
            if (minute < 0 || minute > 59) throw new DateTimeException("minute");

            if (second == null) {
                LocalDateTime start = date.atTime(hour, minute);
                return new TimeRange(start, start.plusMinutes(1));
            }
            if (second < 0 || second > 59) throw new DateTimeException("second");

            LocalDateTime start = date.atTime(hour, minute, second);
            return new TimeRange(start, start.plusSeconds(1));
        } catch (DateTimeException ex) {
            throw new BadRequestException("Invalid time filter.");
        }
    }

    private Integer numberOrNull(String value) {
        return value == null ? null : Integer.valueOf(value);
    }

    public record TimeRange(LocalDateTime startInclusive, LocalDateTime endExclusive) {
    }
}
