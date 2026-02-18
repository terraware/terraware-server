package com.terraformation.backend.api

import com.fasterxml.jackson.databind.module.SimpleModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class SerializerConfiguration {
  @Bean
  fun blankStringDeserializerModule(): SimpleModule {
    return SimpleModule("BlankStringDeserializer")
        .addDeserializer(String::class.java, BlankStringDeserializer())
  }

  @Bean
  fun arbitraryJsonObjectSerializerModule(): SimpleModule {
    return SimpleModule("JsonbSerializer")
        .addDeserializer(ArbitraryJsonObject::class.java, ArbitraryJsonObjectDeserializer())
        .addSerializer(ArbitraryJsonObject::class.java, ArbitraryJsonObjectSerializer())
  }
}
