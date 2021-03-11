package com.terraformation.seedbank.mqtt

import com.terraformation.seedbank.config.TerrawareServerConfig
import com.terraformation.seedbank.services.perClassLogger
import javax.annotation.ManagedBean
import javax.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationStartedEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener

/**
 * Manages communication with the MQTT broker.
 *
 * If MQTT is enabled, attempts to keep a connection to the broker open. The MQTT client library
 * we're using has some bugs and less-than-optimal behavior when the broker connection fails, so
 * this class does its own retry management.
 *
 * Parses incoming MQTT messages using a [MessageParser] and, if they're well-formed and relevant to
 * the server, publishes them as [IncomingMqttMessage] instances on the local application event bus.
 * Event listeners react to those messages and trigger appropriate business logic.
 */
@ConditionalOnProperty(TerrawareServerConfig.MQTT_ENABLED_PROPERTY)
@ManagedBean
class MqttClientManager(
    config: TerrawareServerConfig,
    private val jwtGenerator: JwtGenerator,
    private val parser: MessageParser,
    private val publisher: ApplicationEventPublisher,
) : MqttCallbackExtended {
  private val address =
      config.mqtt.address ?: throw IllegalArgumentException("terraware.mqtt.address not set")
  private val clientId =
      config.mqtt.clientId ?: throw IllegalArgumentException("terraware.mqtt.clientId not set")
  private val password = config.mqtt.password?.toCharArray()
  private val topicFilter = listOfNotNull(config.mqtt.topicPrefix, "#").joinToString("/")
  private val retryIntervalMillis: Long = config.mqtt.connectRetryIntervalMillis

  private var client: MqttClient? = null
  private var connectJob: Job? = null
  private val persistence = MemoryPersistence()

  private val log = perClassLogger()

  @PreDestroy
  fun shutdown() {
    connectJob?.cancel()

    if (client?.isConnected == true) {
      client?.disconnect()
    }

    client?.close()
  }

  @EventListener
  fun connectAtApplicationStart(@Suppress("UNUSED_PARAMETER") event: ApplicationStartedEvent) {
    startConnecting()
  }

  private fun startConnecting() {
    synchronized(this) {
      if (connectJob != null) {
        log.warn("Ignoring redundant attempt to connect to broker")
        return
      }

      val connectOptions = MqttConnectOptions()

      // Paho's automatic reconnect appears to be pretty buggy with WebSocket connections; we will
      // manage our own reconnect logic instead.
      connectOptions.isAutomaticReconnect = false

      connectOptions.isCleanSession = false
      connectOptions.userName = clientId

      // The Paho client gets into a screwed-up state if the initial connection attempt fails, so
      // manage connection retries in the background and attempt to destroy the client after each
      // failed connection attempt. https://github.com/eclipse/paho.mqtt.java/issues/481
      connectJob =
          GlobalScope.launch {
            log.debug("Connecting to MQTT broker $address with username $clientId")

            while (client == null) {
              val newClient = MqttClient("$address", clientId, persistence)

              try {
                connectOptions.password =
                    password ?: jwtGenerator.generateMqttToken(clientId, topicFilter).toCharArray()

                newClient.setCallback(this@MqttClientManager)
                newClient.connect(connectOptions)
                newClient.subscribe(topicFilter)

                connectJob = null
                client = newClient
                break
              } catch (e: MqttException) {
                log.warn("Failed to connect to MQTT broker ${newClient.serverURI} (will retry): $e")
              } catch (e: CancellationException) {
                // Background job was explicitly canceled; no need to do anything.
                break
              } catch (e: Exception) {
                log.error("Unexpected exception connecting to MQTT broker", e)
              }

              // The client can be in a half-connected state after a connect failure
              try {
                newClient.disconnect()
              } catch (e: MqttException) {
                // Swallow it
              }
              try {
                newClient.close()
              } catch (e: MqttException) {
                // Swallow it
              }

              delay(retryIntervalMillis)
            }
          }
    }
  }

  override fun connectionLost(cause: Throwable) {
    log.warn("Lost connection to MQTT broker; will try to reconnect")

    // Destroy the current client and create a new one. Can't call methods on the client from within
    // a callback, so do it in the background.
    val oldClient = client
    client = null

    GlobalScope.launch {
      if (oldClient != null) {
        try {
          oldClient.disconnect()
        } catch (e: MqttException) {
          log.debug("Got exception when disconnecting client (expected): $e")
        }

        try {
          oldClient.close(true)
        } catch (e: MqttException) {
          log.debug("Got exception when closing client: $e")
        }
      }

      startConnecting()
    }
  }

  override fun messageArrived(topic: String, message: MqttMessage) {
    val incomingMessage = parser.parse(topic, message)
    if (incomingMessage != null) {
      publisher.publishEvent(incomingMessage)
    }
  }

  override fun deliveryComplete(token: IMqttDeliveryToken) {
    // No-op
  }

  override fun connectComplete(reconnect: Boolean, serverURI: String) {
    log.info("Connected to MQTT broker $serverURI")
  }
}
