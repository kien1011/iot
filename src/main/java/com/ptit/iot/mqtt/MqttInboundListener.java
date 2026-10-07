package com.ptit.iot.mqtt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ptit.iot.dto.device.DeviceStatusMqttPayload;
import com.ptit.iot.mqtt.event.DeviceStatusMqttMessageEvent;
import com.ptit.iot.mqtt.event.SensorMqttMessageEvent;
import com.ptit.iot.service.DeviceControlService;
import com.ptit.iot.service.SensorIngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class MqttInboundListener {
    private static final Logger log = LoggerFactory.getLogger(MqttInboundListener.class);

    private final ObjectMapper objectMapper;
    private final SensorIngestionService sensorIngestionService;
    private final DeviceControlService deviceControlService;

    public MqttInboundListener(ObjectMapper objectMapper,
                               SensorIngestionService sensorIngestionService,
                               DeviceControlService deviceControlService) {
        this.objectMapper = objectMapper;
        this.sensorIngestionService = sensorIngestionService;
        this.deviceControlService = deviceControlService;
    }

    @EventListener
    public void onSensorData(SensorMqttMessageEvent event) {
        sensorIngestionService.acceptMqttPayload(event.payload());
    }

    @EventListener
    public void onDeviceStatus(DeviceStatusMqttMessageEvent event) {
        try {
            DeviceStatusMqttPayload payload = objectMapper.readValue(event.payload(), DeviceStatusMqttPayload.class);
            deviceControlService.handleHardwareStatus(payload);
        } catch (JsonProcessingException ex) {
            log.warn("Rejected invalid device status MQTT JSON: {}", event.payload());
        }
    }
}
