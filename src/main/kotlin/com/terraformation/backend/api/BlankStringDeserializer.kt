package com.terraformation.backend.api

import tools.jackson.core.JsonParser
import tools.jackson.databind.BeanProperty
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.ValueDeserializer
import tools.jackson.databind.deser.jdk.StringDeserializer

/**
 * Deserializes blank or empty strings in JSON objects as null. Doing this as part of JSON
 * deserialization means UI code doesn't have to worry about checking whether optional text fields
 * have been left blank.
 *
 * Does not affect the values of [ArbitraryJsonObject] payload fields; those are treated as opaque
 * and can contain empty strings.
 *
 * Does not affect fields annotated with [AllowBlankString].
 */
class BlankStringDeserializer : ValueDeserializer<String?>() {
  override fun createContextual(
      ctxt: DeserializationContext,
      property: BeanProperty?,
  ): ValueDeserializer<*> {
    return if (property?.getAnnotation(AllowBlankString::class.java) != null) {
      StringDeserializer.instance
    } else {
      this
    }
  }

  override fun deserialize(parser: JsonParser, ctxt: DeserializationContext): String? {
    return StringDeserializer.instance.deserialize(parser, ctxt)?.ifBlank { null }
  }
}
