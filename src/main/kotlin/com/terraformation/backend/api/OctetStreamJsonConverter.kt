package com.terraformation.backend.api

import jakarta.inject.Named
import org.springframework.http.HttpInputMessage
import org.springframework.http.HttpOutputMessage
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.converter.HttpMessageNotReadableException
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.annotation.JsonDeserialize

/**
 * Workaround for a bug in swagger-ui that causes it to leave off the content type of JSON parts of
 * `multipart/form-data` POST requests. This causes the parts to be treated as
 * `application/octet-stream` (the default content type) and Spring has no way of knowing that it's
 * really JSON.
 *
 * To prevent this converter from unintentionally converting objects that should really be treated
 * as binary data, it only works if the target class is annotated with [JsonDeserialize].
 */
@Named
class OctetStreamJsonConverter(private val objectMapper: ObjectMapper) : HttpMessageConverter<Any> {
  override fun canRead(clazz: Class<*>, mediaType: MediaType?): Boolean {
    return mediaType == MediaType.APPLICATION_OCTET_STREAM &&
        clazz.isAnnotationPresent(JsonDeserialize::class.java)
  }

  override fun canWrite(clazz: Class<*>, mediaType: MediaType?): Boolean {
    return false
  }

  override fun getSupportedMediaTypes(): List<MediaType> {
    return listOf(MediaType.APPLICATION_OCTET_STREAM)
  }

  override fun read(clazz: Class<out Any>, inputMessage: HttpInputMessage): Any {
    try {
      return objectMapper.readValue(inputMessage.body, clazz)
    } catch (e: JacksonException) {
      throw HttpMessageNotReadableException("Unable to deserialize as JSON", e, inputMessage)
    }
  }

  override fun write(t: Any, contentType: MediaType?, outputMessage: HttpOutputMessage) {
    throw IllegalArgumentException("Converter only supports reading")
  }
}
