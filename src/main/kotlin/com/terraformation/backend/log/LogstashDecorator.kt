package com.terraformation.backend.log

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule
import com.terraformation.backend.db.GeometryModule
import net.logstash.logback.decorate.JsonFactoryDecorator

/**
 * Configures the Logstash encoder to serialize objects to JSON the same way the application does.
 * This allows objects in the key/value list of a log message to be inspected in Datadog.
 */
class LogstashDecorator : JsonFactoryDecorator {
  override fun decorate(factory: JsonFactory): JsonFactory {
    val objectMapper = factory.codec as ObjectMapper

    // Configure the ObjectMapper the same as the one that's used by the application, including
    // default Spring Boot modules and serialization features.

    objectMapper.registerModule(GeometryModule())
    objectMapper.registerModule(JavaTimeModule())
    objectMapper.registerModule(Jdk8Module())
    objectMapper.registerModule(KotlinModule.Builder().build())
    objectMapper.registerModule(ParameterNamesModule())

    objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    objectMapper.disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS)

    return factory
  }
}
