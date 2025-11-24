package com.terraformation.backend.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonMapperBuilder
import tools.jackson.module.kotlin.readValue

class BlankStringDeserializerTest {
  private val objectMapper =
      jacksonMapperBuilder()
          .addModule(SerializerConfiguration().blankStringDeserializerModule())
          .build()

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
