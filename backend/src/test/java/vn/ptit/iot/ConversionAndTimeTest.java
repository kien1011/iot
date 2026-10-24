package vn.ptit.iot;

import java.time.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import vn.ptit.iot.util.*;
import static org.assertj.core.api.Assertions.*;

class ConversionAndTimeTest {
    @Test void originalLuxModelIsFiniteDecreasingAndExactlyRounded() {
        var lux = new LuxConverter(10000, 50000, 0.7);
        assertThat(lux.convert(0)).isEqualTo(lux.convert(1));
        assertThat(lux.convert(1023)).isEqualTo(lux.convert(1022));
        assertThat(lux.convert(68)).isGreaterThan(lux.convert(200));
        double previous = Double.MAX_VALUE;
        for (int adc : IntStream.rangeClosed(0,1023).toArray()) {
            double value = lux.convert(adc);
            assertThat(Double.isFinite(value)).isTrue();
            assertThat(value).isLessThanOrEqualTo(previous);
            assertThat(value).isEqualTo((double)Math.round(value));
            previous = value;
        }
        assertThat(LuxConverter.roundOneDecimal(28.55)).isEqualTo(28.6);
    }
    @Test void sixCalendarPrecisionsAreHalfOpenIntervals() {
        for (String text : new String[]{"2024", "2024-02", "2024-02-29", "2024-02-29 23", "2024-02-29 23:59", "2024-02-29 23:59:59"}) {
            var range = TimeRange.parse(text);
            assertThat(range.start()).isBefore(range.end());
        }
        assertThat(TimeRange.parse("2024-02").end()).isEqualTo(LocalDateTime.of(2024,3,1,0,0));
        assertThat(TimeRange.parse("2024-02-29 23:59:59").end()).isEqualTo(LocalDateTime.of(2024,3,1,0,0));
        assertThat(TimeRange.parse("9999").end().getYear()).isEqualTo(10000);
        assertThat(TimeRange.parse("0000").start().getYear()).isZero();
        assertThat(TimeRange.parse(" ")).isNull();
    }
    @Test void invalidDateLengthsLeapYearsAndClockValuesAreRejected() {
        for (String value : new String[]{"2023-02-29", "1900-02-29", "2026-2", "2026-02-30", "2026-01-01 24", "2026-01-01 00:60", "2026-01-01 00:00:60", "2026-13", "2026-00", "2026-01-00", "26", "2026-01-01T10"}) {
            assertThatThrownBy(() -> TimeRange.parse(value)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(TimeRange.parse("2000-02-29")).isNotNull();
    }
    @Test void numberRulesMatchDemo() {
        for (String number : new String[]{"1", "+1.0", "-.5", ".25", "-30", " 0 "}) assertThat(ExactNumber.parse(number)).isNotNull();
        for (String number : new String[]{"Infinity", "1e2", "1,2", "2 lux", "NaN", "1.", "+"}) {
            assertThatThrownBy(() -> ExactNumber.parse(number)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
