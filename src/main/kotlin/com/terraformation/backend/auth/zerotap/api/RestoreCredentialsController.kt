package com.terraformation.backend.auth.zerotap.api

import com.terraformation.backend.api.CustomerEndpoint
import com.terraformation.backend.api.SimpleSuccessResponsePayload
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
@RequestMapping("/api/v1/users/me/restoreCredentials")
@RestController
class RestoreCredentialsController(
    private val restoreCredentialServiceDependency: RestoreCredentialService?,
) {
  private val restoreCredentialService: RestoreCredentialService
    get() =
        restoreCredentialServiceDependency
            ?: throw ServiceUnavailableException("Zero-tap sign-in is not enabled")

  @Operation(
      summary = "Gets options for creating an Android restore credential for the current user."
  )
  @PostMapping("/options")
  fun getRestoreCredentialCreationOptions(): GetRestoreCredentialCreationOptionsResponsePayload {
    return GetRestoreCredentialCreationOptionsResponsePayload(
        restoreCredentialService.createRegistrationOptions()
    )
  }

  @Operation(summary = "Registers an Android restore credential for the current user.")
  @PostMapping
  fun registerRestoreCredential(
      @RequestBody payload: RegisterRestoreCredentialRequestPayload
  ): SimpleSuccessResponsePayload {
    restoreCredentialService.register(payload.registrationResponseJson)
    return SimpleSuccessResponsePayload()
  }
}

data class GetRestoreCredentialCreationOptionsResponsePayload(
    @Schema(
        description =
            "WebAuthn credential creation options to pass to Credential Manager's " +
                "CreateRestoreCredentialRequest."
    )
    val requestJson: String,
) : SuccessResponsePayload

data class RegisterRestoreCredentialRequestPayload(
    @Schema(
        description =
            "Registration response JSON from Credential Manager's CreateRestoreCredentialResponse."
    )
    val registrationResponseJson: String,
)
