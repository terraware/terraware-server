package com.terraformation.backend.api

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.node.ObjectNode
import org.jooq.JSONB

/**
 * Underlying data type for API fields that accept arbitrary client-supplied JSON objects. Payload
 * classes should use this typealias rather than the underlying type so as to make the intended
 * behavior clearer.
 */
typealias ArbitraryJsonObject = JSONB

/**
 * Deserializes arbitrary JSON objects to jOOQ JSONB wrappers. We use JSONB instead of [Map] objects
 * because we want to preserve client-supplied JSON exactly as is rather than applying
 * transformations such as mapping empty strings to null.
 */
class ArbitraryJsonObjectDeserializer : JsonDeserializer<ArbitraryJsonObject?>() {
  override fun deserialize(parser: JsonParser, ctxt: DeserializationContext): ArbitraryJsonObject? {
    val objectNode = parser.readValueAs(ObjectNode::class.java)
    return JSONB.jsonbOrNull(objectNode?.toString())
  }
}

/**
 * "Serializes" jOOQ JSONB wrappers to JSON. In reality, this just unwraps the existing JSON
 * representation contained in the JSONB object.
 */
class ArbitraryJsonObjectSerializer : JsonSerializer<ArbitraryJsonObject?>() {
  override fun serialize(
      value: ArbitraryJsonObject?,
      gen: JsonGenerator,
      serializers: SerializerProvider,
  ) {
    if (value == null) {
      gen.writeNull()
    } else {
      gen.writeRawValue(value.data())
    }
  }
}
