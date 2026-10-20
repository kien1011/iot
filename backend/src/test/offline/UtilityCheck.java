import vn.ptit.iot.util.*;
import java.time.*;
import java.util.*;
/** No framework dependencies. Compile with --release 17; this does not build Spring Boot. */
public class UtilityCheck {
    static int checks;
    static void check(boolean ok, String name) {
        checks++; if (!ok) throw new AssertionError(name);
    }
    static void invalid(Runnable operation, String name) {
        checks++;
        try { operation.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(name);
    }
    public static void main(String[] args) {
        var lux = new LuxConverter(10000,50000,0.7);
        double previous=Double.POSITIVE_INFINITY;
        for(int adc=0; adc<=1023; adc++) {
            double value=lux.convert(adc);
            check(Double.isFinite(value) && value>=0 && value<=previous && Math.rint(value)==value,"ADC "+adc);
            previous=value;
        }
        check(lux.convert(0)==lux.convert(1),"bright-end clamp");
        check(lux.convert(1023)==lux.convert(1022),"dark-end clamp");
        check(lux.convert(100)>lux.convert(900),"inverse relationship");
        check(LuxConverter.roundOneDecimal(28.55)==28.6,"temperature rounding");
        check(LuxConverter.roundOneDecimal(-2.35)==-2.4,"negative rounding");
        String[] times={"2024","2024-02","2024-02-29","2024-02-29 23","2024-02-29 23:59","2024-02-29 23:59:59"};
        long[] seconds={31622400,2505600,86400,3600,60,1};
        for(int i=0;i<times.length;i++) {
            var r=TimeRange.parse(times[i]);
            check(Duration.between(r.start(),r.end()).getSeconds()==seconds[i],times[i]);
        }
        for(String s : List.of("2023-02-29","1900-02-29","2026-2","2026-01-32","2026-13",
            "2026-01-01 24","2026-01-01 01:60","2026-01-01 01:01:60","2026-01-01T00:00","2026-","abc")) {
            invalid(()->TimeRange.parse(s),"invalid time "+s);
        }
        check(TimeRange.parse("2000-02-29").end()!=null,"century leap year");
        check(TimeRange.parse("0000").start().equals(TimeRange.parse("0000").end()),"valid year before MySQL range empty");
        check(TimeRange.parse("9999").end()==null,"last MySQL year unbounded end");
        check(TimeRange.parse(" ").start()==null,"blank time");
        for(String s : List.of("30","+30.5","-2.5",".5","-.5","0")) {
            check(TimeRange.exactNumber(s).equals(Double.parseDouble(s)),"number "+s);
        }
        for(String s : List.of("1e2","1,2","Infinity","12 lux","30.","--1","NaN")) {
            invalid(()->TimeRange.exactNumber(s),"invalid number "+s);
        }
        check(TimeRange.exactNumber("")==null,"blank number");
        System.out.println("PASS "+checks+" assertions: actual LuxConverter and TimeRange classes");
        System.out.println("Runtime: "+System.getProperty("java.version")+" (not a Maven/Spring integration test)");
    }
}
