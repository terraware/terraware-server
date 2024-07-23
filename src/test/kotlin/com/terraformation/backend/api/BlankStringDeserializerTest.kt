package com.terraformation.backend.api

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BlankStringDeserializerTest {
  private val objectMapper =
      jacksonObjectMapper()
          .registerModule(SerializerConfiguration().blankStringDeserializerModule())

  @Test
  fun `allows blank strings on fields annotated with AllowBlankString`() {
    val json = """{"allowBlank": "", "nullIfBlank": ""}"""
    val payload = objectMapper.readValue<TestPayload>(json)

    assertEquals(TestPayload(allowBlank = "", nullIfBlank = null), payload)
  }

  data class TestPayload(
      @AllowBlankString val allowBlank: String?,
      val nullIfBlank: String?,
  )
}
