package com.ptit.iot.dto.sensor;

public record SensorMqttPayload(Double temperature, Double humidity, Double light) {
}
