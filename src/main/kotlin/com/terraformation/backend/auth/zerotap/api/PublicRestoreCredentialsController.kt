package com.terraformation.backend.auth.zerotap.api

import com.terraformation.backend.api.CustomerEndpoint
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.auth.zerotap.RestoreCredentialService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.ws.rs.ServiceUnavailableException
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@CustomerEndpoint
@RequestMapping("/api/v1/restoreCredentials")
@RestController
class PublicRestoreCredentialsController(
    private val restoreCredentialServiceDependency: RestoreCredentialService?,
) {
  private val restoreCredentialService: RestoreCredentialService
    get() =
        restoreCredentialServiceDependency
            ?: throw ServiceUnavailableException("Zero-tap sign-in is not enabled")

  @Operation(
      summary = "Gets options for signing in with an Android restore credential.",
      description = "Does not require authentication.",
  )
  @PostMapping("/challenge")
  fun getRestoreCredentialRequestOptions(): GetRestoreCredentialRequestOptionsResponsePayload {
    return GetRestoreCredentialRequestOptionsResponsePayload(
        restoreCredentialService.createAuthenticationOptions()
    )
  }

  @Operation(
      summary = "Signs in with an Android restore credential.",
      description =
          "Does not require authentication. Returns a short-lived ticket to exchange for " +
              "tokens at Keycloak. The restore credential can't be used again.",
  )
  @PostMapping("/verify")
  fun verifyRestoreCredential(
      @RequestBody payload: VerifyRestoreCredentialRequestPayload
  ): VerifyRestoreCredentialResponsePayload {
    return VerifyRestoreCredentialResponsePayload(
        restoreCredentialService.authenticate(payload.authenticationResponseJson)
    )
  }
}

data class GetRestoreCredentialRequestOptionsResponsePayload(
    @Schema(
        description =
            "WebAuthn credential request options to pass to Credential Manager's " +
                "GetPublicKeyCredentialOption."
    )
    val requestJson: String,
) : SuccessResponsePayload

data class VerifyRestoreCredentialRequestPayload(
    @Schema(
        description = "Authentication response JSON from Credential Manager's PublicKeyCredential."
    )
    val authenticationResponseJson: String,
)

data class VerifyRestoreCredentialResponsePayload(
    @Schema(
        description =
            "Zero-tap ticket to pass as the zero_tap_ticket parameter of a password grant " +
                "request to Keycloak's token endpoint. Valid for a short time and can only be " +
                "used once."
    )
    val ticket: String,
) : SuccessResponsePayload
