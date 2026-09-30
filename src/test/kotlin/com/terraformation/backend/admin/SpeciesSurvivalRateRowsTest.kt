package com.terraformation.backend.admin

import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.tracking.model.ObservationSpeciesResultsModel
import java.math.BigDecimal
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpeciesSurvivalRateRowsTest {
  private val speciesIdA = SpeciesId(1)
  private val speciesIdB = SpeciesId(2)
  private val speciesIdC = SpeciesId(3)
  private val speciesNames = mapOf(speciesIdA to "Aaa", speciesIdB to "Bbb", speciesIdC to "Ccc")

  @Test
  fun `uses newest source for latest values and newest non-null rate for latest available`() {
    val observationId1 = ObservationId(1)
    val observationId2 = ObservationId(2)
    val observationId3 = ObservationId(3)
    val time1 = Instant.ofEpochSecond(1)
    val time2 = Instant.ofEpochSecond(2)
    val time3 = Instant.ofEpochSecond(3)

    val sources =
        listOf(
            SpeciesResultsSource(
                observationId3,
                time3,
                listOf(
                    species(speciesIdA, survivalRate = 80, t0Density = BigDecimal.TEN),
                    species(speciesIdB, survivalRate = null),
                    species(null, survivalRate = null, certainty = RecordedSpeciesCertainty.Other),
                ),
            ),
            SpeciesResultsSource(
                observationId2,
                time2,
                listOf(
                    species(speciesIdA, survivalRate = 90),
                    species(speciesIdB, survivalRate = 70),
                ),
            ),
            SpeciesResultsSource(
                observationId1,
                time1,
                listOf(species(speciesIdC, survivalRate = 60)),
            ),
        )

    assertEquals(
        listOf(
            SpeciesSurvivalRateRow(
                latestAvailableObservationCompletedTime = time3.toString(),
                latestAvailableObservationId = observationId3,
                latestAvailableSurvivalRate = 80,
                latestObservationSurvivalRate = 80,
                latestObservationT0Density = BigDecimal.TEN,
                latestObservationTotalLive = 5,
                latestObservationTotalPlants = 8,
                scientificName = "Aaa",
                speciesId = speciesIdA,
            ),
            SpeciesSurvivalRateRow(
                latestAvailableObservationCompletedTime = time2.toString(),
                latestAvailableObservationId = observationId2,
                latestAvailableSurvivalRate = 70,
                latestObservationSurvivalRate = null,
                latestObservationT0Density = null,
                latestObservationTotalLive = 5,
                latestObservationTotalPlants = 8,
                scientificName = "Bbb",
                speciesId = speciesIdB,
            ),
            SpeciesSurvivalRateRow(
                latestAvailableObservationCompletedTime = time1.toString(),
                latestAvailableObservationId = observationId1,
                latestAvailableSurvivalRate = 60,
                latestObservationSurvivalRate = null,
                latestObservationT0Density = null,
                latestObservationTotalLive = null,
                latestObservationTotalPlants = null,
                scientificName = "Ccc",
                speciesId = speciesIdC,
            ),
        ),
        speciesSurvivalRateRows(sources, speciesNames, ::plotSpeciesTotalPlants),
    )
  }

  @Test
  fun `survival rate text falls back to latest available rate`() {
    val rows =
        speciesSurvivalRateRows(
            listOf(
                SpeciesResultsSource(
                    ObservationId(2),
                    null,
                    listOf(
                        species(speciesIdA, survivalRate = 80),
                        species(speciesIdB, survivalRate = null),
                        species(speciesIdC, survivalRate = null),
                    ),
                ),
                SpeciesResultsSource(
                    ObservationId(1),
                    null,
                    listOf(species(speciesIdB, survivalRate = 70)),
                ),
            ),
            speciesNames,
            ::aggregateSpeciesTotalPlants,
        )

    assertEquals(listOf("80%", "— (70% in obs 1)", "—"), rows.map { it.survivalRateText })
  }

  @Test
  fun `species total plants matches the containing entity's definition`() {
    val sources =
        listOf(
            SpeciesResultsSource(
                ObservationId(1),
                null,
                listOf(species(speciesIdA, survivalRate = 80)),
            )
        )

    assertEquals(
        8,
        speciesSurvivalRateRows(sources, speciesNames, ::plotSpeciesTotalPlants)
            .single()
            .latestObservationTotalPlants,
        "Plot total includes live, existing, and dead plants",
    )
    assertEquals(
        6,
        speciesSurvivalRateRows(sources, speciesNames, ::aggregateSpeciesTotalPlants)
            .single()
            .latestObservationTotalPlants,
        "Aggregate total includes live and dead plants",
    )
  }

  private fun species(
      speciesId: SpeciesId?,
      survivalRate: Int?,
      t0Density: BigDecimal? = null,
      certainty: RecordedSpeciesCertainty = RecordedSpeciesCertainty.Known,
  ) =
      ObservationSpeciesResultsModel(
          certainty = certainty,
          latestLive = 5,
          permanentLive = 5,
          speciesId = speciesId,
          speciesName = null,
          survivalRate = survivalRate,
          t0Density = t0Density,
          totalDead = 1,
          totalExisting = 2,
          totalLive = 5,
          totalPlants = 8,
      )
}
