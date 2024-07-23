package com.terraformation.backend.api

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.deser.std.StringDeserializer

/**
 * Deserializes blank or empty strings in JSON objects as null. Doing this as part of JSON
 * deserialization means UI code doesn't have to worry about checking whether optional text fields
 * have been left blank.
 *
 * Does not affect the values of [ArbitraryJsonObject] payload fields; those are treated as opaque
 * and can contain empty strings.
 */
class BlankStringDeserializer : JsonDeserializer<String?>() {
  override fun deserialize(parser: JsonParser, ctxt: DeserializationContext): String? {
    val clazz = handledType()
    val allowBlankString =
        if (clazz != null) {
          val annotated = ctxt.config.introspectClassAnnotations(clazz).classInfo
          val annotations = annotated.getAnnotations()
          annotations.has(AllowBlankString::class.java)
        } else {
          false
        }

    val deserialized = StringDeserializer.instance.deserialize(parser, ctxt)
    return if (allowBlankString) {
      deserialized
    } else {
      deserialized?.ifBlank { null }
    }
  }
}
