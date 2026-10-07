package com.ptit.iot.mqtt;

import com.ptit.iot.config.MqttProperties;
import com.ptit.iot.domain.enums.DeviceAction;
import com.ptit.iot.exception.MqttUnavailableException;
import com.ptit.iot.mqtt.event.DeviceStatusMqttMessageEvent;
import com.ptit.iot.mqtt.event.SensorMqttMessageEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class MqttGateway implements MqttCallbackExtended {
    private static final Logger log = LoggerFactory.getLogger(MqttGateway.class);

    private final MqttProperties properties;
    private final ApplicationEventPublisher eventPublisher;
    private final AtomicBoolean connecting = new AtomicBoolean(false);

    private MqttAsyncClient client;
    private MqttConnectOptions connectOptions;

    public MqttGateway(MqttProperties properties, ApplicationEventPublisher eventPublisher) {
        this.properties = properties;
        this.eventPublisher = eventPublisher;
    }

    @PostConstruct
    public void initialize() throws MqttException {
        client = new MqttAsyncClient(
                properties.getBrokerUri(),
                properties.getClientId(),
                new MemoryPersistence()
        );
        client.setCallback(this);

        connectOptions = new MqttConnectOptions();
        connectOptions.setAutomaticReconnect(true);
        connectOptions.setCleanSession(true);
        connectOptions.setConnectionTimeout(10);
        connectOptions.setKeepAliveInterval(20);
        if (hasText(properties.getUsername())) {
            connectOptions.setUserName(properties.getUsername());
        }
        if (properties.getPassword() != null) {
            connectOptions.setPassword(properties.getPassword().toCharArray());
        }
    }

    @Scheduled(fixedDelayString = "${iot.mqtt.reconnect-delay-ms:5000}")
    public void ensureConnected() {
        if (client == null || client.isConnected() || !connecting.compareAndSet(false, true)) {
            return;
        }

        try {
            client.connect(connectOptions, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    connecting.set(false);
                    log.info("MQTT connected to {}", properties.getBrokerUri());
                    subscribeTopics();
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    connecting.set(false);
                    log.warn("MQTT connection failed: {}", exception == null ? "unknown error" : exception.getMessage());
                }
            });
        } catch (MqttException ex) {
            connecting.set(false);
            log.warn("MQTT connect attempt failed: {}", ex.getMessage());
        }
    }

    public void publishDeviceCommand(Integer deviceId, DeviceAction action) {
        if (client == null || !client.isConnected()) {
            throw new MqttUnavailableException("MQTT Broker is not connected. The history record remains LOADING.");
        }

        String payload = deviceId + ":" + action.name();
        try {
            IMqttDeliveryToken token = client.publish(
                    properties.getCommandTopic(),
                    payload.getBytes(StandardCharsets.UTF_8),
                    properties.getQos(),
                    false
            );
            token.waitForCompletion(2000);
            log.info("MQTT command published: {}", payload);
        } catch (MqttException ex) {
            throw new MqttUnavailableException(
                    "Could not publish the device command. The history record remains LOADING.",
                    ex
            );
        }
    }

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        connecting.set(false);
        log.info("MQTT {}connected: {}", reconnect ? "re" : "", serverURI);
        subscribeTopics();
    }

    @Override
    public void connectionLost(Throwable cause) {
        connecting.set(false);
        log.warn("MQTT connection lost: {}", cause == null ? "unknown cause" : cause.getMessage());
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
        if (properties.getSensorTopic().equals(topic)) {
            eventPublisher.publishEvent(new SensorMqttMessageEvent(payload));
        } else if (properties.getStatusTopic().equals(topic)) {
            eventPublisher.publishEvent(new DeviceStatusMqttMessageEvent(payload));
        }
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // No action required. Device confirmation comes from iot/devices/status.
    }

    private void subscribeTopics() {
        if (client == null || !client.isConnected()) return;
        try {
            client.subscribe(properties.getSensorTopic(), properties.getQos());
            client.subscribe(properties.getStatusTopic(), properties.getQos());
            log.info("MQTT subscribed to {} and {}", properties.getSensorTopic(), properties.getStatusTopic());
        } catch (MqttException ex) {
            log.warn("MQTT subscribe failed: {}", ex.getMessage());
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @PreDestroy
    public void close() {
        if (client == null) return;
        try {
            if (client.isConnected()) client.disconnect().waitForCompletion(2000);
            client.close();
        } catch (MqttException ex) {
            log.debug("MQTT shutdown warning: {}", ex.getMessage());
        }
    }
}
