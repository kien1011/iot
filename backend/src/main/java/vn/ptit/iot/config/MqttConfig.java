package vn.ptit.iot.config;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
@Configuration
public class MqttConfig {
    @Bean(destroyMethod="") MqttAsyncClient mqttClient(@Value("${app.mqtt.uri}") String uri,
                                                     @Value("${app.mqtt.client-id}") String id) throws MqttException {
        MqttAsyncClient client = new MqttAsyncClient(uri, id, new MemoryPersistence());
        DisconnectedBufferOptions buffer = new DisconnectedBufferOptions();
        buffer.setBufferEnabled(false);
        client.setBufferOpts(buffer);
        return client;
    }
}
