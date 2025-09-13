package com.terraformation.backend.file

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.log.perClassLogger
import io.jsonwebtoken.Jwts
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.basicAuth
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import jakarta.inject.Named
import java.io.StringReader
import java.net.URI
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.InstantSource
import java.util.Base64
import java.util.Date
import kotlinx.coroutines.runBlocking
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser

@Named
class MuxService(
    private val clock: InstantSource,
    private val config: TerrawareServerConfig,
    private val fileService: FileService,
    private val httpClient: HttpClient,
) {
  private val muxApiUrl = URI("https://api.mux.com")
  private val fileAccessTokenExpiration = Duration.ofHours(1)
  private val fileAccessEndpoint = "/api/v1/files/tokens"

  private val log = perClassLogger()
  private lateinit var tokenId: String
  private lateinit var tokenSecret: String
  private lateinit var signingKey: PrivateKey
  private lateinit var signingKeyId: String
  private lateinit var baseUrl: URI

  init {
    if (config.mux.enabled) {
      baseUrl = config.mux.externalUrl ?: config.webAppUrl
      signingKey = parseSigningKey()
      signingKeyId = config.mux.signingKeyId!!
      tokenId = config.mux.tokenId!!
      tokenSecret = config.mux.tokenSecret!!
    }
  }

  fun processFile(fileId: FileId): String {
    val token = fileService.createToken(fileId, fileAccessTokenExpiration)

    val request =
        CreateVideoAssetRequest(
            fileId = fileId,
            test = config.mux.useTestAssets,
            url = baseUrl.resolve("$fileAccessEndpoint/$token"),
        )
    val response = sendRequest<CreateVideoAssetResponse>("/video/v1/assets", request)

    return response.data.playback_ids.first().id
  }

  fun generatePlaybackToken(playbackId: String, expiration: Duration): String {
    return Jwts.builder()
        .subject(playbackId)
        .claim("kid", signingKeyId)
        .claim("aud", "v") // "v" = token is for video playback
        .expiration(Date(clock.instant().plus(expiration).toEpochMilli()))
        .signWith(signingKey)
        .compact()
  }

  /**
   * Parses a Mux-provided signing key. Mux's private keys are provided as base64-encoded strings
   * that decode to PEM-formatted RSA private keys.
   */
  private fun parseSigningKey(): PrivateKey {
    val pemString =
        Base64.getDecoder().decode(config.mux.signingKeyPrivate).toString(Charsets.US_ASCII)
    val pemObj = PEMParser(StringReader(pemString)).readObject() as PEMKeyPair
    val keySpec = PKCS8EncodedKeySpec(pemObj.privateKeyInfo.encoded)
    return KeyFactory.getInstance("RSA").generatePrivate(keySpec)
  }

  private suspend fun doSendRequest(
      path: String,
      body: Any? = null,
      httpMethod: HttpMethod,
  ): HttpResponse {
    val url = muxApiUrl.resolve(path).toString()

    return try {
      httpClient.request(url) {
        method = httpMethod
        basicAuth(tokenId, tokenSecret)
        body?.let { setBody(it) }
      }
    } catch (e: ClientRequestException) {
      log.debug("Mux response ${e.response.status}: ${e.response.bodyAsText()}")
      throw e
    }
  }

  private inline fun <reified T> sendRequest(
      path: String,
      body: Any? = null,
      method: HttpMethod = if (body != null) HttpMethod.Post else HttpMethod.Get,
  ): T {
    ensureEnabled()

    return runBlocking {
      val response = doSendRequest(path, body, method)
      if (T::class.java == Unit.javaClass) {
        T::class.objectInstance!!
      } else {
        response.body()
      }
    }
  }

  private fun ensureEnabled() {
    if (!config.mux.enabled) {
      throw IllegalStateException("Mux is not enabled")
    }
  }

  data class CreateVideoAssetInput(
      val url: URI,
  )

  data class VideoAssetMeta(
      val external_id: String,
  )

  data class CreateVideoAssetRequest(
      val inputs: List<CreateVideoAssetInput>,
      val meta: VideoAssetMeta,
      val test: Boolean,
  ) {
    constructor(
        fileId: FileId,
        test: Boolean,
        url: URI,
    ) : this(
        inputs = listOf(CreateVideoAssetInput(url)),
        meta = VideoAssetMeta("$fileId"),
        test = test,
    )

    val playback_policies
      get() = listOf("signed")
  }

  @Suppress("EnumEntryName")
  enum class VideoAssetStatus {
    preparing,
    ready,
    errored,
  }

  data class VideoAssetPlaybackIdInfo(val id: String)

  data class VideoAssetErrorDetails(val type: String?, val message: String?)

  data class VideoAssetData(
      val status: VideoAssetStatus,
      /** Duration in seconds. */
      val duration: Int,
      val playback_ids: List<VideoAssetPlaybackIdInfo>,
      val errors: VideoAssetErrorDetails?,
      val meta: VideoAssetMeta?,
  )

  data class CreateVideoAssetResponse(val data: VideoAssetData)
}
