package com.terraformation.backend.api

import com.terraformation.backend.db.EnumFromReferenceTable
import jakarta.inject.Named
import org.jooq.exception.IOException
import org.springframework.core.convert.TypeDescriptor
import org.springframework.core.convert.converter.GenericConverter
import tools.jackson.databind.ObjectMapper

/**
 * Customizes how web request parameters are converted to an [EnumFromReferenceTableConverter],
 * matching our JSON serializer.
 */
@Named
class EnumFromReferenceTableConverter(private val objectMapper: ObjectMapper) : GenericConverter {

  // Specify the conversion pair: String to Enum that implements MyEnumInterface
  override fun getConvertibleTypes(): Set<GenericConverter.ConvertiblePair> {
    return setOf(
        GenericConverter.ConvertiblePair(String::class.java, EnumFromReferenceTable::class.java)
    )
  }

  override fun convert(source: Any?, sourceType: TypeDescriptor, targetType: TypeDescriptor): Any? {
    if (source == null) {
      return null
    }
    try {
      return objectMapper.convertValue(source, targetType.type)
    } catch (e: IOException) {
      return null
    }
  }
}
