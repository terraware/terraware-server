package com.terraformation.seedbank.mqtt

import com.terraformation.seedbank.config.TerrawareServerConfig
import com.terraformation.seedbank.services.perClassLogger
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@ConditionalOnProperty("terraware.mqtt.enabled")
@Configuration
class MqttClientConfiguration {
  private val log = perClassLogger()

  @Bean(destroyMethod = "disconnect")
  fun mqttClient(
      config: TerrawareServerConfig,
      jwtGenerator: JwtGenerator,
      callback: MessageHandler,
  ): MqttClient {
    val address =
        config.mqtt.address ?: throw IllegalArgumentException("terraware.mqtt.address not set")
    val clientId =
        config.mqtt.clientId ?: throw IllegalArgumentException("terraware.mqtt.clientId not set")

    val persistence = MemoryPersistence()
    val client = MqttClient("$address", clientId, persistence)

    val topicFilter = listOfNotNull(config.mqtt.topicPrefix, "#").joinToString("/")

    val connectOptions = MqttConnectOptions()
    connectOptions.isAutomaticReconnect = true
    connectOptions.isCleanSession = false
    connectOptions.userName = clientId
    connectOptions.password = jwtGenerator.generateMqttToken(clientId, topicFilter).toCharArray()

    log.debug("Connecting to MQTT broker $address with username $clientId")

    client.setCallback(callback)
    client.connect(connectOptions)
    client.subscribe(topicFilter)

    return client
  }
}
