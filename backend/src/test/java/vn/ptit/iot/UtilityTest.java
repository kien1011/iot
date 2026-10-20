package vn.ptit.iot;
import org.junit.jupiter.api.Test;
import vn.ptit.iot.util.*;
import static org.assertj.core.api.Assertions.*;
class UtilityTest {
    @Test void conversionIsFiniteAndInverse() {
        var converter = new LuxConverter(10000,50000,0.7);
        double previous = Double.POSITIVE_INFINITY;
        for(int adc=0;adc<=1023;adc++) {
            double value=converter.convert(adc);
            assertThat(value).isFinite().isLessThanOrEqualTo(previous);
            previous=value;
        }
    }
    @Test void preciseTimeAndNumberParsing() {
        assertThat(TimeRange.parse("2024-02-29").end().toString()).isEqualTo("2024-03-01T00:00");
        assertThatThrownBy(() -> TimeRange.parse("2023-02-29")).isInstanceOf(IllegalArgumentException.class);
        assertThat(TimeRange.exactNumber("+28.5")).isEqualTo(28.5);
        assertThatThrownBy(() -> TimeRange.exactNumber("1e3")).isInstanceOf(IllegalArgumentException.class);
    }
}
