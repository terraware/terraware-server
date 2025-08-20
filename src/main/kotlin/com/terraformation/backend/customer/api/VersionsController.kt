package com.terraformation.backend.customer.api

import com.terraformation.backend.api.CustomerEndpoint
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.customer.db.AppVersionStore
import com.terraformation.backend.db.default_schema.tables.pojos.AppVersionsRow
import io.swagger.v3.oas.annotations.Operation
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@CustomerEndpoint
@RequestMapping("/api/v1/versions")
@RestController
class VersionsController(private val appVersionStore: AppVersionStore) {
  @GetMapping
  @Operation(
      summary = "Gets the minimum and recommended versions for Terraware's client applications."
  )
  fun getVersions(): VersionsResponsePayload {
    val entries = appVersionStore.findAll().map { VersionsEntryPayload(it) }
    return VersionsResponsePayload(entries)
  }
}

data class VersionsResponsePayload(val versions: List<VersionsEntryPayload>) :
    SuccessResponsePayload

data class VersionsEntryPayload(
    val appName: String,
    val platform: String,
    val minimumVersion: String,
    val recommendedVersion: String,
) {
  constructor(
      row: AppVersionsRow
  ) : this(row.appName!!, row.platform!!, row.minimumVersion!!, row.recommendedVersion!!)
}
