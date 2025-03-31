package com.terraformation.backend.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.terraformation.backend.db.ParameterizedEnum
import org.jooq.exception.IOException
import org.springframework.core.convert.TypeDescriptor
import org.springframework.core.convert.converter.GenericConverter
import org.springframework.lang.Nullable
import org.springframework.stereotype.Component

/** Customizes how request params are parsed to an enum, matching our JSON serializer. */
@Component
class JacksonEnumConverter(private val objectMapper: ObjectMapper) : GenericConverter {

  // Specify the conversion pair: String to Enum that implements MyEnumInterface
  override fun getConvertibleTypes(): Set<GenericConverter.ConvertiblePair> {
    return setOf(
        GenericConverter.ConvertiblePair(String::class.java, ParameterizedEnum::class.java))
  }

  override fun convert(
      @Nullable source: Any?,
      sourceType: TypeDescriptor,
      targetType: TypeDescriptor
  ): Any? {
    if (source == null) {
      return null
    }
    try {
      return objectMapper.readValue("\"" + source + "\"", targetType.getType())
    } catch (e: IOException) {
      return null
    }
  }
}
