package vn.ptit.iot.util;
/** Nominal circuit model, not a calibration of the user's actual LDR. */
public class LuxConverter {
    private final double fixedResistance;
    private final double resistanceAt10Lux;
    private final double gamma;
    public LuxConverter(double fixedResistance, double resistanceAt10Lux, double gamma) {
        if (!(fixedResistance > 0 && Double.isFinite(fixedResistance)
                && resistanceAt10Lux > 0 && Double.isFinite(resistanceAt10Lux)
                && gamma > 0 && Double.isFinite(gamma))) {
            throw new IllegalArgumentException("LDR configuration must be finite and positive");
        }
        this.fixedResistance = fixedResistance;
        this.resistanceAt10Lux = resistanceAt10Lux;
        this.gamma = gamma;
    }
    public double convert(int rawAdc) {
        // Both ADC endpoints are valid. Saturate to [1,1022] for finite resistance.
        double adc = Math.max(1, Math.min(1022, rawAdc));
        double resistance = fixedResistance * adc / (1023.0 - adc);
        double lux = 10 * Math.pow(resistanceAt10Lux / resistance, 1 / gamma);
        return Math.round(lux); // The exact integer displayed/searched is stored as DOUBLE.
    }
    public static double roundOneDecimal(double value) {
        return java.math.BigDecimal.valueOf(value).setScale(1, java.math.RoundingMode.HALF_UP).doubleValue();
    }
}
