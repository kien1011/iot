package com.ptit.iot.service;

import com.ptit.iot.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExactSensorValueParserTest {
    @Test
    void acceptsFrontendDecimalForms() {
        assertEquals(30.0, ExactSensorValueParser.parse("30"));
        assertEquals(-2.5, ExactSensorValueParser.parse("-2.5"));
        assertEquals(0.5, ExactSensorValueParser.parse(".5"));
        assertEquals(2.0, ExactSensorValueParser.parse("+2"));
    }

    @Test
    void rejectsUnsupportedNumericForms() {
        assertThrows(BadRequestException.class, () -> ExactSensorValueParser.parse("1e3"));
        assertThrows(BadRequestException.class, () -> ExactSensorValueParser.parse("NaN"));
        assertThrows(BadRequestException.class, () -> ExactSensorValueParser.parse("30abc"));
    }
}
