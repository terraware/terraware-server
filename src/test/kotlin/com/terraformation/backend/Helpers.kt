package com.terraformation.backend

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions

/**
 * Asserts that two objects are equal and, if they're not, outputs the comparison failure using
 * pretty-printed JSON rather than the outputs of their `toString` methods.
 *
 * This makes it easier to examine differences between accession objects with lots of field values.
 */
fun assertJsonEquals(expected: Any, actual: Any, message: String? = null) {
  if (expected != actual) {
    // Don't make the object mapper unless we actually need it; it's a little heavyweight.
    val objectMapper =
        jacksonObjectMapper()
            .registerModule(JavaTimeModule())
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .setSerializationInclusion(JsonInclude.Include.NON_EMPTY)

    Assertions.assertEquals(
        objectMapper.writeValueAsString(expected), objectMapper.writeValueAsString(actual), message)
  }
}
