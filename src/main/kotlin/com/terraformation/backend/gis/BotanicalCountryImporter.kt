package com.terraformation.backend.gis

import com.terraformation.backend.db.SRID
import com.terraformation.backend.db.default_schema.tables.references.BOTANICAL_COUNTRIES
import com.terraformation.backend.gis.event.BotanicalCountriesImportedEvent
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named
import java.io.InputStream
import java.net.URI
import org.geotools.api.feature.Feature
import org.geotools.feature.FeatureIterator
import org.geotools.geojson.feature.FeatureJSON
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.PrecisionModel
import org.locationtech.jts.geom.util.GeometryFixer
import org.locationtech.jts.precision.GeometryPrecisionReducer
import org.springframework.context.ApplicationEventPublisher

@Named
class BotanicalCountryImporter(
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
) {
  companion object {
    val defaultGeoJsonUrl =
        URI(
            "https://raw.githubusercontent.com/tdwg/wgsrpd/refs/heads/master/geojson/level3.geojson"
        )
  }

  private val log = perClassLogger()

  fun importBotanicalCountries(inputStream: InputStream) {
    val features = FeatureJSON().readFeatureCollection(inputStream).features()
    val precisionReducer = GeometryPrecisionReducer(PrecisionModel(1000000.0))
    precisionReducer.setChangePrecisionModel(true)

    dslContext.transaction { _ ->
      val level3Codes = mutableSetOf<String>()

      val botanicalCountriesRows = features.use {
        features
            .asSequence()
            .map { feature ->
              val level3Code = feature.getProperty("LEVEL3_COD").value.toString()
              val geometry = feature.defaultGeometryProperty.value
              if (geometry !is Geometry) {
                throw IllegalStateException(
                    "$level3Code geometry is of type ${geometry?.javaClass?.name}"
                )
              }

              val validGeometry =
                  if (geometry.isValid) {
                    geometry
                  } else {
                    log.debug("Geometry for $level3Code is invalid")
                    GeometryFixer(geometry).result
                  }

              val precisionReducedGeometry = precisionReducer.reduce(validGeometry)
              precisionReducedGeometry.srid = SRID.LONG_LAT

              level3Codes.add(level3Code)

              DSL.row(
                  feature.getProperty("LEVEL3_NAM").value.toString(),
                  level3Code,
                  feature.getProperty("LEVEL2_COD").value.toString().toInt(),
                  feature.getProperty("LEVEL1_COD").value.toString().toInt(),
                  precisionReducedGeometry,
              )
            }
            .toList()
      }

      log.info("Inserting botanical countries into database")

      with(BOTANICAL_COUNTRIES) {
        dslContext
            .insertInto(
                BOTANICAL_COUNTRIES,
                NAME,
                LEVEL3_CODE,
                LEVEL2_CODE,
                LEVEL1_CODE,
                BOUNDARY,
            )
            .valuesOfRows(botanicalCountriesRows)
            .onConflict(LEVEL3_CODE)
            .doUpdate()
            .set(NAME, DSL.excluded(NAME))
            .set(LEVEL2_CODE, DSL.excluded(LEVEL2_CODE))
            .set(LEVEL1_CODE, DSL.excluded(LEVEL1_CODE))
            .set(BOUNDARY, DSL.excluded(BOUNDARY))
            .execute()

        dslContext.deleteFrom(BOTANICAL_COUNTRIES).where(LEVEL3_CODE.notIn(level3Codes)).execute()

        eventPublisher.publishEvent(BotanicalCountriesImportedEvent())
      }
    }
  }

  private fun <T : Feature> FeatureIterator<T>.asSequence() = sequence {
    while (hasNext()) {
      yield(next())
    }
  }
}
