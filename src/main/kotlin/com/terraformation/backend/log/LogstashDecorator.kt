package com.terraformation.backend.log

import com.terraformation.backend.db.GeometryModule
import net.logstash.logback.decorate.MapperBuilderDecorator
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.cfg.DateTimeFeature
import tools.jackson.databind.cfg.MapperBuilder
import tools.jackson.module.kotlin.KotlinModule

/**
 * Configures the Logstash encoder to serialize objects to JSON the same way the application does.
 * This allows objects in the key/value list of a log message to be inspected in Datadog.
 */
class LogstashDecorator<B : MapperBuilder<ObjectMapper, B>> :
    MapperBuilderDecorator<ObjectMapper, B> {
  override fun decorate(builder: B): B {
    // Configure the ObjectMapper the same as the one that's used by the application, including
    // default Spring Boot modules and serialization features.

    builder.addModule(GeometryModule())
    builder.addModule(KotlinModule.Builder().build())

    builder.disable(DateTimeFeature.WRITE_DURATIONS_AS_TIMESTAMPS)

    return builder
  }
}
