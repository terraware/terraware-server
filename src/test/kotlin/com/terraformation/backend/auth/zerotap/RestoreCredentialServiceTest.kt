package com.terraformation.backend.auth.zerotap

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.auth.zerotap.db.RestoreCredentialStore
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.config.TerrawareServerConfig.ZeroTapConfig
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.tables.records.RestoreCredentialChallengesRecord
import com.terraformation.backend.db.default_schema.tables.records.RestoreCredentialsRecord
import com.terraformation.backend.db.default_schema.tables.references.RESTORE_CREDENTIALS
import com.terraformation.backend.db.default_schema.tables.references.RESTORE_CREDENTIAL_CHALLENGES
import com.webauthn4j.converter.AttestationObjectConverter
import com.webauthn4j.converter.AttestedCredentialDataConverter
import com.webauthn4j.converter.util.ObjectConverter
import com.webauthn4j.data.PublicKeyCredentialCreationOptions
import com.webauthn4j.data.ResidentKeyRequirement
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier
import com.webauthn4j.data.client.Origin
import com.webauthn4j.test.authenticator.webauthn.NoneAttestationAuthenticator
import com.webauthn4j.test.authenticator.webauthn.WebAuthnAuthenticatorAdaptor
import com.webauthn4j.test.client.ClientPlatform
import io.mockk.every
import io.mockk.mockk
import java.net.URI
import kotlin.io.encoding.Base64
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RestoreCredentialServiceTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val config: TerrawareServerConfig = mockk()
  private val service: RestoreCredentialService by lazy {
    RestoreCredentialService(config, RestoreCredentialStore(clock, dslContext))
  }

  private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
  private val objectConverter = ObjectConverter()
  private val origin = "android:apk-key-hash:test"

  @BeforeEach
  fun setUp() {
    every { config.webAppUrl } returns URI("https://terraware.io/")
    every { config.zeroTap } returns
        ZeroTapConfig(
            enabled = true,
            signingKeyPrivate = "unused",
        )
  }

  @Nested
  inner class CreateRegistrationOptions {
    @Test
    fun `stores challenge for current user and requires resident key`() {
      val options = parseOptions(service.createRegistrationOptions())

      assertEquals("terraware.io", options.rp.id, "RP ID")
      assertEquals(
          listOf(COSEAlgorithmIdentifier.ES256, COSEAlgorithmIdentifier.RS256),
          options.pubKeyCredParams.map { it.alg },
          "Key algorithms",
      )
      assertEquals(
          ResidentKeyRequirement.REQUIRED,
          options.authenticatorSelection?.residentKey,
          "Resident key requirement",
      )
      assertTableEquals(
          RestoreCredentialChallengesRecord(
              challenge = options.challenge.value,
              expiresTime = clock.instant().plus(config.zeroTap.challengeLifetime),
              userId = user.userId,
          )
      )
    }
  }

  @Nested
  inner class Register {
    @Test
    fun `stores credential and consumes challenge`() {
      val response = createRegistrationResponse()

      service.register(response.json)

      assertTableEquals(
          RestoreCredentialsRecord(
              attestedCredentialData = response.attestedCredentialData,
              createdTime = clock.instant(),
              credentialId = response.credentialId,
              signCount = 1,
              userId = user.userId,
          ),
      )
      assertTableEmpty(RESTORE_CREDENTIAL_CHALLENGES)
    }

    @Test
    fun `rejects malformed JSON`() {
      assertThrows<IllegalArgumentException> { service.register("{") }
    }

    @Test
    fun `rejects reused challenges`() {
      val responseJson = createRegistrationResponse().json

      service.register(responseJson)

      assertThrows<IllegalArgumentException> { service.register(responseJson) }
    }

    @Test
    fun `rejects responses from non-Android origins`() {
      val responseJson = createRegistrationResponse("https://terraware.io").json

      assertThrows<IllegalArgumentException> { service.register(responseJson) }
      assertTableEmpty(RESTORE_CREDENTIALS)
    }
  }

  private fun parseOptions(json: String): PublicKeyCredentialCreationOptions =
      objectConverter.jsonMapper.readValue(json, PublicKeyCredentialCreationOptions::class.java)

  /** Returns a registration response from an emulated authenticator. */
  private fun createRegistrationResponse(clientOrigin: String = origin): RegistrationResponse {
    val clientPlatform =
        ClientPlatform(
            Origin(clientOrigin),
            WebAuthnAuthenticatorAdaptor(NoneAttestationAuthenticator()),
        )
    val credential = clientPlatform.create(parseOptions(service.createRegistrationOptions()))
    val response = credential.response!!
    val responseJson =
        objectConverter.jsonMapper.writeValueAsString(
            mapOf(
                "clientExtensionResults" to emptyMap<String, Any>(),
                "id" to base64Url.encode(credential.rawId),
                "rawId" to base64Url.encode(credential.rawId),
                "response" to
                    mapOf(
                        "attestationObject" to base64Url.encode(response.attestationObject),
                        "clientDataJSON" to base64Url.encode(response.clientDataJSON),
                    ),
                "type" to "public-key",
            )
        )

    val attestedCredentialData =
        AttestationObjectConverter(objectConverter)
            .convert(response.attestationObject)!!
            .authenticatorData
            .attestedCredentialData!!

    return RegistrationResponse(
        credentialId = credential.rawId,
        attestedCredentialData =
            AttestedCredentialDataConverter(objectConverter).convert(attestedCredentialData),
        json = responseJson,
    )
  }

  private class RegistrationResponse(
      val credentialId: ByteArray,
      val attestedCredentialData: ByteArray,
      val json: String,
  )
}
