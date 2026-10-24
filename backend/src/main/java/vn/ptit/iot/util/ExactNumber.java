package vn.ptit.iot.util;

public final class ExactNumber {
    private ExactNumber() {}
    public static Double parse(String input) {
        if (input == null || input.isBlank()) return null;
        String value = input.trim();
        if (!value.matches("[+-]?(?:\\d+(?:\\.\\d+)?|\\.\\d+)")) throw new IllegalArgumentException("Invalid value");
        double number = Double.parseDouble(value);
        if (!Double.isFinite(number)) throw new IllegalArgumentException("Invalid value");
        return number;
    }
}
