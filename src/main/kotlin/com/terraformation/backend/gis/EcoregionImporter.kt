package com.terraformation.backend.gis

import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.tables.references.COUNTRIES
import com.terraformation.backend.db.default_schema.tables.references.ECOREGIONS
import com.terraformation.backend.db.default_schema.tables.references.ECOREGION_COUNTRIES
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.tracking.model.Shapefile
import jakarta.inject.Named
import java.net.URI
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.PrecisionModel
import org.locationtech.jts.geom.util.GeometryFixer
import org.locationtech.jts.precision.GeometryPrecisionReducer

/**
 * Imports the WWF/Resolve ecoregions shapefile into the database. The shapefile (which is packaged
 * in a zip archive) contains a feature collection where each feature is a single ecoregion with its
 * geographic boundary and a number of properties that identify which ecoregion it is.
 *
 * The format (particularly the list of property names) does not appear to be documented anywhere.
 * We extract all the properties that seem like they might potentially be useful. We assume the
 * "objectid" property is intended to be a stable ID that stays the same across revisions of the
 * data.
 */
@Named
class EcoregionImporter(
    private val countryDetector: CountryDetector,
    private val dslContext: DSLContext,
) {
  companion object {
    /** URL of the official distribution of the Ecoregions zip file. */
    val defaultZipFileUrl = URI("https://storage.googleapis.com/teow2016/Ecoregions2017.zip")
  }

  private val log = perClassLogger()

  fun importEcoregions(zipFilePath: Path) {
    val validCountryCodes =
        dslContext.select(COUNTRIES.CODE).from(COUNTRIES).fetchSet(COUNTRIES.CODE.asNonNullable())

    log.info("Parsing ecoregions shapefile")

    val shapefiles = Shapefile.fromZipFile(zipFilePath)
    if (shapefiles.size != 1) {
      throw IllegalArgumentException("Expected 1 shapefile in zip, found ${shapefiles.size}")
    }
    val features = shapefiles.first().features

    log.info("Preprocessing ecoregion geometries")

    val geometriesByObjectId = mutableMapOf<String, Geometry>()
    val precisionReducer = GeometryPrecisionReducer(PrecisionModel(1000000.0))
    precisionReducer.setChangePrecisionModel(true)

    // GeometryFixer is CPU-intensive for large geometries. Make use of all the available CPUs
    // to process features in parallel.

    val rows =
        runBlocking(Dispatchers.Default) {
          features
              .map { feature ->
                async {
                  val rawGeometry =
                      if (feature.geometry.isValid) {
                        feature.geometry
                      } else {
                        GeometryFixer(feature.geometry).result
                      }

                  val geometry = precisionReducer.reduce(rawGeometry)

                  val objectId = feature.properties["objectid"]!!
                  geometriesByObjectId[objectId] = geometry

                  DSL.row(
                      objectId,
                      feature.properties["eco_name"],
                      feature.properties["biome_num"],
                      feature.properties["biome_name"],
                      feature.properties["realm"],
                      feature.properties["eco_biome_"],
                      feature.properties["eco_id"],
                      geometry,
                  )
                }
              }
              .awaitAll()
        }

    log.info("Inserting ecoregions into database")

    dslContext.transaction { _ ->
      val ecoregionIdsByObjectId =
          with(ECOREGIONS) {
            dslContext
                .insertInto(
                    ECOREGIONS,
                    OBJECT_ID,
                    ECO_NAME,
                    BIOME_NUMBER,
                    BIOME_NAME,
                    REALM,
                    ECO_BIOME_CODE,
                    ECO_ID,
                    BOUNDARY,
                )
                .valuesOfRows(rows)
                .onConflict(OBJECT_ID)
                .doUpdate()
                .set(ECO_NAME, DSL.excluded(ECO_NAME))
                .set(BIOME_NUMBER, DSL.excluded(BIOME_NUMBER))
                .set(BIOME_NAME, DSL.excluded(BIOME_NAME))
                .set(REALM, DSL.excluded(REALM))
                .set(ECO_BIOME_CODE, DSL.excluded(ECO_BIOME_CODE))
                .set(ECO_ID, DSL.excluded(ECO_ID))
                .set(BOUNDARY, DSL.excluded(BOUNDARY))
                .execute()

            dslContext
                .deleteFrom(ECOREGIONS)
                .where(OBJECT_ID.notIn(geometriesByObjectId.keys))
                .execute()

            dslContext
                .select(OBJECT_ID, ID)
                .from(ECOREGIONS)
                .fetchMap(OBJECT_ID.asNonNullable(), ID.asNonNullable())
          }

      log.info("Imported raw ecoregions data; calculating ecoregion-country mappings")

      // Calculating the country list can be expensive for complex geometries. Make use of all the
      // available CPUs to calculate regions' country lists in parallel.

      val ecoregionCountriesRows =
          runBlocking(Dispatchers.Default) {
            features
                .map { feature ->
                  async {
                    log.debug("Calculating for ${feature.properties["eco_name"]}")

                    val objectId = feature.properties["objectid"]!!
                    val ecoregionId = ecoregionIdsByObjectId[objectId]
                    val countries =
                        countryDetector.getCountries(geometriesByObjectId.getValue(objectId))

                    countries
                        .filter { it in validCountryCodes }
                        .map { countryCode -> DSL.row(ecoregionId, countryCode) }
                  }
                }
                .awaitAll()
                .flatten()
          }

      dslContext.deleteFrom(ECOREGION_COUNTRIES).execute()

      dslContext
          .insertInto(
              ECOREGION_COUNTRIES,
              ECOREGION_COUNTRIES.ECOREGION_ID,
              ECOREGION_COUNTRIES.COUNTRY_CODE,
          )
          .valuesOfRows(ecoregionCountriesRows)
          .execute()
    }
  }
}
