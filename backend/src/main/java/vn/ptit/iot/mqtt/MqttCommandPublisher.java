package vn.ptit.iot.mqtt;

import java.nio.charset.StandardCharsets;
import org.eclipse.paho.client.mqttv3.*;
import org.slf4j.*;
import org.springframework.stereotype.Component;
import vn.ptit.iot.entity.DeviceState;

@Component
public class MqttCommandPublisher {
    private static final Logger log = LoggerFactory.getLogger(MqttCommandPublisher.class);
    private final MqttAsyncClient client;
    public MqttCommandPublisher(MqttAsyncClient client) { this.client = client; }
    public void publish(int deviceId, DeviceState action) {
        if (!client.isConnected()) return; // The existing pending request will time out normally.
        try {
            client.publish("iot/devices/command", (deviceId + ":" + action).getBytes(StandardCharsets.UTF_8),
                0, false, null, new IMqttActionListener() {
                    @Override public void onSuccess(IMqttToken token) { /* Publish is not a hardware ACK. */ }
                    @Override public void onFailure(IMqttToken token, Throwable error) { log.warn("MQTT command send failed", error); }
                });
        } catch (MqttException ex) {
            log.warn("MQTT command was not sent; waiting for normal timeout", ex);
        }
    }
}
