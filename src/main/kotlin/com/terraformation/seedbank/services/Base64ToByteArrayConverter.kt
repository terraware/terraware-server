package com.terraformation.seedbank.services

import io.micronaut.core.convert.ConversionContext
import io.micronaut.core.convert.TypeConverter
import java.util.Base64
import java.util.Optional
import javax.inject.Singleton

/** Converts base64-encoded binary configuration values to byte arrays. */
@Singleton
class Base64ToByteArrayConverter : TypeConverter<String, ByteArray> {
  private val decoder = Base64.getDecoder()!!

  override fun convert(
      obj: String, targetType: Class<ByteArray>, context: ConversionContext
  ): Optional<ByteArray> {
    return try {
      Optional.of(decoder.decode(obj))
    } catch (e: Exception) {
      context.reject(obj, e)
      // https://github.com/micronaut-projects/micronaut-core/issues/4762
      // Optional.empty()
      throw e
    }
  }
}
