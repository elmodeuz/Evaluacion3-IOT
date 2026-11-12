#include <Arduino.h>
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <PubSubClient.h>
#include <DHT.h>
#include "mbedtls/md.h"
#include "mbedtls/sha256.h"
#include "secrets.h"   // THING_NAME, AWS_IOT_ENDPOINT y certificados

// Red virtual de Wokwi
const char* ssid = "Wokwi-GUEST";
const char* password = "";

// AWS IoT Core (MQTT sobre TLS)
const int mqtt_port = 8883;

#define DHTPIN 4
#define DHTTYPE DHT22
#define ACTUATOR_PIN 2

DHT dht(DHTPIN, DHTTYPE);
WiFiClientSecure espClient;
PubSubClient client(espClient);

const char* topic_telemetry = "inacap/ti3042/telemetria";
const char* topic_control = "inacap/ti3042/control";

// Buffer Offline para contingencia (Criterios 3.1.1.4 y 3.1.4.13)
struct TelemetryRecord {
  float temp;
  float hum;
  unsigned long timestamp;
};
const int BUFFER_MAX = 15;
TelemetryRecord offlineBuffer[BUFFER_MAX];
int bufferCount = 0;

unsigned long lastReadTime = 0;
const unsigned long readInterval = 3000;
unsigned long lastReconnectAttempt = 0;

// Verificación de integridad con hash SHA-256 (ISO 27000 / Criterio 3.1.1.2)
String calculateSHA256(String input) {
  byte shaResult[32];
  mbedtls_sha256_context ctx;
  mbedtls_sha256_init(&ctx);
  mbedtls_sha256_starts(&ctx, 0);
  mbedtls_sha256_update(&ctx, (const unsigned char*)input.c_str(), input.length());
  mbedtls_sha256_finish(&ctx, shaResult);
  mbedtls_sha256_free(&ctx);

  String hashStr = "";
  for (int i = 0; i < 32; i++) {
    char buf[3];
    sprintf(buf, "%02x", shaResult[i]);
    hashStr += buf;
  }
  return hashStr;
}
// Recepción de órdenes para el actuador (Criterios 3.1.3.8 y 3.1.3.9)
void callback(char* topic, byte* payload, unsigned int length) {
  String message = "";
  for (unsigned int i = 0; i < length; i++) {
    message += (char)payload[i];
  }
  Serial.println("[SERIAL RX]: " + message);

  if (message == "ACTUATOR_ON") {
    digitalWrite(ACTUATOR_PIN, HIGH);
    Serial.println("[ACTUADOR] Estado: ENCENDIDO");
  } else if (message == "ACTUATOR_OFF") {
    digitalWrite(ACTUATOR_PIN, LOW);
    Serial.println("[ACTUADOR] Estado: APAGADO");
  }
}

void enviarTrama(float t, float h, bool isSync = false) {
  String dataJson = "{\"temp\":" + String(t, 1) + ",\"hum\":" + String(h, 1) + ",\"sync\":" + (isSync ? "true" : "false") + "}";
  String hashIntegridad = calculateSHA256(dataJson);
  String packet = "{\"data\":" + dataJson + ",\"sha256\":\"" + hashIntegridad + "\"}";
  
  client.publish(topic_telemetry, packet.c_str());
  Serial.println("[SERIAL TX]: " + packet);
}

boolean reconnect() {
  if (client.connect(THING_NAME)) {
    Serial.println("[RED] Conectado exitosamente a AWS IoT Core");
    client.subscribe(topic_control);
    
    // Si había datos guardados offline, los sincroniza (Criterio 3.1.2.7)
    if (bufferCount > 0) {
      Serial.println("[SYNC] Transmitiendo lecturas acumuladas fuera de linea...");
      for (int i = 0; i < bufferCount; i++) {
        enviarTrama(offlineBuffer[i].temp, offlineBuffer[i].hum, true);
        delay(80);
      }
      bufferCount = 0;
      Serial.println("[SYNC] Buffer sincronizado.");
    }
    return true;
  }
  Serial.print("[RED] Fallo conexion AWS, estado MQTT = ");
  Serial.println(client.state());
  return false;
}

void setup() {
  Serial.begin(115200);
  pinMode(ACTUATOR_PIN, OUTPUT);
  digitalWrite(ACTUATOR_PIN, LOW);
  dht.begin();

  Serial.println("[INIT] Conectando a Wokwi-GUEST...");
  WiFi.begin(ssid, password, 6);
  while (WiFi.status() != WL_CONNECTED) {
    delay(250);
    Serial.print(".");
  }
  Serial.println("\n[RED] WiFi conectado.");

  // Certificados TLS para AWS IoT Core
  espClient.setCACert(AWS_CERT_CA);
  espClient.setCertificate(AWS_CERT_CRT);
  espClient.setPrivateKey(AWS_CERT_PRIVATE);

  client.setServer(AWS_IOT_ENDPOINT, mqtt_port);
  client.setBufferSize(512);
  client.setCallback(callback);
}

void loop() {
  unsigned long now = millis();

  // Reconexión automática no bloqueante (Criterios 3.1.1.3 y 3.1.3.10)
  if (!client.connected()) {
    if (now - lastReconnectAttempt > 5000) {
      lastReconnectAttempt = now;
      reconnect();
    }
  } else {
    client.loop();
  }

  // Muestreo periódico sin pausar la CPU con delay()
  if (now - lastReadTime >= readInterval) {
    lastReadTime = now;
    float h = dht.readHumidity();
    float t = dht.readTemperature();

    if (!isnan(h) && !isnan(t)) {
      if (client.connected()) {
        enviarTrama(t, h);
      } else {
        // Almacenamiento local temporal si se pierde la red (Criterio 3.1.1.4)
        if (bufferCount < BUFFER_MAX) {
          offlineBuffer[bufferCount] = {t, h, now};
          bufferCount++;
          Serial.println("[OFFLINE] Guardado en buffer local: " + String(bufferCount) + "/" + String(BUFFER_MAX));
        }
      }
    }
  }
}