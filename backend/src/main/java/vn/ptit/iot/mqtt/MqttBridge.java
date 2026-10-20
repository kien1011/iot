package vn.ptit.iot.mqtt;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import com.fasterxml.jackson.databind.*;
import jakarta.annotation.*;
import org.eclipse.paho.client.mqttv3.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import vn.ptit.iot.entity.DeviceStatus;
import vn.ptit.iot.service.*;
@Component
public class MqttBridge implements MqttCallbackExtended {
    private static final Logger log = LoggerFactory.getLogger(MqttBridge.class);
    private final MqttAsyncClient client;   
    private final SensorService sensors;
    private final DeviceService devices;
    private final ObjectMapper json;
    private final ScheduledExecutorService timers;
    private final MqttConnectOptions options = new MqttConnectOptions();
    private final AtomicBoolean connecting = new AtomicBoolean();
    private final AtomicBoolean subscribing = new AtomicBoolean();
    private volatile boolean subscribed;
    private ScheduledFuture<?> reconnectTask;
    public MqttBridge(MqttAsyncClient client, SensorService sensors, DeviceService devices,
                      ObjectMapper json, ScheduledExecutorService timers,
                      @Value("${app.mqtt.username}") String username, @Value("${app.mqtt.password}") String password) {
        this.client=client; this.sensors=sensors; this.devices=devices; this.json=json; this.timers=timers;
        options.setUserName(username); options.setPassword(password.toCharArray());
        options.setCleanSession(true); options.setAutomaticReconnect(false);
        options.setConnectionTimeout(3); options.setKeepAliveInterval(20);
        options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
    }
    @PostConstruct public void start() {
        client.setCallback(this);
        // Retry only the connection/subscription, never a control message.
        reconnectTask = timers.scheduleWithFixedDelay(this::maintainConnection, 0, 2, TimeUnit.SECONDS);
    }
    private void maintainConnection() {
        if (client.isConnected()) { subscribe(); return; }
        if (!connecting.compareAndSet(false, true)) return;
        try {
            client.connect(options, null, new IMqttActionListener() {
                @Override public void onSuccess(IMqttToken token) { connecting.set(false); }
                @Override public void onFailure(IMqttToken token, Throwable error) {
                    connecting.set(false); log.debug("MQTT connect failed; will reconnect", error);
                }
            });
        } catch (MqttException ex) { connecting.set(false); log.debug("MQTT connect unavailable", ex); }
    }
    private void subscribe() {
        if (subscribed || !subscribing.compareAndSet(false, true)) return;
        try {
            client.subscribe(new String[]{"iot/sensors/data", "iot/devices/status"}, new int[]{0, 0}, null,
                new IMqttActionListener() {
                    @Override public void onSuccess(IMqttToken token) { subscribed=true; subscribing.set(false); }
                    @Override public void onFailure(IMqttToken token, Throwable error) {
                        subscribing.set(false); log.warn("MQTT subscribe failed; will retry", error);
                    }
                });
        } catch (MqttException ex) { subscribing.set(false); log.debug("MQTT subscribe unavailable", ex); }
    }
    @Override public void connectComplete(boolean reconnect, String uri) {
        subscribed=false; subscribe(); log.info("MQTT connected to {}", uri);
    }
    @Override public void connectionLost(Throwable cause) { subscribed=false; subscribing.set(false); connecting.set(false); }
    @Override public void deliveryComplete(IMqttDeliveryToken token) { /* QoS 0 delivery is not a hardware ACK. */ }
    @Override public void messageArrived(String topic, MqttMessage message) {
        if (message.isRetained()) return; // Never consume an old retained snapshot/status on reconnect.
        try {
            JsonNode data = json.readTree(new String(message.getPayload(), StandardCharsets.UTF_8));
            if ("iot/sensors/data".equals(topic)) {
                if (!validSensor(data)) { log.warn("Discarded invalid sensor packet"); return; }
                sensors.record(data.get("temperature").asDouble(), data.get("humidity").asDouble(), data.get("light").asInt());
            } else if ("iot/devices/status".equals(topic) && data != null && data.path("deviceId").isIntegralNumber()
                       && ("ON".equals(data.path("status").asText()) || "OFF".equals(data.path("status").asText()))) {
                devices.acknowledge(data.get("deviceId").asInt(), DeviceStatus.valueOf(data.get("status").asText()));
            }
        } catch (Exception ex) {
            // MQTT is a boundary: log parsing/DB failures; never acknowledge or fabricate saved data.
            log.error("MQTT packet processing failed on {}", topic, ex);
        }
    }
    private boolean validSensor(JsonNode d) {
        return d != null && d.path("temperature").isNumber() && d.path("humidity").isNumber()
            && Double.isFinite(d.get("temperature").asDouble()) && Double.isFinite(d.get("humidity").asDouble())
            && d.get("humidity").asDouble() >= 0 && d.get("humidity").asDouble() <= 100
            && d.path("light").isIntegralNumber() && d.get("light").canConvertToInt()
            && d.get("light").asInt() >= 0 && d.get("light").asInt() <= 1023;
    }
    @PreDestroy public void stop() {
        if (reconnectTask != null) reconnectTask.cancel(false);
        try { if (client.isConnected()) client.disconnectForcibly(1000,1000); client.close(true); }
        catch (MqttException ex) { log.debug("MQTT close", ex); }
    }
}
