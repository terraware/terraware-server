package com.terraformation.backend.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyName
import com.fasterxml.jackson.databind.introspect.Annotated
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector
import com.fasterxml.jackson.databind.module.SimpleModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

class CustomAnnotationIntrospector : JacksonAnnotationIntrospector() {
  override fun findDeserializer(a: Annotated?): Any {
    val customAnnotation = a?.getAnnotation(AllowBlankString::class.java)
    return if (customAnnotation != null) {
      return super.findDeserializer(a)
    } else {
      return super.findDeserializer(a)
    }
  }
}

@Configuration
class SerializerConfiguration {
  @Bean
  fun blankStringDeserializerModule(): SimpleModule {
    val mapper = ObjectMapper()
    val module = SimpleModule("BlankStringDeserializer")
    module.addDeserializer(String::class.java, BlankStringDeserializer())
    mapper.registerModule(module)
    mapper.setAnnotationIntrospector(CustomAnnotationIntrospector())
    return module
  }

  @Bean
  fun arbitraryJsonObjectSerializerModule(): SimpleModule {
    return SimpleModule("JsonbSerializer")
        .addDeserializer(ArbitraryJsonObject::class.java, ArbitraryJsonObjectDeserializer())
        .addSerializer(ArbitraryJsonObject::class.java, ArbitraryJsonObjectSerializer())
  }
}
