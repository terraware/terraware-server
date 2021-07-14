package com.terraformation.backend.services

import java.util.Base64
import javax.annotation.ManagedBean
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding
import org.springframework.core.convert.converter.Converter

/**
 * Converts base64-encoded binary configuration values to byte arrays. This allows us to have
 * [ByteArray] fields in configuration classes in cases where we need binary configuration data
 * (encryption keys, etc.)
 */
@ConfigurationPropertiesBinding
@ManagedBean
class Base64ToByteArrayConverter : Converter<String, ByteArray> {
  private val decoder = Base64.getDecoder()!!

  override fun convert(obj: String): ByteArray {
    return try {
      decoder.decode(obj)
    } catch (e: Exception) {
      throw IllegalArgumentException(e)
    }
  }
}
