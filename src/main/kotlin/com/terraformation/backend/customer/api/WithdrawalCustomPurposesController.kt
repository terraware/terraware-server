package com.terraformation.backend.customer.api

import com.terraformation.backend.api.CustomerEndpoint
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.customer.db.WithdrawalCustomPurposeHelper
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.WithdrawalCustomPurposeId
import com.terraformation.backend.db.default_schema.WithdrawalCustomPurposeType
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@CustomerEndpoint
@RequestMapping("/api/v1/withdrawalCustomPurposes")
@RestController
class WithdrawalCustomPurposesController(private val helper: WithdrawalCustomPurposeHelper) {
  @GetMapping
  @Operation(
      summary = "List an organization's custom withdrawal purposes.",
      description = "Custom purposes are created implicitly when submitting a withdrawal.",
  )
  fun listCustomPurposes(
      @RequestParam organizationId: OrganizationId,
      @RequestParam type: WithdrawalCustomPurposeType,
      @RequestParam(defaultValue = "false") includeArchived: Boolean,
  ): ListWithdrawalCustomPurposesResponsePayload =
      ListWithdrawalCustomPurposesResponsePayload(
          helper.fetchByOrganizationId(organizationId, type, includeArchived).map {
            WithdrawalCustomPurposePayload(
                id = it.id,
                isArchived = it.isArchived,
                name = it.name,
            )
          }
      )

  @PutMapping("/{customPurposeId}")
  @Operation(summary = "Rename, archive, or unarchive a custom withdrawal purpose.")
  fun updateCustomPurpose(
      @PathVariable customPurposeId: WithdrawalCustomPurposeId,
      @RequestBody @Valid payload: UpdateWithdrawalCustomPurposeRequestPayload,
  ): SimpleSuccessResponsePayload {
    helper.update(customPurposeId, isArchived = payload.isArchived, name = payload.name)
    return SimpleSuccessResponsePayload()
  }
}

data class WithdrawalCustomPurposePayload(
    val id: WithdrawalCustomPurposeId,
    @Schema(
        description =
            "If true, hidden from the withdrawal form but still shown on past withdrawals. " +
                "Custom purposes are created implicitly when submitting a withdrawal."
    )
    val isArchived: Boolean,
    val name: String,
)

data class ListWithdrawalCustomPurposesResponsePayload(
    val customPurposes: List<WithdrawalCustomPurposePayload>
) : SuccessResponsePayload

data class UpdateWithdrawalCustomPurposeRequestPayload(
    @Schema(
        description =
            "If true, hide from the withdrawal form but retain on past withdrawals. " +
                "Set to false to unarchive. Omit to leave unchanged."
    )
    val isArchived: Boolean? = null,
    @Schema(
        description =
            "New name for the custom withdrawal purpose. " +
                "Renames apply to past withdrawals. Omit to leave unchanged.",
    )
    @field:Size(min = 1, max = 50)
    val name: String? = null,
)
