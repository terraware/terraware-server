package com.terraformation.seedbank.scheduler

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import javax.inject.Singleton

interface JobSerializer<S> {
  fun deserialize(className: String, version: Int, serialized: S): Any
  fun serialize(value: Any): SerializedJob<S>

  fun deserialize(serializedJob: SerializedJob<S>): Any {
    return deserialize(serializedJob.className, serializedJob.version, serializedJob.serialized)
  }
}

@Singleton
class JsonSerializer(
    private val objectMapper: ObjectMapper,
    jsonUpgraders: List<JsonUpgrader>,
) : JobSerializer<String> {
  private val upgradersByClassName: Map<String, JsonUpgrader>

  init {
    val map = mutableMapOf<String, JsonUpgrader>()
    jsonUpgraders.forEach { upgrader ->
      upgrader.classNames.forEach { className ->
        val clazz = Class.forName(className)
        if (!clazz.isAnnotationPresent(JsonVersion::class.java)) {
          throw IllegalArgumentException("$className has an upgrader but no version annotation")
        }
        if (className !in map) {
          map[className] = upgrader
        } else {
          val conflict1 = map[className]?.javaClass?.name
          val conflict2 = upgrader.javaClass.name
          throw IllegalArgumentException(
              "$className is claimed by two upgraders: $conflict1 $conflict2")
        }
      }
    }

    upgradersByClassName = map
  }

  override fun deserialize(className: String, version: Int, serialized: String): Any {
    val clazz = Class.forName(className)
    val classVersion = clazz.jsonVersionFromAnnotation
    val upgrader = upgradersByClassName[className]

    // If there are no upgraders for the class, or the JSON is already the current version,
    // deserialize it directly.
    if (upgrader == null || version == classVersion) {
      return objectMapper.readValue(serialized, clazz)
    }

    val originalTree = objectMapper.readTree(serialized) as ObjectNode
    val upgradedTree = upgrader.upgrade(className, version, originalTree)
    return objectMapper.readerFor(clazz).readValue(upgradedTree)
  }

  override fun serialize(value: Any): SerializedJob<String> {
    val clazz = value.javaClass

    return SerializedJob(
        clazz.name,
        clazz.jsonVersionFromAnnotation,
        objectMapper.writeValueAsString(value),
    )
  }
}

data class SerializedJob<S>(val className: String, val version: Int, val serialized: S)

class SerializationException(message: String, cause: Exception? = null) :
    RuntimeException(message, cause)
