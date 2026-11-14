package com.terraformation.backend.config

import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.thymeleaf.TemplateEngine
import org.thymeleaf.exceptions.TemplateProcessingException
import org.thymeleaf.standard.StandardDialect
import org.thymeleaf.standard.serializer.IStandardJavaScriptSerializer
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamWriteFeature
import tools.jackson.core.io.CharacterEscapes
import tools.jackson.core.io.SerializedString
import tools.jackson.core.json.JsonWriteFeature
import tools.jackson.databind.ObjectMapper

@Configuration
class ThymeleafConfig {
  companion object {
    @Bean
    @JvmStatic
    fun thymeleafJavaScriptSerializerConfigurer(
        objectMapper: ObjectProvider<ObjectMapper>
    ): BeanPostProcessor {
      // BeanPostProcessors are created early; resolving the ObjectMapper here would force it and
      // its dependencies to be created before all post-processors are registered.
      val writer by lazy {
        objectMapper
            .getObject()
            .writer()
            .with(JavaScriptCharacterEscapes)
            .with(JsonWriteFeature.ESCAPE_NON_ASCII)
            .without(StreamWriteFeature.AUTO_CLOSE_TARGET)
      }
      val serializer = IStandardJavaScriptSerializer { value, output ->
        try {
          writer.writeValue(output, value)
        } catch (e: JacksonException) {
          throw TemplateProcessingException("Could not serialize value as JavaScript", e)
        }
      }

      return object : BeanPostProcessor {
        override fun postProcessBeforeInitialization(bean: Any, beanName: String): Any {
          if (bean is TemplateEngine) {
            bean.dialects.filterIsInstance<StandardDialect>().forEach {
              it.javaScriptSerializer = serializer
            }
          }
          return bean
        }
      }
    }
  }

  private object JavaScriptCharacterEscapes : CharacterEscapes() {
    private val escapes =
        standardAsciiEscapesForJSON().apply {
          for (character in "<>&'/") {
            this[character.code] = ESCAPE_STANDARD
          }
        }

    override fun getEscapeCodesForAscii(): IntArray = escapes

    override fun getEscapeSequence(character: Int): SerializedString? = null
  }
}
