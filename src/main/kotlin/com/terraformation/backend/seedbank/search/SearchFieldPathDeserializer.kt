package com.terraformation.backend.seedbank.search

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.module.SimpleModule
import com.terraformation.backend.search.SearchFieldPath
import com.terraformation.backend.search.SearchFieldPrefix
import javax.annotation.ManagedBean
import javax.annotation.PostConstruct

/** Configures the Jackson object mapper to be able to deserialize search field paths. */
@ManagedBean
class SearchFieldPathDeserializer(
    private val objectMapper: ObjectMapper,
    accessionsNamespace: AccessionsNamespace,
) : StdDeserializer<SearchFieldPath>(SearchFieldPath::class.java) {
  private val root = SearchFieldPrefix(root = accessionsNamespace)

  @PostConstruct
  fun registerModules() {
    val module = SimpleModule()
    module.addDeserializer(SearchFieldPath::class.java, this)
    objectMapper.registerModule(module)
  }

  override fun deserialize(p: JsonParser, context: DeserializationContext): SearchFieldPath {
    val pathString = p.text
    val pathObject = root.resolveOrNull(pathString)

    if (pathObject == null) {
      context.handleWeirdStringValue(
          SearchFieldPath::class.java, pathString, "Field %s unknown", pathString)
      throw RuntimeException("BUG! Jackson error handling API should have thrown an exception")
    }

    return pathObject
  }
}
