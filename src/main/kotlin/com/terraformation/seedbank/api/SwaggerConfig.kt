package com.terraformation.seedbank.api

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Paths
import javax.annotation.ManagedBean
import org.springdoc.core.customizers.OpenApiCustomiser

/**
 * Customizes generation of Swagger API documentation.
 *
 * - The list of endpoints and the list of schemas is alphabetized by tag and then by endpoint path
 * so that the JSON/YAML documentation can be usefully diffed between code versions.
 */
@ManagedBean
class SwaggerConfig : OpenApiCustomiser {
  override fun customise(openApi: OpenAPI) {
    sortEndpoints(openApi)
    sortSchemas(openApi)
  }

  private fun sortEndpoints(openApi: OpenAPI) {
    val paths = Paths()
    openApi.paths.entries.sortedBy { it.key }.forEach { paths.addPathItem(it.key, it.value) }
    openApi.paths = paths
  }

  private fun sortSchemas(openApi: OpenAPI) {
    openApi.components.schemas = openApi.components.schemas.toSortedMap()
  }
}
