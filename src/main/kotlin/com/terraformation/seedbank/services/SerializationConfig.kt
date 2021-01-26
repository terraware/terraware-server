package com.terraformation.seedbank.services

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.module.SimpleModule
import com.terraformation.seedbank.search.SearchField
import com.terraformation.seedbank.search.SearchFields
import javax.annotation.ManagedBean
import javax.annotation.PostConstruct

@ManagedBean
class SerializationConfig(
    private val objectMapper: ObjectMapper,
    private val searchFields: SearchFields
) {
  @PostConstruct
  fun registerModules() {
    val module = SimpleModule()
    module.addDeserializer(SearchField::class.java, SearchFieldsDeserializer(searchFields))
    objectMapper.registerModule(module)
  }
}

private class SearchFieldsDeserializer(private val searchFields: SearchFields) :
    StdDeserializer<SearchField<*>>(SearchField::class.java) {
  override fun deserialize(p: JsonParser, context: DeserializationContext): SearchField<*> {
    val fieldName = p.text
    val field = searchFields[fieldName]
    if (field == null) {
      // This throws an exception; we never get to the `return`
      context.handleWeirdStringValue(
          SearchField::class.java, fieldName, "Field %s unknown", fieldName)
    }
    return field!!
  }
}
