package com.terraformation.backend.species.api

import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.species.db.GbifImporter
import io.swagger.v3.oas.annotations.Hidden
import java.net.URI
import javax.ws.rs.FormParam
import javax.ws.rs.ServerErrorException
import javax.ws.rs.core.Response
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Hidden
@RequestMapping("/api/v1/admin/species")
@RestController
class SpeciesAdminController(
    private val gbifImporter: GbifImporter,
) {
  @PostMapping("/importGbif")
  fun importGbif(@FormParam("source") source: URI): SimpleSuccessResponsePayload {
    try {
      gbifImporter.import(source)
      return SimpleSuccessResponsePayload()
    } catch (e: AccessDeniedException) {
      throw e
    } catch (e: Exception) {
      throw ServerErrorException("Import failed", Response.Status.INTERNAL_SERVER_ERROR, e)
    }
  }
}
