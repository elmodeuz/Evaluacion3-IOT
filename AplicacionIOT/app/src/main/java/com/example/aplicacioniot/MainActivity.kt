package com.example.aplicacioniot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.eclipse.paho.client.mqttv3.*
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var mqttClient: MqttClient
    private val brokerUri = "tcp://broker.hivemq.com:1883"
    private val topicTelemetry = "inacap/ti3042/telemetria"
    private val topicControl = "inacap/ti3042/control"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFFF5F5F5)
                ) {
                    IoTMonitoringScreen(
                        onSendCommand = { cmd -> sendCommand(cmd) },
                        onInitMqtt = { onDataReceived, onStatusChanged ->
                            initMqtt(onDataReceived, onStatusChanged)
                        }
                    )
                }
            }
        }
    }

    private fun initMqtt(
        onDataReceived: (String, String, Boolean) -> Unit,
        onStatusChanged: (String, Color) -> Unit
    ) {
        val clientId = "AndroidClient_" + System.currentTimeMillis()
        mqttClient = MqttClient(brokerUri, clientId, null)

        val options = MqttConnectOptions().apply {
            isAutomaticReconnect = true
            isCleanSession = true
            connectionTimeout = 10
            keepAliveInterval = 20
        }

        mqttClient.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                try {
                    mqttClient.subscribe(topicTelemetry)
                    onStatusChanged("Conectado al broker MQTT", Color(0xFF388E3C))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun connectionLost(cause: Throwable?) {
                onStatusChanged("Conexión perdida. Reconectando...", Color.Red)
            }

            override fun messageArrived(topic: String?, message: MqttMessage?) {
                val payload = message?.toString() ?: return
                try {
                    val root = JSONObject(payload)
                    val dataObj = root.getJSONObject("data")
                    val receivedHash = root.getString("sha256")

                    val temp = dataObj.getDouble("temp")
                    val hum = dataObj.getDouble("hum")
                    val sync = dataObj.getBoolean("sync")

                    // Reconstrucción con 1 decimal idéntico a String(t, 1) del firmware
                    val formattedTemp = String.format(Locale.US, "%.1f", temp)
                    val formattedHum = String.format(Locale.US, "%.1f", hum)
                    val canonicalDataString = "{\"temp\":$formattedTemp,\"hum\":$formattedHum,\"sync\":$sync}"

                    val calculatedHash = calculateSHA256(canonicalDataString)
                    val isValid = calculatedHash.equals(receivedHash, ignoreCase = true)

                    onDataReceived(formattedTemp, formattedHum, isValid)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })

        Thread {
            try {
                mqttClient.connect(options)
            } catch (e: Exception) {
                onStatusChanged("Error al conectar con broker", Color.Red)
            }
        }.start()
    }

    private fun sendCommand(command: String) {
        Thread {
            try {
                if (::mqttClient.isInitialized && mqttClient.isConnected) {
                    val message = MqttMessage(command.toByteArray()).apply { qos = 1 }
                    mqttClient.publish(topicControl, message)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun calculateSHA256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (::mqttClient.isInitialized && mqttClient.isConnected) {
                mqttClient.disconnect()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

@Composable
fun IoTMonitoringScreen(
    onSendCommand: (String) -> Unit,
    onInitMqtt: ((String, String, Boolean) -> Unit, (String, Color) -> Unit) -> Unit
) {
    var temperature by remember { mutableStateOf("--") }
    var humidity by remember { mutableStateOf("--") }
    var securityStatus by remember { mutableStateOf("Conectando al broker...") }
    var securityColor by remember { mutableStateOf(Color(0xFFF57C00)) }

    LaunchedEffect(Unit) {
        onInitMqtt(
            { temp, hum, isValid ->
                temperature = temp
                humidity = hum
                if (isValid) {
                    securityStatus = "Integridad ISO 27000: Verificada (SHA-256 Coincide)"
                    securityColor = Color(0xFF388E3C)
                } else {
                    securityStatus = "Alerta: Datos alterados o corruptos"
                    securityColor = Color.Red
                }
            },
            { status, color ->
                securityStatus = status
                securityColor = color
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Monitor Ambiental IoT",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF212121)
        )
        Text(
            text = "INACAP - TI3042",
            fontSize = 14.sp,
            color = Color.Gray,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "Temperatura: $temperature °C",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFD32F2F)
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Humedad: $humidity %",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1976D2)
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = securityStatus,
                    fontSize = 13.sp,
                    color = securityColor
                )
            }
        }

        Spacer(modifier = Modifier.height(30.dp))

        Button(
            onClick = { onSendCommand("ACTUATOR_ON") },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF388E3C)),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text("Encender Actuador (LED/Relé)", fontSize = 16.sp)
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = { onSendCommand("ACTUATOR_OFF") },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text("Apagar Actuador", fontSize = 16.sp)
        }
    }
}