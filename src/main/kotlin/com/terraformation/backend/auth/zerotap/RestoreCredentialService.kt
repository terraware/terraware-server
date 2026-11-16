package com.terraformation.backend.auth.zerotap

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.auth.zerotap.db.RestoreCredentialStore
import com.terraformation.backend.config.TerrawareServerConfig
import com.webauthn4j.WebAuthnManager
import com.webauthn4j.converter.AttestedCredentialDataConverter
import com.webauthn4j.converter.exception.DataConversionException
import com.webauthn4j.converter.util.ObjectConverter
import com.webauthn4j.credential.CredentialRecordImpl
import com.webauthn4j.data.AuthenticationParameters
import com.webauthn4j.data.PublicKeyCredentialParameters
import com.webauthn4j.data.PublicKeyCredentialType
import com.webauthn4j.data.RegistrationParameters
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier
import com.webauthn4j.data.client.challenge.DefaultChallenge
import com.webauthn4j.server.ServerProperty
import com.webauthn4j.verifier.exception.VerificationException
import jakarta.inject.Named
import java.security.SecureRandom
import kotlin.io.encoding.Base64
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import tools.jackson.core.JacksonException

/**
 * Registers and verifies Android restore credentials. Restore credentials are WebAuthn credentials
 * that Credential Manager backs up and restores when the user sets up a new device, letting the app
 * sign the user in without asking for their password.
 */
