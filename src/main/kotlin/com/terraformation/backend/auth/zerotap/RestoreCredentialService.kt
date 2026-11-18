package com.terraformation.backend.auth.zerotap

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.auth.zerotap.db.RestoreCredentialStore
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.log.perClassLogger
import com.webauthn4j.WebAuthnManager
import com.webauthn4j.converter.AttestedCredentialDataConverter
import com.webauthn4j.converter.exception.DataConversionException
import com.webauthn4j.converter.util.ObjectConverter
import com.webauthn4j.credential.CredentialRecordImpl
import com.webauthn4j.data.AuthenticationParameters
import com.webauthn4j.data.PublicKeyCredentialParameters
import com.webauthn4j.data.PublicKeyCredentialType
import com.webauthn4j.data.RegistrationParameters
import com.webauthn4j.data.attestation.authenticator.AuthenticatorData
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier
import com.webauthn4j.data.client.CollectedClientData
import com.webauthn4j.data.client.challenge.DefaultChallenge
import com.webauthn4j.server.ServerProperty
import com.webauthn4j.verifier.exception.VerificationException
import jakarta.inject.Named
import java.security.MessageDigest
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
  private val log = perClassLogger()
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
    log.debug(
        "Restore credential registration challenge ${challenge.toBase64Url()} issued to user " +
            "${user.userId} for RP ID ${rpId()}"
    )

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
    log.debug("Restore credential registration from user $userId: $registrationResponseJson")

    val registrationData =
        try {
          webAuthnManager.parseRegistrationResponseJSON(registrationResponseJson)
        } catch (e: DataConversionException) {
          throw invalidCredential("Registration from user $userId failed: unparseable response: $e")
        } catch (e: JacksonException) {
          throw invalidCredential("Registration from user $userId failed: unparseable response: $e")
        }
    log.debug(
        "Registration from user $userId: client data " +
            "${describe(registrationData.collectedClientData)}, attestation format " +
            "${registrationData.attestationObject?.format}, " +
            "authenticator data ${describe(registrationData.attestationObject?.authenticatorData)}"
    )

    val challenge =
        registrationData.collectedClientData?.challenge?.value
            ?: throw invalidCredential(
                "Registration from user $userId failed: no challenge in client data"
            )

    if (!restoreCredentialStore.consumeChallenge(challenge, userId)) {
      throw invalidCredential(
          "Registration from user $userId failed: ${describeChallengeFailure(challenge, userId)}"
      )
    }

    try {
      webAuthnManager.verify(
          registrationData,
          RegistrationParameters(serverProperty(challenge), pubKeyCredParams, false, false),
      )
    } catch (e: VerificationException) {
      throw invalidCredential("Registration from user $userId failed verification: $e")
    }

    val authenticatorData = registrationData.attestationObject!!.authenticatorData
    val attestedCredentialData = authenticatorData.attestedCredentialData!!
    log.debug(
        "Registered restore credential ${attestedCredentialData.credentialId.toBase64Url()} " +
            "for user $userId: algorithm ${attestedCredentialData.coseKey.algorithm}, " +
            "AAGUID ${attestedCredentialData.aaguid}"
    )

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
    log.debug(
        "Restore credential authentication challenge ${challenge.toBase64Url()} issued for " +
            "RP ID ${rpId()}"
    )

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
    log.debug("Restore credential authentication: $authenticationResponseJson")

    val authenticationData =
        try {
          webAuthnManager.parseAuthenticationResponseJSON(authenticationResponseJson)
        } catch (e: DataConversionException) {
          throw notAccepted("Authentication failed: unparseable response: $e")
        } catch (e: JacksonException) {
          throw notAccepted("Authentication failed: unparseable response: $e")
        }
    val credentialId = authenticationData.credentialId
    val credentialIdString = credentialId.toBase64Url()
    log.debug(
        "Authentication with credential $credentialIdString: user handle " +
            "${authenticationData.userHandle?.toBase64Url()}, client data " +
            "${describe(authenticationData.collectedClientData)}, authenticator data " +
            describe(authenticationData.authenticatorData)
    )

    val challenge =
        authenticationData.collectedClientData?.challenge?.value
            ?: throw notAccepted("Authentication with $credentialIdString failed: no challenge")

    if (!restoreCredentialStore.consumeChallenge(challenge, null)) {
      throw notAccepted(
          "Authentication with $credentialIdString failed: " +
              describeChallengeFailure(challenge, null)
      )
    }

    val credential =
        restoreCredentialStore.fetchByCredentialId(credentialId)
            ?: throw notAccepted {
              val ownerId = restoreCredentialStore.fetchUserIdByCredentialId(credentialId)
              if (ownerId == null) {
                log.debug(
                    "Authentication with $credentialIdString failed: credential not found; it " +
                        "was never registered, was already used, or was deleted at sign-out"
                )
              } else {
                log.debug(
                    "Authentication with $credentialIdString failed: credential belongs to user " +
                        "$ownerId, who is deleted or has no Keycloak ID"
                )
              }
            }
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
      throw notAccepted(
          "Authentication with $credentialIdString for user ${credential.userId} failed " +
              "verification: $e"
      )
    }

    // Issue the ticket first so the credential isn't lost if issuing fails.
    val ticket = zeroTapTicketIssuer.issueTicket(credential.authId)

    if (!restoreCredentialStore.deleteCredential(credentialId)) {
      throw notAccepted(
          "Authentication with $credentialIdString failed: credential was used by a " +
              "concurrent request"
      )
    }

    log.debug("Authenticated user ${credential.userId} with credential $credentialIdString")

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

  private fun ByteArray.toBase64Url(): String = base64Url.encode(this)

  private fun describeChallengeFailure(challenge: ByteArray, userId: UserId?): String {
    val record = restoreCredentialStore.fetchChallenge(challenge)
    return when {
      record == null ->
          "challenge ${challenge.toBase64Url()} not found; it was never issued, was already " +
              "used, or expired and was cleaned up"
      record.userId != userId ->
          "challenge ${challenge.toBase64Url()} was issued to user ${record.userId}"
      else -> "challenge ${challenge.toBase64Url()} expired at ${record.expiresTime}"
    }
  }

  private fun describe(clientData: CollectedClientData?): String {
    return clientData?.let {
      "type=${it.type.value} origin=${it.origin} challenge=${it.challenge.value.toBase64Url()} " +
          "crossOrigin=${it.crossOrigin}"
    } ?: "missing"
  }

  private fun describe(authenticatorData: AuthenticatorData<*>?): String {
    val expectedRpIdHash = MessageDigest.getInstance("SHA-256").digest(rpId().toByteArray())

    return authenticatorData?.let {
      "rpIdHash=${it.rpIdHash.toBase64Url()} (expected ${expectedRpIdHash.toBase64Url()} " +
          "for ${rpId()}) UP=${it.isFlagUP} UV=${it.isFlagUV} BE=${it.isFlagBE} " +
          "BS=${it.isFlagBS} signCount=${it.signCount}"
    } ?: "missing"
  }

  private inline fun notAccepted(beforeThrowing: () -> Unit = {}): ResponseStatusException {
    beforeThrowing()
    return ResponseStatusException(HttpStatus.UNAUTHORIZED, "Restore credential not accepted")
  }

  private fun notAccepted(debugMessage: String) = notAccepted { log.debug(debugMessage) }

  private fun invalidCredential(debugMessage: String) = invalidCredential {
    log.debug(debugMessage)
  }

  private inline fun invalidCredential(beforeThrowing: () -> Unit = {}): IllegalArgumentException {
    beforeThrowing()
    return IllegalArgumentException("Invalid restore credential")
  }
}
