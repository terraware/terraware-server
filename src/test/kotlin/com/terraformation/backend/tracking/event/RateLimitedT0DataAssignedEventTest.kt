package com.terraformation.backend.tracking.event

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.tracking.model.PlotT0DensityChangedEventModel
import com.terraformation.backend.tracking.model.SpeciesDensityChangedEventModel
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RateLimitedT0DataAssignedEventTest {
  private val organizationId = OrganizationId(1)
  private val plantingSiteId = PlantingSiteId(10)

  @Test
  fun `combine for different monitoring plots`() {
    val existingEvent =
        RateLimitedT0DataAssignedEvent(
            organizationId = organizationId,
            plantingSiteId = plantingSiteId,
            listOf(
                PlotT0DensityChangedEventModel(
                    MonitoringPlotId(1),
                    1L,
                    speciesDensityChanges =
                        listOf(
                            SpeciesDensityChangedEventModel(
                                speciesId = SpeciesId(1),
                                speciesScientificName = "Species 1",
                                previousPlotDensity = BigDecimal.valueOf(1),
                                newPlotDensity = BigDecimal.valueOf(2),
                            )
                        ),
                ),
            ),
        )

    val newEvent =
        RateLimitedT0DataAssignedEvent(
            organizationId = organizationId,
            plantingSiteId = plantingSiteId,
            listOf(
                PlotT0DensityChangedEventModel(
                    MonitoringPlotId(2),
                    2L,
                    speciesDensityChanges =
                        listOf(
                            SpeciesDensityChangedEventModel(
                                speciesId = SpeciesId(1),
                                speciesScientificName = "Species 1",
                                previousPlotDensity = BigDecimal.valueOf(1),
                                newPlotDensity = BigDecimal.valueOf(2),
                            )
                        ),
                ),
            ),
        )

    assertEquals(
        RateLimitedT0DataAssignedEvent(
            organizationId = organizationId,
            plantingSiteId = plantingSiteId,
            listOf(
                PlotT0DensityChangedEventModel(
                    MonitoringPlotId(1),
                    1L,
                    speciesDensityChanges =
                        listOf(
                            SpeciesDensityChangedEventModel(
                                speciesId = SpeciesId(1),
                                speciesScientificName = "Species 1",
                                previousPlotDensity = BigDecimal.valueOf(1),
                                newPlotDensity = BigDecimal.valueOf(2),
                            )
                        ),
                ),
                PlotT0DensityChangedEventModel(
                    MonitoringPlotId(2),
                    2L,
                    speciesDensityChanges =
                        listOf(
                            SpeciesDensityChangedEventModel(
                                speciesId = SpeciesId(1),
                                speciesScientificName = "Species 1",
                                previousPlotDensity = BigDecimal.valueOf(1),
                                newPlotDensity = BigDecimal.valueOf(2),
                            )
                        ),
                ),
            ),
        ),
        newEvent.combine(existingEvent),
    )
  }

  @Test
  fun `combine for same monitoring plot, diff species`() {
    val existingEvent =
        RateLimitedT0DataAssignedEvent(
            organizationId = organizationId,
            plantingSiteId = plantingSiteId,
            listOf(
                PlotT0DensityChangedEventModel(
                    MonitoringPlotId(1),
                    1L,
                    speciesDensityChanges =
                        listOf(
                            SpeciesDensityChangedEventModel(
                                speciesId = SpeciesId(1),
                                speciesScientificName = "Species 1",
                                previousPlotDensity = BigDecimal.valueOf(1),
                                newPlotDensity = BigDecimal.valueOf(2),
                            )
                        ),
                ),
            ),
        )

    val newEvent =
        RateLimitedT0DataAssignedEvent(
            organizationId = organizationId,
            plantingSiteId = plantingSiteId,
            listOf(
                PlotT0DensityChangedEventModel(
                    MonitoringPlotId(1),
                    1L,
                    speciesDensityChanges =
                        listOf(
                            SpeciesDensityChangedEventModel(
                                speciesId = SpeciesId(2),
                                speciesScientificName = "Species 2",
                                previousPlotDensity = BigDecimal.valueOf(3),
                                newPlotDensity = BigDecimal.valueOf(4),
                            )
                        ),
                ),
            ),
        )

    assertEquals(
        RateLimitedT0DataAssignedEvent(
            organizationId = organizationId,
            plantingSiteId = plantingSiteId,
            listOf(
                PlotT0DensityChangedEventModel(
                    MonitoringPlotId(1),
                    1L,
                    speciesDensityChanges =
                        listOf(
                            SpeciesDensityChangedEventModel(
                                speciesId = SpeciesId(1),
                                speciesScientificName = "Species 1",
                                previousPlotDensity = BigDecimal.valueOf(1),
                                newPlotDensity = BigDecimal.valueOf(2),
                            ),
                            SpeciesDensityChangedEventModel(
                                speciesId = SpeciesId(2),
                                speciesScientificName = "Species 2",
                                previousPlotDensity = BigDecimal.valueOf(3),
                                newPlotDensity = BigDecimal.valueOf(4),
                            ),
                        ),
                ),
            ),
        ),
        newEvent.combine(existingEvent),
    )
  }

  @Test
  fun `combine for same monitoring plot, same species`() {
    val existingEvent =
        RateLimitedT0DataAssignedEvent(
            organizationId = organizationId,
            plantingSiteId = plantingSiteId,
            listOf(
                PlotT0DensityChangedEventModel(
                    MonitoringPlotId(1),
                    1L,
                    speciesDensityChanges =
                        listOf(
                            SpeciesDensityChangedEventModel(
                                speciesId = SpeciesId(1),
                                speciesScientificName = "Species 1",
                                previousPlotDensity = BigDecimal.valueOf(1),
                                newPlotDensity = BigDecimal.valueOf(2),
                            )
                        ),
                ),
            ),
        )

    val newEvent =
        RateLimitedT0DataAssignedEvent(
            organizationId = organizationId,
            plantingSiteId = plantingSiteId,
            listOf(
                PlotT0DensityChangedEventModel(
                    MonitoringPlotId(1),
                    1L,
                    speciesDensityChanges =
                        listOf(
                            SpeciesDensityChangedEventModel(
                                speciesId = SpeciesId(1),
                                speciesScientificName = "Species 1",
                                previousPlotDensity = BigDecimal.valueOf(3),
                                newPlotDensity = BigDecimal.valueOf(4),
                            )
                        ),
                ),
            ),
        )

    assertEquals(
        RateLimitedT0DataAssignedEvent(
            organizationId = organizationId,
            plantingSiteId = plantingSiteId,
            listOf(
                PlotT0DensityChangedEventModel(
                    MonitoringPlotId(1),
                    1L,
                    speciesDensityChanges =
                        listOf(
                            SpeciesDensityChangedEventModel(
                                speciesId = SpeciesId(1),
                                speciesScientificName = "Species 1",
                                previousPlotDensity = BigDecimal.valueOf(1),
                                newPlotDensity = BigDecimal.valueOf(4),
                            ),
                        ),
                ),
            ),
        ),
        newEvent.combine(existingEvent),
    )
  }

  @Test
  fun `combine with lots of data`() {
    val existingEvent =
        RateLimitedT0DataAssignedEvent(
            organizationId = organizationId,
            plantingSiteId = plantingSiteId,
            listOf(
                PlotT0DensityChangedEventModel(
                    MonitoringPlotId(1),
                    1L,
                    speciesDensityChanges =
                        listOf(
                            SpeciesDensityChangedEventModel(
                                speciesId = SpeciesId(1),
                                speciesScientificName = "Species 1",
                                previousPlotDensity = BigDecimal.valueOf(10),
                                newPlotDensity = BigDecimal.valueOf(20),
                            ),
                        ),
                ),
                PlotT0DensityChangedEventModel(
                    MonitoringPlotId(2),
                    2L,
                    speciesDensityChanges =
                        listOf(
                            SpeciesDensityChangedEventModel(
                                speciesId = SpeciesId(1),
                                speciesScientificName = "Species 1",
                                previousPlotDensity = BigDecimal.valueOf(1),
                                newPlotDensity = BigDecimal.valueOf(2),
                            ),
                            SpeciesDensityChangedEventModel(
                                speciesId = SpeciesId(2),
                                speciesScientificName = "Species 2",
                                previousPlotDensity = BigDecimal.valueOf(3),
                                newPlotDensity = BigDecimal.valueOf(4),
                            ),
                        ),
                ),
            ),
        )

    val newEvent =
        RateLimitedT0DataAssignedEvent(
            organizationId = organizationId,
            plantingSiteId = plantingSiteId,
            listOf(
                PlotT0DensityChangedEventModel(
                    MonitoringPlotId(2),
                    2L,
                    speciesDensityChanges =
                        listOf(
                            SpeciesDensityChangedEventModel(
                                speciesId = SpeciesId(2),
                                speciesScientificName = "Species 2",
                                previousPlotDensity = BigDecimal.valueOf(4),
                                newPlotDensity = BigDecimal.valueOf(5),
                            ),
                            SpeciesDensityChangedEventModel(
                                speciesId = SpeciesId(3),
                                speciesScientificName = "Species 3",
                                previousPlotDensity = BigDecimal.valueOf(6),
                                newPlotDensity = BigDecimal.valueOf(7),
                            ),
                        ),
                ),
                PlotT0DensityChangedEventModel(
                    MonitoringPlotId(3),
                    3L,
                    speciesDensityChanges =
                        listOf(
                            SpeciesDensityChangedEventModel(
                                speciesId = SpeciesId(1),
                                speciesScientificName = "Species 1",
                                previousPlotDensity = BigDecimal.valueOf(8),
                                newPlotDensity = BigDecimal.valueOf(9),
                            ),
                            SpeciesDensityChangedEventModel(
                                speciesId = SpeciesId(4),
                                speciesScientificName = "Species 4",
                                previousPlotDensity = BigDecimal.valueOf(11),
                                newPlotDensity = BigDecimal.valueOf(12),
                            ),
                        ),
                ),
            ),
        )

    assertEquals(
        RateLimitedT0DataAssignedEvent(
            organizationId = organizationId,
            plantingSiteId = plantingSiteId,
            listOf(
                PlotT0DensityChangedEventModel(
                    MonitoringPlotId(1),
                    1L,
                    speciesDensityChanges =
                        listOf(
                            SpeciesDensityChangedEventModel(
                                speciesId = SpeciesId(1),
                                speciesScientificName = "Species 1",
                                previousPlotDensity = BigDecimal.valueOf(10),
                                newPlotDensity = BigDecimal.valueOf(20),
                            ),
                        ),
                ),
                PlotT0DensityChangedEventModel(
                    MonitoringPlotId(2),
                    2L,
                    speciesDensityChanges =
                        listOf(
                            SpeciesDensityChangedEventModel(
                                speciesId = SpeciesId(1),
                                speciesScientificName = "Species 1",
                                previousPlotDensity = BigDecimal.valueOf(1),
                                newPlotDensity = BigDecimal.valueOf(2),
                            ),
                            SpeciesDensityChangedEventModel(
                                speciesId = SpeciesId(2),
                                speciesScientificName = "Species 2",
                                previousPlotDensity = BigDecimal.valueOf(3),
                                newPlotDensity = BigDecimal.valueOf(5),
                            ),
                            SpeciesDensityChangedEventModel(
                                speciesId = SpeciesId(3),
                                speciesScientificName = "Species 3",
                                previousPlotDensity = BigDecimal.valueOf(6),
                                newPlotDensity = BigDecimal.valueOf(7),
                            ),
                        ),
                ),
                PlotT0DensityChangedEventModel(
                    MonitoringPlotId(3),
                    3L,
                    speciesDensityChanges =
                        listOf(
                            SpeciesDensityChangedEventModel(
                                speciesId = SpeciesId(1),
                                speciesScientificName = "Species 1",
                                previousPlotDensity = BigDecimal.valueOf(8),
                                newPlotDensity = BigDecimal.valueOf(9),
                            ),
                            SpeciesDensityChangedEventModel(
                                speciesId = SpeciesId(4),
                                speciesScientificName = "Species 4",
                                previousPlotDensity = BigDecimal.valueOf(11),
                                newPlotDensity = BigDecimal.valueOf(12),
                            ),
                        ),
                ),
            ),
        ),
        newEvent.combine(existingEvent),
    )
  }
}
