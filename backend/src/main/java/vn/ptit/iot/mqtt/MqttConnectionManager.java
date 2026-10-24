package vn.ptit.iot.mqtt;

import jakarta.annotation.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.paho.client.mqttv3.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MqttConnectionManager implements MqttCallbackExtended {
    private static final Logger log = LoggerFactory.getLogger(MqttConnectionManager.class);
    private final MqttAsyncClient client;
    private final MqttMessageHandler messages;
    private final MqttConnectOptions options = new MqttConnectOptions();
    private final AtomicBoolean connecting = new AtomicBoolean();
    private final ScheduledExecutorService reconnects = Executors.newSingleThreadScheduledExecutor(task -> {
        var thread = new Thread(task, "mqtt-connection"); thread.setDaemon(true); return thread;
    });

    public MqttConnectionManager(MqttAsyncClient client, MqttMessageHandler messages,
            @Value("${mqtt.username}") String username, @Value("${mqtt.password}") String password) {
        this.client = client; this.messages = messages;
        options.setUserName(username); options.setPassword(password.toCharArray());
        options.setCleanSession(true); options.setAutomaticReconnect(false);
        options.setConnectionTimeout(3); options.setKeepAliveInterval(15);
        client.setCallback(this);
    }
    @PostConstruct public void start() {
        // Includes initial connection failures. Async connect never occupies an HTTP thread.
        reconnects.scheduleWithFixedDelay(this::connect, 0, 2, TimeUnit.SECONDS);
    }
    private void connect() {
        if (client.isConnected() || !connecting.compareAndSet(false, true)) return;
        try {
            client.connect(options, null, new IMqttActionListener() {
                @Override public void onSuccess(IMqttToken token) { connecting.set(false); }
                @Override public void onFailure(IMqttToken token, Throwable error) {
                    connecting.set(false); log.debug("MQTT connection unavailable", error);
                }
            });
        } catch (MqttException ex) { connecting.set(false); log.debug("MQTT connection attempt failed", ex); }
    }
    @Override public void connectComplete(boolean reconnect, String uri) {
        try {
            client.subscribe(new String[]{"iot/sensors/data", "iot/devices/status"}, new int[]{0, 0}, null,
                new IMqttActionListener() {
                    @Override public void onSuccess(IMqttToken token) { log.info("MQTT subscribed at {}", uri); }
                    @Override public void onFailure(IMqttToken token, Throwable error) {
                        log.warn("MQTT subscription failed; reconnecting", error);
                        disconnectForRetry();
                    }
                });
        } catch (MqttException ex) { log.warn("MQTT subscribe failed", ex); disconnectForRetry(); }
    }
    private void disconnectForRetry() {
        try { client.disconnectForcibly(0, 0, false); }
        catch (MqttException ex) { log.debug("MQTT disconnect failed", ex); }
    }
    @Override public void connectionLost(Throwable cause) { log.info("MQTT connection lost; reconnecting"); }
    @Override public void messageArrived(String topic, MqttMessage message) {
        if (message.isRetained()) return;
        try { messages.receive(topic, new String(message.getPayload(), StandardCharsets.UTF_8)); }
        catch (RuntimeException ex) { log.error("MQTT packet processing failed", ex); }
    }
    @Override public void deliveryComplete(IMqttDeliveryToken token) { /* ACKs arrive on the status topic. */ }
    @PreDestroy public void stop() {
        reconnects.shutdownNow();
        disconnectForRetry();
    }
}
