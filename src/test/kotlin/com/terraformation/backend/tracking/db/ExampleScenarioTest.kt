package com.terraformation.backend.tracking.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.IdentifierGenerator
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.gis.CountryDetector
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ExampleScenarioTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()
  private val identifierGenerator: IdentifierGenerator by lazy {
    IdentifierGenerator(clock, dslContext)
  }
  private val observationLocker: ObservationLocker by lazy { ObservationLocker(dslContext) }
  private val parentStore: ParentStore by lazy { ParentStore(dslContext) }

  private val observationResultsStore: ObservationResultsStore by lazy {
    ObservationResultsStore(dslContext)
  }
  private val observationStore: ObservationStore by lazy {
    ObservationStore(
        clock,
        dslContext,
        eventPublisher,
        observationLocker,
        observationsDao,
        observationPlotConditionsDao,
        observationPlotsDao,
        observationRequestedSubzonesDao,
        parentStore,
    )
  }
  private val plantingSiteStore: PlantingSiteStore by lazy {
    PlantingSiteStore(
        clock,
        CountryDetector(),
        dslContext,
        eventPublisher,
        identifierGenerator,
        monitoringPlotsDao,
        parentStore,
        plantingSeasonsDao,
        plantingSitesDao,
        plantingSubzonesDao,
        plantingZonesDao,
        eventPublisher,
    )
  }

  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertOrganizationUser(role = Role.Admin)
  }

  @Test
  fun runExampleScenario() {
    scenario {
      site(SiteOperation.created) {
        stratum(1) {
          substratum(1, area = 50) {
            plot(111)
            plot(112)
          }
          substratum(2, area = 50) {
            plot(211) //
          }
        }
        stratum(2) {
          substratum(3, area = 200) {
            plot(311) //
          }
        }
      }

      t0DensitySet {
        plot(111) {
          species(0, density = 10)
          species(1, density = 20)
        }
        plot(112) {
          species(0, density = 30)
          species(1, density = 40)
        }
        plot(211) {
          species(0, density = 50)
          species(1, density = 60)
        }
        plot(311) {
          species(0, density = 70)
          species(1, density = 80)
        }
      }

      observation(1) {
        plot(111) {
          // You can explicitly list the 0 values if you want
          species(0, existing = 0, live = 9, dead = 0)
          // Or leave them out
          species(1, live = 2)
          species(unknown, live = 0, dead = 0)
          species(other, live = 0, dead = 0)
        }
        plot(112) {
          species(0, live = 3)
          species(1, live = 5)
        }
        plot(211) {
          species(0, live = 5)
          species(1, live = 6)
        }
        // Default plot type
        plot(311) {
          species(0, live = 6)
          species(1, live = 7)
        }
      }

      expect(observation = 1) {
        survivalRate(12)
        species(0, survivalRate = 14)
        species(1, survivalRate = 10)
        stratum(1) {
          survivalRate(14)
          species(0, survivalRate = 19)
          species(1, survivalRate = 11)
          substratum(1) {
            survivalRate(19)
            species(0, survivalRate = 30)
            species(1, survivalRate = 12)
            plot(111) {
              survivalRate(37)
              species(0, survivalRate = 90)
              species(1, survivalRate = 10)
            }
            plot(112) {
              survivalRate(11)
              species(0, survivalRate = 10)
              species(1, survivalRate = 13)
            }
          }
          substratum(2) {
            // Partial verification (other values ignored)
            survivalRate(10)
            species(0, survivalRate = 10)
            plot(211) {
              species(0, survivalRate = 10) //
            }
          }
        }
      }
    }
  }

  fun scenario(init: ObservationScenario.() -> Unit) {
    ObservationScenario(observationResultsStore, observationStore, plantingSiteStore, this).init()
  }
}