@ConditionalOnProperty(TerrawareServerConfig.ZERO_TAP_ENABLED_PROPERTY)
@Named
class RestoreCredentialService(
    private val config: TerrawareServerConfig,
    private val restoreCredentialStore: RestoreCredentialStore,
    private val zeroTapTicketIssuer: ZeroTapTicketIssuer,
) {
  private val objectConverter = ObjectConverter()
  private val attestedCredentialDataConverter = AttestedCredentialDataConverter(objectConverter)
  private val webAuthnManager = WebAuthnManager.createNonStrictWebAuthnManager(objectConverter)
  private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
  private val secureRandom = SecureRandom()
  private val pubKeyCredParams =
      listOf(COSEAlgorithmIdentifier.ES256, COSEAlgorithmIdentifier.RS256).map {
        PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, it)
      }

  /**
   * Returns the WebAuthn credential creation options, as a JSON string, that the app passes to
   * Credential Manager to create a restore credential for the current user.
   */
  fun createRegistrationOptions(): String {
    val user = currentUser()
    val challenge = generateRandomBytes()
    restoreCredentialStore.createChallenge(challenge, user.userId, config.zeroTap.challengeLifetime)

    val options =
        mapOf(
            "attestation" to "none",
            "authenticatorSelection" to
                mapOf(
                    "authenticatorAttachment" to "platform",
                    "requireResidentKey" to true,
                    "residentKey" to "required",
                    "userVerification" to "discouraged",
                ),
            "challenge" to base64Url.encode(challenge),
            "pubKeyCredParams" to
                pubKeyCredParams.map { mapOf("alg" to it.alg.value, "type" to "public-key") },
            "rp" to mapOf("id" to rpId(), "name" to "Terraware"),
            "timeout" to config.zeroTap.challengeLifetime.toMillis(),
            // The user handle is opaque; restore credentials are looked up by credential ID.
            "user" to
                mapOf(
                    "displayName" to user.email,
                    "id" to base64Url.encode(generateRandomBytes()),
                    "name" to user.email,
                ),
        )

    return objectConverter.jsonMapper.writeValueAsString(options)
  }

  /**
   * Verifies a restore credential registration response from Credential Manager and stores the new
   * credential for the current user.
   */
  fun register(registrationResponseJson: String) {
    val userId = currentUser().userId
    val registrationData =
        try {
          webAuthnManager.parseRegistrationResponseJSON(registrationResponseJson)
        } catch (e: DataConversionException) {
          throw invalidCredential()
        } catch (e: JacksonException) {
          throw invalidCredential()
        }
    val challenge =
        registrationData.collectedClientData?.challenge?.value ?: throw invalidCredential()

    if (!restoreCredentialStore.consumeChallenge(challenge, userId)) {
      throw invalidCredential()
    }

    try {
      webAuthnManager.verify(
          registrationData,
          RegistrationParameters(serverProperty(challenge), pubKeyCredParams, false, false),
      )
    } catch (e: VerificationException) {
      throw invalidCredential()
    }

    val authenticatorData = registrationData.attestationObject!!.authenticatorData
    val attestedCredentialData = authenticatorData.attestedCredentialData!!

    restoreCredentialStore.createCredential(
        userId,
        attestedCredentialData.credentialId,
        attestedCredentialDataConverter.convert(attestedCredentialData),
        authenticatorData.signCount,
    )
  }

  /**
   * Returns the WebAuthn credential request options, as a JSON string, that the app passes to
   * Credential Manager to get the user's restore credential after the app is restored on a new
   * device.
   */
  fun createAuthenticationOptions(): String {
    val challenge = generateRandomBytes()
    restoreCredentialStore.createChallenge(challenge, null, config.zeroTap.challengeLifetime)

    val options =
        mapOf(
            "challenge" to base64Url.encode(challenge),
            "rpId" to rpId(),
            "timeout" to config.zeroTap.challengeLifetime.toMillis(),
            "userVerification" to "discouraged",
        )

    return objectConverter.jsonMapper.writeValueAsString(options)
  }

  /**
   * Verifies an authentication response from a restore credential and returns a zero-tap ticket the
   * app can exchange for tokens at Keycloak. Restore credentials are single-use: the credential is
   * deleted and the app is expected to register a new one once it's signed in.
   *
   * Failures are deliberately not distinguished from one another, so unauthenticated callers can't
   * use this to learn which credentials exist.
   */
  fun authenticate(authenticationResponseJson: String): String {
    val authenticationData =
        try {
          webAuthnManager.parseAuthenticationResponseJSON(authenticationResponseJson)
        } catch (e: DataConversionException) {
          throw notAccepted()
        } catch (e: JacksonException) {
          throw notAccepted()
        }
    val challenge = authenticationData.collectedClientData?.challenge?.value ?: throw notAccepted()

    if (!restoreCredentialStore.consumeChallenge(challenge, null)) {
      throw notAccepted()
    }

    val credentialId = authenticationData.credentialId
    val credential = restoreCredentialStore.fetchByCredentialId(credentialId) ?: throw notAccepted()
    val credentialRecord =
        CredentialRecordImpl(
            null,
            null,
            null,
            null,
            credential.signCount,
            attestedCredentialDataConverter.convert(credential.attestedCredentialData),
            null,
            null,
            null,
            null,
        )

    try {
      webAuthnManager.verify(
          authenticationData,
          AuthenticationParameters(serverProperty(challenge), credentialRecord, null, false, false),
      )
    } catch (e: VerificationException) {
      throw notAccepted()
    }

    // Issue the ticket first so the credential isn't lost if issuing fails.
    val ticket = zeroTapTicketIssuer.issueTicket(credential.authId)

    if (!restoreCredentialStore.deleteCredential(credentialId)) {
      throw notAccepted()
    }

    return ticket
  }

  private fun generateRandomBytes(): ByteArray = ByteArray(32).also { secureRandom.nextBytes(it) }

  private fun serverProperty(challenge: ByteArray): ServerProperty {
    return ServerProperty.builder()
        // Credential Manager only lets the app that created a restore key use it, so there's no
        // need to check the app's signing certificate hash.
        .originPredicate { it.toString().startsWith("android:apk-key-hash:") }
        .rpId(rpId())
        .challenge(DefaultChallenge(challenge))
        .build()
  }

  private fun rpId(): String = config.zeroTap.rpId ?: config.webAppUrl.host

  private fun notAccepted() =
      ResponseStatusException(HttpStatus.UNAUTHORIZED, "Restore credential not accepted")

  private fun invalidCredential() = IllegalArgumentException("Invalid restore credential")
}
