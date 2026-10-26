package com.terraformation.backend.log

import com.terraformation.backend.db.GeometryModule
import net.logstash.logback.decorate.MapperBuilderDecorator
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.cfg.MapperBuilder

/**
 * Configures the Logstash encoder to serialize objects to JSON the same way the application does.
 * This allows objects in the key/value list of a log message to be inspected in Datadog.
 */
class LogstashDecorator<M : ObjectMapper, B : MapperBuilder<M, B>> : MapperBuilderDecorator<M, B> {
  override fun decorate(mapperBuilder: B): B {
    return mapperBuilder.addModule(GeometryModule())
  }
}
