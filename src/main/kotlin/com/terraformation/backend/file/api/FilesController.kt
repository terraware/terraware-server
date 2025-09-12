package com.terraformation.backend.file.api

import com.terraformation.backend.api.InternalEndpoint
import com.terraformation.backend.api.toResponseEntity
import com.terraformation.backend.file.FileService
import io.swagger.v3.oas.annotations.Operation
import org.springframework.core.io.InputStreamResource
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@InternalEndpoint
@RequestMapping("/api/v1/files")
@RestController
class FilesController(
    private val fileService: FileService,
) {
  @GetMapping("/tokens/{token}", produces = [MediaType.ALL_VALUE])
  @Operation(
      summary = "Gets the contents of the file associated with a file access token.",
      description =
          "This endpoint does not require authentication; it's intended to offer temporary file " +
              "access for third-party services such as video transcoding.",
  )
  fun getFileForToken(@PathVariable token: String): ResponseEntity<InputStreamResource> {
    return fileService.readFileForToken(token).toResponseEntity()
  }
}
