package vn.ptit.iot.mqtt;
import java.nio.charset.StandardCharsets;
import org.eclipse.paho.client.mqttv3.*;
import org.slf4j.*;
import org.springframework.stereotype.Component;
import vn.ptit.iot.entity.DeviceStatus;
@Component
public class CommandPublisher {
    private static final Logger log = LoggerFactory.getLogger(CommandPublisher.class);
    private final MqttAsyncClient client;
    public CommandPublisher(MqttAsyncClient client) { this.client=client; }
    public void publish(int deviceId, DeviceStatus action) {
        if (!client.isConnected()) return; // No offline command queue or retry.
        try {
            client.publish("iot/devices/command", (deviceId + ":" + action.name()).getBytes(StandardCharsets.UTF_8), 0, false);
        } catch (MqttException ex) { log.warn("Command not published; its normal timeout remains active: {}", ex.getMessage()); }
    }
}
