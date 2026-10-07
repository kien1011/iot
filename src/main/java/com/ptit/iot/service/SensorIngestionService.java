package com.ptit.iot.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ptit.iot.domain.Sensor;
import com.ptit.iot.domain.SensorData;
import com.ptit.iot.dto.sensor.SensorLatestResponse;
import com.ptit.iot.dto.sensor.SensorMqttPayload;
import com.ptit.iot.repository.SensorDataRepository;
import com.ptit.iot.repository.SensorRepository;
import com.ptit.iot.websocket.WebSocketBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class SensorIngestionService {
    private static final Logger log = LoggerFactory.getLogger(SensorIngestionService.class);

    private final ObjectMapper objectMapper;
    private final SensorRepository sensorRepository;
    private final SensorDataRepository sensorDataRepository;
    private final WebSocketBroadcaster webSocketBroadcaster;
    private final Clock clock;

    public SensorIngestionService(ObjectMapper objectMapper,
                                  SensorRepository sensorRepository,
                                  SensorDataRepository sensorDataRepository,
                                  WebSocketBroadcaster webSocketBroadcaster,
                                  Clock clock) {
        this.objectMapper = objectMapper;
        this.sensorRepository = sensorRepository;
        this.sensorDataRepository = sensorDataRepository;
        this.webSocketBroadcaster = webSocketBroadcaster;
        this.clock = clock;
    }

    @Transactional
    public void acceptMqttPayload(String rawPayload) {
        final SensorMqttPayload payload;
        try {
            payload = objectMapper.readValue(rawPayload, SensorMqttPayload.class);
        } catch (JsonProcessingException ex) {
            log.warn("Rejected invalid sensor MQTT JSON: {}", rawPayload);
            return;
        }

        if (!isValid(payload)) {
            log.warn("Rejected invalid sensor values: {}", rawPayload);
            return;
        }

        SensorLatestResponse eventData = persistMeasurement(payload);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                webSocketBroadcaster.broadcast("sensor:update", eventData);
            }
        });
    }

    protected SensorLatestResponse persistMeasurement(SensorMqttPayload payload) {
        Sensor temperatureSensor = requiredSensor("Temperature");
        Sensor humiditySensor = requiredSensor("Humidity");
        Sensor lightSensor = requiredSensor("Light");

        // One MQTT payload is one measurement cycle: all three rows use exactly the same timestamp.
        LocalDateTime measurementTime = LocalDateTime.now(clock);

        SensorData temperature = new SensorData();
        temperature.setSensor(temperatureSensor);
        temperature.setValue(payload.temperature());
        temperature.setCreatedAt(measurementTime);

        SensorData humidity = new SensorData();
        humidity.setSensor(humiditySensor);
        humidity.setValue(payload.humidity());
        humidity.setCreatedAt(measurementTime);

        SensorData light = new SensorData();
        light.setSensor(lightSensor);
        light.setValue(payload.light());
        light.setCreatedAt(measurementTime);

        // Save in Temperature -> Humidity -> Light order so equal timestamps remain intuitive in history.
        sensorDataRepository.saveAll(List.of(temperature, humidity, light));

        return new SensorLatestResponse(
                payload.temperature(),
                payload.humidity(),
                payload.light(),
                measurementTime
        );
    }

    private Sensor requiredSensor(String name) {
        return sensorRepository.findByName(name)
                .orElseThrow(() -> new IllegalStateException("Missing seeded sensor: " + name));
    }

    private boolean isValid(SensorMqttPayload payload) {
        if (payload == null || payload.temperature() == null || payload.humidity() == null || payload.light() == null) {
            return false;
        }
        if (!Double.isFinite(payload.temperature()) || !Double.isFinite(payload.humidity()) || !Double.isFinite(payload.light())) {
            return false;
        }
        if (payload.humidity() < 0 || payload.humidity() > 100) return false;
        // Keep the backend unit-agnostic: the UI currently labels light as lux, while the
        // hardware side can later convert/calibrate before publishing if needed.
        return payload.light() >= 0;
    }
}
