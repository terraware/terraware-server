package com.terraformation.backend.eventlog.api

import com.fasterxml.jackson.annotation.JsonTypeName
import com.terraformation.backend.assertSetEquals
import kotlin.reflect.full.findAnnotation
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EventSubjectNameTest {
  @Test
  fun `subject payload type names match enum entry names`() {
    val payloadClasses = EventSubjectPayload::class.sealedSubclasses
    val payloadTypeNames =
        payloadClasses.mapNotNull { it.findAnnotation<JsonTypeName>()?.value }.toSet()
    val enumNames = EventSubjectName.entries.map { it.name }.toSet()

    assertSetEquals(payloadTypeNames, enumNames)
  }

  @Test
  fun `enum entries are in alphabetical order`() {
    assertEquals(EventSubjectName.entries.sortedBy { it.name }, EventSubjectName.entries)
  }
}
