package com.terraformation.seedbank.services

import io.micronaut.core.convert.ConversionContext
import io.micronaut.core.convert.TypeConverter
import java.util.Base64
import java.util.Optional
import javax.inject.Singleton

@Singleton
class Base64ToByteArrayConverter : TypeConverter<String, ByteArray> {
  private val decoder = Base64.getDecoder()!!

  override fun convert(
      obj: String, targetType: Class<ByteArray>, context: ConversionContext
  ): Optional<ByteArray> {
    return Optional.of(decoder.decode(obj))
  }
}
