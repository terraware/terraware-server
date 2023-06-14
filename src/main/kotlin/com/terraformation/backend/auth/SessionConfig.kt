package com.terraformation.backend.auth

import com.terraformation.backend.log.perClassLogger
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.converter.Converter
import org.springframework.core.convert.support.GenericConversionService
import org.springframework.core.serializer.DefaultDeserializer
import org.springframework.core.serializer.support.SerializingConverter
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.session.config.SessionRepositoryCustomizer
import org.springframework.session.jdbc.JdbcIndexedSessionRepository

@Configuration
class SessionConfig {
  /**
   * Make the default security context repository available as an injectable dependency so our
   * application code can manipulate session data.
   */
  @Bean fun securityContextRepository() = HttpSessionSecurityContextRepository()

  @Bean
  fun sessionRepositoryCustomizer(): SessionRepositoryCustomizer<JdbcIndexedSessionRepository> {
    val service = GenericConversionService()
    service.addConverter(Any::class.java, ByteArray::class.java, SerializingConverter())
    service.addConverter(ByteArray::class.java, Any::class.java, SessionInvalidatingConverter())

    return SessionRepositoryCustomizer { repository -> repository.setConversionService(service) }
  }

  /**
   * Deserializer for session data that treats deserialization failures as missing sessions rather
   * than as internal errors. With this class, we don't need to explicitly delete active login
   * sessions when we upgrade Spring and there's a backward-incompatible change to one of the
   * authentication objects that gets serialized as part of the session data. Without this class,
   * existing sessions that aren't serialization-compatible with the current version of the code
   * cause HTTP 500 responses.
   */
  class SessionInvalidatingConverter : Converter<ByteArray, Any> {
    private val deserializer = DefaultDeserializer(javaClass.classLoader)
    private val log = perClassLogger()

    override fun convert(source: ByteArray): Any? {
      return try {
        deserializer.deserialize(source.inputStream())
      } catch (e: Exception) {
        log.warn("Failed to deserialize session data; will start a new session")
        null
      }
    }
  }
}
