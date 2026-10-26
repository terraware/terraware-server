package com.terraformation.backend.tracking.model

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.backend.db.GeometryDeserializer
import com.terraformation.backend.db.tracking.DraftPlantingSiteType
import com.terraformation.backend.util.toMultiPolygon
import jakarta.inject.Named
import java.math.BigDecimal
import org.jooq.JSONB
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.MultiPolygon
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.ObjectReader

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
class DraftPlantingSiteMapConverter(private val objectMapper: ObjectMapper) {
  private val reader: ObjectReader =
      objectMapper
          .readerFor(DraftPlantingSiteJson::class.java)
          .withAttribute(GeometryDeserializer.SKIP_VALIDATION, true)

  /** @throws IllegalArgumentException The data is malformed or missing required values. */
  fun parse(data: JSONB): DraftPlantingSiteMap {
    val json =
        try {
          reader.readValue<DraftPlantingSiteJson>(data.data())
        } catch (e: JacksonException) {
          throw IllegalArgumentException("Invalid draft planting site data: ${e.message}", e)
        }

    val siteType = parseSiteType(json.siteType)

    return DraftPlantingSiteMap(
        siteType = siteType,
        siteEditStep = json.siteEditStep,
        boundarySource = json.boundarySource,
        boundary = toMultiPolygon(json.boundary),
        exclusion = toMultiPolygon(json.exclusion),
        strata =
            if (siteType == DraftPlantingSiteType.Simple) {
              emptyList()
            } else {
              json.strata.orEmpty().map { it.toModel() }
            },
    )
  }

  fun generate(map: DraftPlantingSiteMap): JSONB {
    val json =
        DraftPlantingSiteJson(
            boundary = map.boundary,
            boundarySource = map.boundarySource,
            exclusion = map.exclusion,
            plantingSeasons = emptyList(),
            siteEditStep = map.siteEditStep,
            siteType = map.siteType.name.lowercase(),
            strata = map.strata.map { it.toJson() },
        )

    return JSONB.jsonb(objectMapper.writeValueAsString(json))
  }

  private fun parseSiteType(siteType: String?): DraftPlantingSiteType {
    return when (siteType) {
      "simple" -> DraftPlantingSiteType.Simple
      "detailed" -> DraftPlantingSiteType.Detailed
      else -> throw IllegalArgumentException("Unknown siteType $siteType")
    }
  }
}

/** The JSON structure of the `data` blob. Unknown keys from older clients are ignored. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
private data class DraftPlantingSiteJson(
    val boundary: Geometry? = null,
    val boundarySource: String? = null,
    val exclusion: Geometry? = null,
    /** Always empty; planting seasons aren't part of the draft map. */
    val plantingSeasons: List<Any?>? = null,
    val siteEditStep: String? = null,
    val siteType: String? = null,
    @JsonAlias("plantingZones") val strata: List<DraftStratumJson>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
private data class DraftStratumJson(
    /** Written for the client's benefit, but ignored on input; may be a client-assigned value. */
    val id: Any? = null,
    val name: String? = null,
    val boundary: Geometry? = null,
    @JsonAlias("targetPlantingDensity") val initialPlantingDensity: BigDecimal? = null,
    @JsonAlias("plantingSubzones") val substrata: List<DraftSubstratumJson>? = null,
) {
  fun toModel(): DraftStratumModel {
    return DraftStratumModel(
        id = null,
        name = name ?: throw IllegalArgumentException("Stratum name is missing"),
        boundary =
            toMultiPolygon(boundary)
                ?: throw IllegalArgumentException("Stratum is missing boundary"),
        initialPlantingDensity =
            initialPlantingDensity ?: StratumModel.DEFAULT_INITIAL_PLANTING_DENSITY,
        substrata = substrata.orEmpty().map { it.toModel() },
    )
  }
}

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
private data class DraftSubstratumJson(
    /** Written for the client's benefit, but ignored on input; may be a client-assigned value. */
    val id: Any? = null,
    val name: String? = null,
    val boundary: Geometry? = null,
) {
  fun toModel(): DraftSubstratumModel {
    return DraftSubstratumModel(
        id = null,
        name = name ?: throw IllegalArgumentException("Substratum name is missing"),
        boundary =
            toMultiPolygon(boundary)
                ?: throw IllegalArgumentException("Substratum is missing boundary"),
    )
  }
}

private fun DraftStratumModel.toJson() =
    DraftStratumJson(
        id = id?.value,
        name = name,
        boundary = boundary,
        initialPlantingDensity = initialPlantingDensity,
        substrata = substrata.map { it.toJson() },
    )

private fun DraftSubstratumModel.toJson() =
    DraftSubstratumJson(id = id?.value, name = name, boundary = boundary)

private fun toMultiPolygon(geometry: Geometry?): MultiPolygon? {
  val multiPolygon = geometry?.toMultiPolygon() ?: return null
  if (multiPolygon.isEmpty) {
    throw IllegalArgumentException("${multiPolygon.geometryType} has no polygons")
  }

  return multiPolygon
}
