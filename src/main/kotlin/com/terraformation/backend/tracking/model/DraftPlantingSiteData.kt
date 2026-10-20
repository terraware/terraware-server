package com.terraformation.backend.tracking.model

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.ObjectReader
import com.terraformation.backend.db.GeometryDeserializer
import com.terraformation.backend.db.tracking.DraftPlantingSiteType
import com.terraformation.backend.util.toMultiPolygon
import jakarta.inject.Named
import java.io.IOException
import java.math.BigDecimal
import org.jooq.JSONB
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.MultiPolygon

/**
 * Converts between the `data` JSON blob stored with a draft planting site and
 * [DraftPlantingSiteMap].
 *
 * Parsing accepts both the current key names (`strata`, `substrata`, `initialPlantingDensity`) and
 * the legacy ones (`plantingZones`, `plantingSubzones`, `targetPlantingDensity`). Client-assigned
 * IDs, full names, planting completion flags, and planting seasons are not retained.
 *
 * Geometries are not checked for topological validity since drafts may be works in progress.
 */
@Named
class DraftPlantingSiteData(private val objectMapper: ObjectMapper) {
  private val geometryReader: ObjectReader =
      objectMapper
          .readerFor(Geometry::class.java)
          .withAttribute(GeometryDeserializer.SKIP_VALIDATION, true)

  /** @throws IllegalArgumentException The data is malformed or missing required values. */
  fun parse(data: JSONB): DraftPlantingSiteMap {
    return try {
      parseMap(objectMapper.readTree(data.data()))
    } catch (e: IOException) {
      throw IllegalArgumentException("Invalid draft planting site data: ${e.message}", e)
    }
  }

  private fun parseMap(root: JsonNode): DraftPlantingSiteMap {
    val siteType = parseSiteType(root.get("siteType"))

    val strata =
        if (siteType == DraftPlantingSiteType.Simple) {
          emptyList()
        } else {
          parseList(root.get("strata") ?: root.get("plantingZones"), "Strata") { parseStratum(it) }
        }

    return DraftPlantingSiteMap(
        siteType = siteType,
        siteEditStep = root.textOrNull("siteEditStep"),
        boundarySource = root.textOrNull("boundarySource"),
        boundary = parseGeometry(root.get("boundary")),
        exclusion = parseGeometry(root.get("exclusion")),
        strata = strata,
    )
  }

  fun generate(map: DraftPlantingSiteMap): JSONB {
    val root = objectMapper.createObjectNode()

    map.boundary?.let { root.set<JsonNode>("boundary", objectMapper.valueToTree(it)) }
    map.exclusion?.let { root.set<JsonNode>("exclusion", objectMapper.valueToTree(it)) }
    map.boundarySource?.let { root.put("boundarySource", it) }
    map.siteEditStep?.let { root.put("siteEditStep", it) }
    root.put("siteType", map.siteType.name.lowercase())
    root.putArray("plantingSeasons")

    val strata = root.putArray("strata")
    map.strata.forEach { stratum ->
      val stratumNode = strata.addObject()
      stratum.id?.let { stratumNode.put("id", it.value) }
      stratumNode.put("name", stratum.name)
      stratumNode.set<JsonNode>("boundary", objectMapper.valueToTree(stratum.boundary))
      stratumNode.put("initialPlantingDensity", stratum.initialPlantingDensity)

      val substrata = stratumNode.putArray("substrata")
      stratum.substrata.forEach { substratum ->
        val substratumNode = substrata.addObject()
        substratum.id?.let { substratumNode.put("id", it.value) }
        substratumNode.put("name", substratum.name)
        substratumNode.set<JsonNode>("boundary", objectMapper.valueToTree(substratum.boundary))
      }
    }

    return JSONB.jsonb(objectMapper.writeValueAsString(root))
  }

  private fun parseSiteType(node: JsonNode?): DraftPlantingSiteType {
    return when (val text = node?.takeUnless { it.isNull }?.asText()) {
      "simple" -> DraftPlantingSiteType.Simple
      "detailed" -> DraftPlantingSiteType.Detailed
      else -> throw IllegalArgumentException("Unknown siteType $text")
    }
  }

  private fun parseStratum(node: JsonNode): DraftStratumModel {
    val density = node.get("initialPlantingDensity") ?: node.get("targetPlantingDensity")
    val substrata = node.get("substrata") ?: node.get("plantingSubzones")

    return DraftStratumModel(
        id = null,
        name = parseName(node, "Stratum"),
        boundary =
            parseGeometry(node.get("boundary"))
                ?: throw IllegalArgumentException("Stratum is missing boundary"),
        initialPlantingDensity = parseDensity(density),
        substrata = parseList(substrata, "Substrata") { parseSubstratum(it) },
    )
  }

  private fun parseSubstratum(node: JsonNode): DraftSubstratumModel {
    return DraftSubstratumModel(
        id = null,
        name = parseName(node, "Substratum"),
        boundary =
            parseGeometry(node.get("boundary"))
                ?: throw IllegalArgumentException("Substratum is missing boundary"),
    )
  }

  /** Treats a missing or null list as empty, but rejects values that aren't arrays. */
  private fun <T> parseList(node: JsonNode?, kind: String, parseElement: (JsonNode) -> T): List<T> {
    return when {
      node == null || node.isNull -> emptyList()
      node.isArray -> node.map(parseElement)
      else -> throw IllegalArgumentException("$kind must be an array")
    }
  }

  private fun parseName(node: JsonNode, kind: String): String {
    return node.get("name")?.textValue()
        ?: throw IllegalArgumentException("$kind name is missing or not a string")
  }

  /** Accepts numbers and numeric strings, matching how the migration converted existing data. */
  private fun parseDensity(node: JsonNode?): BigDecimal {
    return when {
      node == null || node.isNull -> StratumModel.DEFAULT_INITIAL_PLANTING_DENSITY
      node.isNumber -> node.decimalValue()
      node.isTextual -> node.textValue().trim().toBigDecimalOrNull()
      else -> null
    } ?: throw IllegalArgumentException("Initial planting density is not a number")
  }

  private fun parseGeometry(node: JsonNode?): MultiPolygon? {
    if (node == null || node.isNull) {
      return null
    }

    val geometry = geometryReader.readValue<Geometry?>(node)?.toMultiPolygon() ?: return null
    if (geometry.isEmpty) {
      throw IllegalArgumentException("${geometry.geometryType} has no polygons")
    }

    return geometry
  }

  private fun JsonNode.textOrNull(field: String): String? {
    return get(field)?.takeUnless { it.isNull }?.asText()
  }
}
