package com.ptit.iot.service;

import com.ptit.iot.exception.BadRequestException;

import java.util.regex.Pattern;

/** Matches the exact-number input rules of the supplied frontend demo. */
public final class ExactSensorValueParser {
    private static final Pattern EXACT_NUMBER = Pattern.compile(
            "^[+-]?(?:\\d+(?:\\.\\d+)?|\\.\\d+)$"
    );

    private ExactSensorValueParser() {
    }

    public static Double parse(String input) {
        String value = input == null ? "" : input.trim();
        if (!EXACT_NUMBER.matcher(value).matches()) {
            throw new BadRequestException("Invalid sensor value filter.");
        }

        double number;
        try {
            number = Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            throw new BadRequestException("Invalid sensor value filter.");
        }
        if (!Double.isFinite(number)) {
            throw new BadRequestException("Invalid sensor value filter.");
        }
        return number;
    }
}
