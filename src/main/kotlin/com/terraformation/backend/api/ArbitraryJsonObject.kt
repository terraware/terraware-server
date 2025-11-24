package com.terraformation.backend.api

import org.jooq.JSONB
import tools.jackson.core.JsonGenerator
import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.ValueDeserializer
import tools.jackson.databind.ValueSerializer
import tools.jackson.databind.node.ObjectNode

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
class ArbitraryJsonObjectDeserializer : ValueDeserializer<ArbitraryJsonObject?>() {
  override fun deserialize(parser: JsonParser, ctxt: DeserializationContext): ArbitraryJsonObject? {
    val objectNode = parser.readValueAs(ObjectNode::class.java)
    return JSONB.jsonbOrNull(objectNode?.toString())
  }
}

/**
 * "Serializes" jOOQ JSONB wrappers to JSON. In reality, this just unwraps the existing JSON
 * representation contained in the JSONB object.
 */
class ArbitraryJsonObjectSerializer : ValueSerializer<ArbitraryJsonObject?>() {
  override fun serialize(
      value: ArbitraryJsonObject?,
      gen: JsonGenerator,
      serializers: SerializationContext,
  ) {
    if (value == null) {
      gen.writeNull()
    } else {
      gen.writeRawValue(value.data())
    }
  }
}
