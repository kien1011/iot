#include <ESP8266WiFi.h>
#include <PubSubClient.h>
#include <ArduinoJson.h>
#include <DHT.h>

const char* WIFI_SSID = "pompon";
const char* WIFI_PASSWORD = "10112005";

const char* MQTT_HOST = "172.20.10.3";
const uint16_t MQTT_PORT = 1235;

const char* MQTT_USER = "nguyentrungkien";
const char* MQTT_PASSWORD = "b23dccn464";

const char* MQTT_CLIENT_ID = "esp8266-iot-node1";

const char* TOPIC_SENSOR_DATA = "iot/sensors/data";
const char* TOPIC_DEVICE_COMMAND = "iot/devices/command";
const char* TOPIC_DEVICE_STATUS = "iot/devices/status";

#define DHT_PIN D4
#define DHT_TYPE DHT11
#define LDR_PIN A0
#define DEVICE_1_PIN D1
#define DEVICE_2_PIN D2

const unsigned long SENSOR_INTERVAL_MS = 2000UL;
unsigned long lastSensorPublishMs = 0;

WiFiClient wifiClient;
PubSubClient mqttClient(wifiClient);
DHT dhtSensor(DHT_PIN, DHT_TYPE);

void connectWiFi() {
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
  }
  Serial.print("\nWiFi OK | IP: ");
  Serial.println(WiFi.localIP());
}

void connectMQTT() {
  while (!mqttClient.connected()) {
    if (mqttClient.connect(MQTT_CLIENT_ID, MQTT_USER, MQTT_PASSWORD)) {
      Serial.println("MQTT OK");
      mqttClient.subscribe(TOPIC_DEVICE_COMMAND);
    } else {
      delay(2000);
    }
  }
}

void handleDeviceCommand(String command) {
  command.trim();
  int separatorIndex = command.indexOf(':');
  if (separatorIndex < 1) return;
  int deviceId = command.substring(0, separatorIndex).toInt();
  String action = command.substring(separatorIndex + 1);
  action.trim();
  action.toUpperCase();
  if (deviceId != 1 && deviceId != 2) return;
  if (action != "ON" && action != "OFF") return;
  bool turnOn = (action == "ON");
  digitalWrite(deviceId == 1 ? DEVICE_1_PIN : DEVICE_2_PIN, turnOn ? HIGH : LOW);
  StaticJsonDocument<64> statusDoc;
  statusDoc["deviceId"] = deviceId;
  statusDoc["status"] = turnOn ? "ON" : "OFF";
  char statusBuffer[64];
  serializeJson(statusDoc, statusBuffer, sizeof(statusBuffer));
  mqttClient.publish(TOPIC_DEVICE_STATUS, statusBuffer);
  Serial.println(statusBuffer);
}

void mqttCallback(char* topic, byte* payload, unsigned int length) {
  String message;
  for (unsigned int i = 0; i < length; i++) {
    message += (char)payload[i];
  }
  if (String(topic) == TOPIC_DEVICE_COMMAND) {
    handleDeviceCommand(message);
  }
}

void publishSensorData() {
  float temperature = dhtSensor.readTemperature();
  float humidity = dhtSensor.readHumidity();
  int lightValue = analogRead(LDR_PIN);
  if (isnan(temperature) || isnan(humidity)) {
    Serial.println("DHT11 ERROR");
    return;
  }
  StaticJsonDocument<128> sensorDoc;
  sensorDoc["temperature"] = temperature;
  sensorDoc["humidity"] = humidity;
  sensorDoc["light"] = lightValue;
  char sensorBuffer[128];
  serializeJson(sensorDoc, sensorBuffer, sizeof(sensorBuffer));
  mqttClient.publish(TOPIC_SENSOR_DATA, sensorBuffer);
  Serial.println(sensorBuffer);
}

void setup() {
  Serial.begin(115200);
  pinMode(DEVICE_1_PIN, OUTPUT);
  pinMode(DEVICE_2_PIN, OUTPUT);
  digitalWrite(DEVICE_1_PIN, LOW);
  digitalWrite(DEVICE_2_PIN, LOW);
  dhtSensor.begin();
  mqttClient.setServer(MQTT_HOST, MQTT_PORT);
  mqttClient.setCallback(mqttCallback);
  connectWiFi();
  connectMQTT();
}

void loop() {
  if (WiFi.status() != WL_CONNECTED) {
    connectWiFi();
  }
  if (!mqttClient.connected()) {
    connectMQTT();
  }
  mqttClient.loop();
  unsigned long currentMillis = millis();
  if (currentMillis - lastSensorPublishMs >= SENSOR_INTERVAL_MS) {
    lastSensorPublishMs = currentMillis;
    publishSensorData();
  }
}