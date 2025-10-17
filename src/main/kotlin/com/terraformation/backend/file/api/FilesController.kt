package com.terraformation.backend.file.api

import com.terraformation.backend.api.InternalEndpoint
import com.terraformation.backend.api.toResponseEntity
import com.terraformation.backend.file.FileService
import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.core.io.InputStreamResource
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@InternalEndpoint
@RequestMapping("/api/v1/files") // If you change this, update the path in MuxService too.
@RestController
class FilesController(
    private val fileService: FileService,
) {
  // If you change this path, update the path in MuxService too.
  @GetMapping("/tokens/{token}", produces = [MediaType.ALL_VALUE])
  @Operation(
      summary = "Gets the contents of the file associated with a file access token.",
      description =
          "This endpoint does not require authentication; it's intended to offer temporary file " +
              "access for third-party services such as video transcoding.",
  )
  @Valid
  fun getFileForToken(
      @PathVariable @Size(min = 8) token: String
  ): ResponseEntity<InputStreamResource> {
    return fileService.readFileForToken(token).toResponseEntity()
  }
}
