package vn.ptit.iot.config;

import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

@Configuration
public class MqttConfig {
    @Bean(destroyMethod = "close") public MqttAsyncClient mqttClient(@Value("${mqtt.url}") String url,
            @Value("${mqtt.client-id}") String clientId) throws MqttException {
        var client = new MqttAsyncClient(url, clientId, new MemoryPersistence());
        var buffer = new DisconnectedBufferOptions();
        buffer.setBufferEnabled(false);
        client.setBufferOpts(buffer);
        return client;
    }
}
