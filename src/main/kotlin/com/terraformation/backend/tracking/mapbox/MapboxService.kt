package com.terraformation.backend.tracking.mapbox

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.log.perClassLogger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import jakarta.inject.Named
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.net.URL
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import kotlin.math.*
import kotlinx.coroutines.runBlocking
import org.locationtech.jts.geom.Point

@Named
class MapboxService(
    private val config: TerrawareServerConfig,
    private val httpClient: HttpClient,
) {
  private val log = perClassLogger()

  val enabled: Boolean = config.mapbox.apiToken?.isNotBlank() == true

  /**
   * The Mapbox username of the account that owns the configured API token. Some Mapbox APIs require
   * the username to be included explicitly in the request.
   *
   * We fetch this lazily the first time it's accessed by requesting information about our access
   * token, which includes the username.
   */
  private val username: String by lazy {
    runBlocking {
      try {
        val response: RetrieveTokenResponsePayload = httpClient.get(mapboxUrl("tokens/v2")).body()
        response.token.user
      } catch (e: ClientRequestException) {
        log.error("HTTP ${e.response.status} when fetching Mapbox token information")
        log.info("Mapbox token error response payload: ${e.response.bodyAsText()}")
        throw MapboxRequestFailedException(e.response.status)
      }
    }
  }

  /** Generates a temporary API token. */
  fun generateTemporaryToken(): String {
    if (!enabled) {
      throw MapboxNotConfiguredException()
    }

    val url = mapboxUrl("tokens/v2/$username")

    val expirationTime =
        Instant.now().plus(config.mapbox.temporaryTokenExpirationMinutes, ChronoUnit.MINUTES)
    val requestPayload =
        TemporaryTokenRequestPayload(
            expires = expirationTime,
            scopes = listOf("styles:tiles", "styles:read", "fonts:read"),
        )

    return runBlocking {
      try {
        val responsePayload: TemporaryTokenResponsePayload =
            httpClient.post(url) { setBody(requestPayload) }.body()

        responsePayload.token
      } catch (e: ClientRequestException) {
        log.error("Mapbox token request failed with HTTP ${e.response.status}")
        log.info("Mapbox token error response payload: ${e.response.bodyAsText()}")
        throw MapboxRequestFailedException(e.response.status)
      }
    }
  }

  /** Return elevation in meter given a point */
  fun getElevation(point: Point): Double {
    // Zoom level for maximum precision
    val zoom = 14

    val slippyCoordinate = convertToSlippy(point, zoom)
    val mapboxTile = retrieveMapboxTile(slippyCoordinate)

    // If a tile does not exist, default to sea level.
    return mapboxTile.image?.let {
      readHeightFromTile(it, slippyCoordinate.pixelX, slippyCoordinate.pixelY)
    } ?: 0.0
  }

  /** Cache of elevation tile retrieved from Mapbox */
  private val mapboxElevationTiles = ConcurrentHashMap<Triple<Int, Int, Int>, MapboxTile>()

  private fun retrieveMapboxTile(slippyCoordinate: SlippyCoordinate): MapboxTile {
    log.info(
        "Retrieving elevation tile at " +
            "(${slippyCoordinate.x}, ${slippyCoordinate.y}, ${slippyCoordinate.zoom})"
    )
    log.info("Number of cached tiles: ${mapboxElevationTiles.size}")

    return mapboxElevationTiles.getOrPut(
        Triple(slippyCoordinate.x, slippyCoordinate.y, slippyCoordinate.zoom)
    ) {
      log.info(
          "Cache miss at elevation tile at " +
              "(${slippyCoordinate.x}, ${slippyCoordinate.y}, ${slippyCoordinate.zoom})"
      )

      // Tile with height data.
      // Reference: https://docs.mapbox.com/data/tilesets/reference/mapbox-terrain-dem-v1/
      val tilesetName = "mapbox-terrain-dem-v1"
      val url = mapboxUrl(slippyCoordinate.mapboxEndpoint(tilesetName))

      val tile = runBlocking {
        try {
          val byteArray = httpClient.get(url).body<ByteArray>()
          ImageIO.read(ByteArrayInputStream(byteArray))
        } catch (e: ClientRequestException) {
          if (e.response.body<MapboxClientErrorResponsePayload>().message == "Tile not found") {
            // This is a special case for this Mapbox tile set when the requested tile is
            // entirely
            // surrounded by water. Return null for no tile, instead of raising an error.
            return@runBlocking null
          }

          // All other errors will be thrown.
          log.error("HTTP ${e.response.status} when fetching Mapbox tile at $url")
          log.info("Mapbox error response payload: ${e.response.bodyAsText()}")
          throw MapboxRequestFailedException(e.response.status)
        }
      }

      MapboxTile(slippyCoordinate.x, slippyCoordinate.y, slippyCoordinate.zoom, tile)
    }
  }

  /**
   * Read pixel value and decode height using the tile specifications. Reference:
   * https://docs.mapbox.com/data/tilesets/guides/access-elevation-data/#decode-data
   */
  private fun readHeightFromTile(tileImage: BufferedImage, pixelX: Int, pixelY: Int): Double {
    val rgb = tileImage.getRGB(pixelX, pixelY)
    val offset = BigDecimal((rgb and 0xFFFFFF) * 0.1)

    val elevation = BigDecimal(-10000) + offset
    return elevation.setScale(1, RoundingMode.HALF_UP).toDouble()
  }

  /**
   * Convert a point to the slippy tile, and the pixel location on the tile Reference:
   * https://wiki.openstreetmap.org/wiki/Slippy_map_tilenames
   */
  private fun convertToSlippy(point: Point, zoom: Int): SlippyCoordinate {
    val longitude = point.x
    val latitude = point.y

    val latRad = Math.toRadians(latitude)
    val n = 2.0.pow(zoom)
    val x = n * ((longitude + 180) / 360)
    val y = n * (1.0 - (ln(tan(latRad) + 1.0 / cos(latRad)) / PI)) / 2.0

    val tileX = floor(x).toInt()
    val tileY = floor(y).toInt()

    val pixelX = ((x - tileX) * 256).toInt()
    val pixelY = ((y - tileY) * 256).toInt()

    return SlippyCoordinate(tileX, tileY, zoom, pixelX, pixelY)
  }

  private fun mapboxUrl(endpoint: String): URL =
      URI("https://api.mapbox.com/$endpoint?access_token=${config.mapbox.apiToken}").toURL()

  /**
   * Wrapper class for the tile image, to distinguish between no cached tile yet vs an empty tile
   */
  data class MapboxTile(
      val x: Int,
      val y: Int,
      val zoom: Int,
      val image: BufferedImage?, // If null, it means that the Mapbox does not have this tile.
  )

  data class SlippyCoordinate(
      val x: Int,
      val y: Int,
      val zoom: Int,
      val pixelX: Int,
      val pixelY: Int,
  ) {
    /** Returns the mapbox endpoint to retrieve the tile */
    fun mapboxEndpoint(tileName: String): String = "v4/mapbox.$tileName/${zoom}/${x}/${y}.pngraw"

    override fun toString(): String {
      return "z=$zoom x=$x y=$y pixel=($pixelX, $pixelY)"
    }
  }

  data class TemporaryTokenRequestPayload(
      val expires: Instant,
      val scopes: List<String>,
  )

  data class TemporaryTokenResponsePayload(val token: String)

  data class RetrieveTokenResponsePayload(val code: String, val token: Token) {
    data class Token(val user: String)
  }

  data class MapboxClientErrorResponsePayload(val message: String)
}
