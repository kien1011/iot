package vn.ptit.iot.mqtt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import org.slf4j.*;
import org.springframework.stereotype.Component;
import vn.ptit.iot.entity.DeviceState;
import vn.ptit.iot.service.*;

@Component
public class MqttMessageHandler {
    private static final Logger log = LoggerFactory.getLogger(MqttMessageHandler.class);
    private final ObjectMapper json;
    private final SensorService sensors;
    private final DeviceService devices;
    public MqttMessageHandler(ObjectMapper json, SensorService sensors, DeviceService devices) {
        this.json = json; this.sensors = sensors; this.devices = devices;
    }
    public void receive(String topic, String payload) {
        final JsonNode packet;
        try { packet = json.readTree(payload); }
        catch (JsonProcessingException ex) { log.warn("Ignoring invalid MQTT JSON"); return; }
        if (packet == null || !packet.isObject()) return;
        if (topic.equals("iot/sensors/data")) {
            var t = packet.path("temperature"); var h = packet.path("humidity"); var l = packet.path("light");
            if (!t.isNumber() || !h.isNumber() || !l.isIntegralNumber() || !l.canConvertToInt()
                    || !Double.isFinite(t.doubleValue()) || !Double.isFinite(h.doubleValue())
                    || h.doubleValue() < 0 || h.doubleValue() > 100 || l.intValue() < 0 || l.intValue() > 1023) {
                log.warn("Ignoring unusable sensor packet"); return;
            }
            sensors.record(t.doubleValue(), h.doubleValue(), l.intValue());
        } else if (topic.equals("iot/devices/status")) {
            var id = packet.path("deviceId"); var status = packet.path("status").asText();
            if (!id.isIntegralNumber() || (id.intValue() != 1 && id.intValue() != 2)
                    || (!status.equals("ON") && !status.equals("OFF"))) return;
            devices.acknowledge(id.intValue(), DeviceState.valueOf(status));
        }
    }
}
