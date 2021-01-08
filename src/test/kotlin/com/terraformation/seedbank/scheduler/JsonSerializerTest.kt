package com.terraformation.seedbank.scheduler

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.KotlinModule
import kotlin.reflect.KClass
import kotlin.reflect.jvm.jvmName
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

internal class JsonSerializerTest {
  private val objectMapper = ObjectMapper().registerModule(KotlinModule())

  @JsonVersion(2) data class SimpleClass(val value: String)
  @JsonVersion(1) data class OtherClass(val value: String)
  data class ClassWithoutVersionAnnotation(val value: String)

  open class NoOpUpgrader(override val classNames: List<String>) : JsonUpgrader {
    var called = false

    constructor(className: String) : this(listOf(className))
    constructor(clazz: KClass<*>) : this(clazz.jvmName)

    override fun upgrade(className: String, version: Int, tree: ObjectNode): ObjectNode {
      called = true
      return tree
    }
  }

  open class RenamingUpgrader : JsonUpgrader {
    override val classNames
      get() = listOf(SimpleClass::class.jvmName)

    override fun upgrade(className: String, version: Int, tree: ObjectNode): ObjectNode {
      tree.replace("value", tree.remove("old"))
      return tree
    }
  }

  @Test
  fun `deserializes current version`() {
    val serializer = JsonSerializer(objectMapper, emptyList())
    val json = """{"value":"test"}"""
    val expected = SimpleClass("test")
    val actual = serializer.deserialize(SimpleClass::class.jvmName, 2, json)

    assertEquals(expected, actual)
  }

  @Test
  fun `deserializes older version if no upgrader present`() {
    val serializer = JsonSerializer(objectMapper, emptyList())
    val json = """{"value":"test"}"""
    val expected = SimpleClass("test")
    val actual = serializer.deserialize(SimpleClass::class.jvmName, 1, json)

    assertEquals(expected, actual)
  }

  @Test
  fun `rejects multiple upgraders that cover the same class`() {
    assertThrows(IllegalArgumentException::class.java) {
      JsonSerializer(
          objectMapper, listOf(NoOpUpgrader(SimpleClass::class), NoOpUpgrader(SimpleClass::class)))
    }
  }

  @Test
  fun `rejects upgraders that cover unversioned classes`() {
    assertThrows(IllegalArgumentException::class.java) {
      JsonSerializer(objectMapper, listOf(NoOpUpgrader(ClassWithoutVersionAnnotation::class)))
    }
  }

  @Test
  fun `calls the correct upgrader for the class`() {
    val matchingUpgrader = NoOpUpgrader(SimpleClass::class)
    val otherUpgrader = NoOpUpgrader(OtherClass::class)
    val serializer = JsonSerializer(objectMapper, listOf(otherUpgrader, matchingUpgrader))
    val json = """{"value":"test"}"""

    serializer.deserialize(SerializedJob(SimpleClass::class.jvmName, 1, json))

    assertTrue(matchingUpgrader.called, "Matching upgrader called")
    assertFalse(otherUpgrader.called, "Other upgrader called")
  }

  @Test
  fun `can rename fields`() {
    val serializer = JsonSerializer(objectMapper, listOf(RenamingUpgrader()))
    val json = """{"old":"test"}"""
    val expected = SimpleClass("test")
    val actual = serializer.deserialize(SimpleClass::class.jvmName, 1, json)

    assertEquals(expected, actual)
  }

  @Test
  fun `can serialize an object`() {
    val serializer = JsonSerializer(objectMapper, emptyList())
    val obj = SimpleClass("test")
    val expected = SerializedJob(SimpleClass::class.jvmName, 2, """{"value":"test"}""")
    val actual = serializer.serialize(obj)

    assertEquals(expected, actual)
  }
}
