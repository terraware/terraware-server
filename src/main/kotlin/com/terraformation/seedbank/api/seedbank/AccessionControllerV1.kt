package com.terraformation.seedbank.api.seedbank

import com.terraformation.seedbank.api.annotation.ApiResponse404
import com.terraformation.seedbank.api.annotation.SeedBankAppEndpoint
import com.terraformation.seedbank.db.AccessionStore
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/api/v1/seedbank/accession")
@RestController
@SeedBankAppEndpoint
class AccessionControllerV1(accessionStore: AccessionStore) {
  private val v2Controller = AccessionController(accessionStore)

  @ApiResponse(
      responseCode = "200",
      description =
          "The accession was created successfully. Response includes fields populated by the " +
              "server, including the accession number.")
  @Operation(summary = "Create a new accession.")
  @PostMapping
  fun create(@RequestBody payload: CreateAccessionRequestPayload) = v2Controller.create(payload)

  @ApiResponse(
      responseCode = "200",
      description =
          "The accession was updated successfully. Response includes fields populated or " +
              "modified by the server as a result of the update.")
  @ApiResponse404(description = "The specified accession doesn't exist.")
  @Operation(summary = "Update an existing accession.")
  @PutMapping("/{accessionNumber}")
  fun update(
      @RequestBody payload: UpdateAccessionRequestPayload,
      @PathVariable accessionNumber: String,
      @RequestParam
      @Schema(
          description =
              "If true, do not actually save the accession; just return the result that would " +
                  "have been returned if it had been saved.")
      simulate: Boolean?
  ) = v2Controller.update(payload, accessionNumber, simulate)

  @ApiResponse(responseCode = "200")
  @ApiResponse404
  @GetMapping("/{accessionNumber}")
  @Operation(summary = "Retrieve an existing accession.")
  fun read(@PathVariable accessionNumber: String) = v2Controller.read(accessionNumber)
}
