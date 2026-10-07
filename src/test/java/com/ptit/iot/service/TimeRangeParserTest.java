package com.ptit.iot.service;

import com.ptit.iot.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TimeRangeParserTest {
    private final TimeRangeParser parser = new TimeRangeParser();

    @Test
    void parsesMinutePrefixLikeFrontendDemo() {
        TimeRangeParser.TimeRange range = parser.parse("2026-09-23 10:30");
        assertEquals(LocalDateTime.of(2026, 9, 23, 10, 30), range.startInclusive());
        assertEquals(LocalDateTime.of(2026, 9, 23, 10, 31), range.endExclusive());
    }

    @Test
    void rejectsInvalidCalendarDate() {
        assertThrows(BadRequestException.class, () -> parser.parse("2026-04-31"));
    }

    @Test
    void acceptsLeapDay() {
        TimeRangeParser.TimeRange range = parser.parse("2028-02-29");
        assertEquals(LocalDateTime.of(2028, 2, 29, 0, 0), range.startInclusive());
        assertEquals(LocalDateTime.of(2028, 3, 1, 0, 0), range.endExclusive());
    }
}
