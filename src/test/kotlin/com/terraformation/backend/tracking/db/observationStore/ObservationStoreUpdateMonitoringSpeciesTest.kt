package com.terraformation.backend.tracking.db.observationStore

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.tracking.event.MonitoringSpeciesTotalsEditedEvent
import com.terraformation.backend.tracking.event.MonitoringSpeciesTotalsEditedEventValues
import com.terraformation.backend.tracking.scenario.ObservationScenario
import com.terraformation.backend.tracking.scenario.ObservationScenario.Companion.OTHER
import com.terraformation.backend.tracking.scenario.ObservationScenario.Companion.UNKNOWN
import com.terraformation.backend.util.nullSafeMapOf
import com.terraformation.backend.util.nullSafeMutableMapOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.security.access.AccessDeniedException

/**
 * Tests the behavior of editing plant counts on monitoring observations, primarily verifying that
 * survival rates are properly updated.
 *
 * Most of these tests follow a similar pattern:
 * 1. Declare the site map.
 * 2. Define the per-plot densities in a nested Map (usually called `densities`).
 * 3. Define the per-plot live plant counts in a nested Map (usually called `obsLive`).
 * 4. Populate the t0 densities from the values in `densities`.
 * 5. Create the observation(s) using the live plant counts from `obsLive`.
 * 6. Define and call a function `assertResultsMatchNumbersFromObsLive` that checks that the
 *    observation results match the numbers in `obsLive` and `densities`.
 * 7. Modify one or more of the live plant counts in `obsLive` to reflect the observation edit under
 *    test.
 * 8. Call `observationEdited` to apply the live plant count changes to the observation.
 * 9. Call `assertResultsMatchNumbersFromObsLive` again. Since we've modified `obsLive`, this will
 *    verify that the edits were applied correctly, that is, that the numbers in the observation
 *    results are based on the updated plant counts rather than the original ones.
 *
 * This is just the general pattern. Not all the tests look _exactly_ like the above; there will be
 * additional steps depending on what the test is checking, e.g., there might be a map edit.
 */
class ObservationStoreUpdateMonitoringSpeciesTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val eventPublisher = TestEventPublisher()

  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertOrganizationUser(role = Role.Admin)
  }

  @Test
  fun `updates plant counts for known species`() {
    scenario {
      siteCreated { stratum(1) { substratum(1) { plot(1) } } }

      val density = 100

      t0DensitySet { plot(1) { species(0, density = density) } }

      var liveCount = 6
      var deadCount = 7
      var existingCount = 8

      observation(1) {
        plot(1) { species(0, live = liveCount, dead = deadCount, existing = existingCount) }
      }

      fun assertResultsMatchPlantCounts() {
        expectResults(observation = 1) {
          survivalRate(percent(liveCount, density))
          species(
              0,
              survivalRate = percent(liveCount, density),
              totalLive = liveCount,
              totalDead = deadCount,
              totalExisting = existingCount,
          )
          stratum(1) {
            survivalRate(percent(liveCount, density))
            species(
                0,
                survivalRate = percent(liveCount, density),
                totalLive = liveCount,
                totalDead = deadCount,
                totalExisting = existingCount,
            )
            substratum(1) {
              survivalRate(percent(liveCount, density))
              species(
                  0,
                  survivalRate = percent(liveCount, density),
                  totalLive = liveCount,
                  totalDead = deadCount,
                  totalExisting = existingCount,
              )
              plot(1) {
                survivalRate(percent(liveCount, density))
                species(
                    0,
                    survivalRate = percent(liveCount, density),
                    totalLive = liveCount,
                    totalDead = deadCount,
                    totalExisting = existingCount,
                )
              }
            }
          }
        }
      }

      assertResultsMatchPlantCounts()

      liveCount = 1
      deadCount = 2
      existingCount = 3

      observationEdited(1) {
        plot(1) { species(0, live = liveCount, dead = deadCount, existing = existingCount) }
      }

      assertResultsMatchPlantCounts()
    }
  }

  @Test
  fun `updates plant counts for unknown and Other species`() {
    scenario {
      siteCreated { stratum(1) { substratum(1) { plot(1) } } }

      val density = 100

      t0DensitySet { plot(1) { species(0, density = density) } }

      var otherLiveCount = 11
      var otherDeadCount = 12
      var otherExistingCount = 13
      var unknownLiveCount = 14
      var unknownDeadCount = 15
      var unknownExistingCount = 16

      observation(1) {
        plot(1) {
          species(
              OTHER,
              live = otherLiveCount,
              dead = otherDeadCount,
              existing = otherExistingCount,
          )
          species(
              UNKNOWN,
              live = unknownLiveCount,
              dead = unknownDeadCount,
              existing = unknownExistingCount,
          )
        }
      }

      fun assertResultsMatchPlantCounts() {
        expectResults(observation = 1) {
          survivalRate(percent(otherLiveCount + unknownLiveCount, density))
          // Unknown species are not included at the site level, just known and Other ones.
          species(
              OTHER,
              survivalRate = null,
              totalLive = otherLiveCount,
              totalDead = otherDeadCount,
              totalExisting = otherExistingCount,
          )
          stratum(1) {
            survivalRate(percent(otherLiveCount + unknownLiveCount, density))
            // Unknown species are not included at the stratum level, just known and Other ones.
            species(
                OTHER,
                survivalRate = null,
                totalLive = otherLiveCount,
                totalDead = otherDeadCount,
                totalExisting = otherExistingCount,
            )
            substratum(1) {
              survivalRate(percent(otherLiveCount + unknownLiveCount, density))
              species(
                  OTHER,
                  survivalRate = null,
                  totalLive = otherLiveCount,
                  totalDead = otherDeadCount,
                  totalExisting = otherExistingCount,
              )
              species(
                  UNKNOWN,
                  survivalRate = null,
                  totalLive = unknownLiveCount,
                  totalDead = unknownDeadCount,
                  totalExisting = unknownExistingCount,
              )
              plot(1) {
                survivalRate(percent(otherLiveCount + unknownLiveCount, density))
                species(
                    OTHER,
                    survivalRate = null,
                    totalLive = otherLiveCount,
                    totalDead = otherDeadCount,
                    totalExisting = otherExistingCount,
                )
                species(
                    UNKNOWN,
                    survivalRate = null,
                    totalLive = unknownLiveCount,
                    totalDead = unknownDeadCount,
                    totalExisting = unknownExistingCount,
                )
              }
            }
          }
        }
      }

      assertResultsMatchPlantCounts()

      otherLiveCount = 21
      otherDeadCount = 22
      otherExistingCount = 23

      observationEdited(1) {
        plot(1) {
          species(
              OTHER,
              live = otherLiveCount,
              dead = otherDeadCount,
              existing = otherExistingCount,
          )
        }
      }

      assertResultsMatchPlantCounts()

      unknownLiveCount = 24
      unknownDeadCount = 25
      unknownExistingCount = 26

      observationEdited(1) {
        plot(1) {
          species(
              UNKNOWN,
              live = unknownLiveCount,
              dead = unknownDeadCount,
              existing = unknownExistingCount,
          )
        }
      }

      assertResultsMatchPlantCounts()
    }
  }

  @Test
  fun `updates aggregate survival rates when most recent observation is edited`() {
    scenario {
      siteCreated {
        stratum(1) {
          substratum(1) {
            plot(111)
            plot(112)
          }
          substratum(2) {
            plot(211) //
          }
        }
        stratum(2) {
          substratum(3) {
            plot(311) //
          }
        }
      }

      // Map<plot number, Map<species number, density>>
      val densities =
          nullSafeMapOf(
              111 to nullSafeMapOf(0 to 10, 1 to 20),
              112 to nullSafeMapOf(0 to 30, 1 to 40),
              211 to nullSafeMapOf(0 to 50, 1 to 60),
              311 to nullSafeMapOf(0 to 70, 1 to 80),
          )

      t0DensitySet {
        densities.forEach { (plotNumber, speciesDensities) ->
          plot(plotNumber) {
            speciesDensities.forEach { (speciesNumber, density) ->
              species(speciesNumber, density = density)
            }
          }
        }
      }

      // Map<plot number, Map<species number, live count>>
      val obsLive =
          nullSafeMapOf(
              111 to nullSafeMutableMapOf(0 to 9, 1 to 2),
              112 to nullSafeMutableMapOf(0 to 3, 1 to 5),
              211 to nullSafeMutableMapOf(0 to 5, 1 to 6),
              311 to nullSafeMutableMapOf(0 to 6, 1 to 7),
          )

      observation(1) {
        obsLive.forEach { (plotNumber, plotLiveCounts) ->
          plot(plotNumber) {
            plotLiveCounts.forEach { (speciesNumber, live) -> species(speciesNumber, live = live) }
          }
        }
      }

      fun assertResultsMatchNumbersFromObsLive() {
        expectResults(observation = 1) {
          survivalRate(
              percent(
                  obsLive[111][0] +
                      obsLive[111][1] +
                      obsLive[112][0] +
                      obsLive[112][1] +
                      obsLive[211][0] +
                      obsLive[211][1] +
                      obsLive[311][0] +
                      obsLive[311][1],
                  densities[111][0] +
                      densities[111][1] +
                      densities[112][0] +
                      densities[112][1] +
                      densities[211][0] +
                      densities[211][1] +
                      densities[311][0] +
                      densities[311][1],
              )
          )
          species(
              0,
              survivalRate =
                  percent(
                      obsLive[111][0] + obsLive[112][0] + obsLive[211][0] + obsLive[311][0],
                      densities[111][0] + densities[112][0] + densities[211][0] + densities[311][0],
                  ),
              totalLive = obsLive[111][0] + obsLive[112][0] + obsLive[211][0] + obsLive[311][0],
          )
          species(
              1,
              survivalRate =
                  percent(
                      obsLive[111][1] + obsLive[112][1] + obsLive[211][1] + obsLive[311][1],
                      densities[111][1] + densities[112][1] + densities[211][1] + densities[311][1],
                  ),
              totalLive = obsLive[111][1] + obsLive[112][1] + obsLive[211][1] + obsLive[311][1],
          )
          stratum(1) {
            survivalRate(
                percent(
                    obsLive[111][0] +
                        obsLive[111][1] +
                        obsLive[112][0] +
                        obsLive[112][1] +
                        obsLive[211][0] +
                        obsLive[211][1],
                    densities[111][0] +
                        densities[111][1] +
                        densities[112][0] +
                        densities[112][1] +
                        densities[211][0] +
                        densities[211][1],
                )
            )
            species(
                0,
                survivalRate =
                    percent(
                        obsLive[111][0] + obsLive[112][0] + obsLive[211][0],
                        densities[111][0] + densities[112][0] + densities[211][0],
                    ),
                totalLive = obsLive[111][0] + obsLive[112][0] + obsLive[211][0],
            )
            species(
                1,
                survivalRate =
                    percent(
                        obsLive[111][1] + obsLive[112][1] + obsLive[211][1],
                        densities[111][1] + densities[112][1] + densities[211][1],
                    ),
                totalLive = obsLive[111][1] + obsLive[112][1] + obsLive[211][1],
            )
            substratum(1) {
              survivalRate(
                  percent(
                      obsLive[111][0] + obsLive[111][1] + obsLive[112][0] + obsLive[112][1],
                      densities[111][0] + densities[111][1] + densities[112][0] + densities[112][1],
                  )
              )
              species(
                  0,
                  survivalRate =
                      percent(
                          obsLive[111][0] + obsLive[112][0],
                          densities[111][0] + densities[112][0],
                      ),
                  totalLive = obsLive[111][0] + obsLive[112][0],
              )
              species(
                  1,
                  survivalRate =
                      percent(
                          obsLive[111][1] + obsLive[112][1],
                          densities[111][1] + densities[112][1],
                      ),
                  totalLive = obsLive[111][1] + obsLive[112][1],
              )
              plot(111) {
                survivalRate(
                    percent(
                        obsLive[111][0] + obsLive[111][1],
                        densities[111][0] + densities[111][1],
                    )
                )
                species(
                    0,
                    survivalRate = percent(obsLive[111][0], densities[111][0]),
                    totalLive = obsLive[111][0],
                )
                species(
                    1,
                    survivalRate = percent(obsLive[111][1], densities[111][1]),
                    totalLive = obsLive[111][1],
                )
              }
              plot(112) {
                survivalRate(
                    percent(
                        obsLive[112][0] + obsLive[112][1],
                        densities[112][0] + densities[112][1],
                    )
                )
                species(
                    0,
                    survivalRate = percent(obsLive[112][0], densities[112][0]),
                    totalLive = obsLive[112][0],
                )
                species(
                    1,
                    survivalRate = percent(obsLive[112][1], densities[112][1]),
                    totalLive = obsLive[112][1],
                )
              }
            }
            substratum(2) {
              survivalRate(
                  percent(
                      obsLive[211][0] + obsLive[211][1],
                      densities[211][0] + densities[211][1],
                  )
              )
              species(
                  0,
                  survivalRate = percent(obsLive[211][0], densities[211][0]),
                  totalLive = obsLive[211][0],
              )
              species(
                  1,
                  survivalRate = percent(obsLive[211][1], densities[211][1]),
                  totalLive = obsLive[211][1],
              )
              plot(211) {
                survivalRate(
                    percent(
                        obsLive[211][0] + obsLive[211][1],
                        densities[211][0] + densities[211][1],
                    )
                )
                species(
                    0,
                    survivalRate = percent(obsLive[211][0], densities[211][0]),
                    totalLive = obsLive[211][0],
                )
                species(
                    1,
                    survivalRate = percent(obsLive[211][1], densities[211][1]),
                    totalLive = obsLive[211][1],
                )
              }
            }
          }
          stratum(2) {
            survivalRate(
                percent(obsLive[311][0] + obsLive[311][1], densities[311][0] + densities[311][1])
            )
            species(0, survivalRate = percent(obsLive[311][0], densities[311][0]))
            species(1, survivalRate = percent(obsLive[311][1], densities[311][1]))
            substratum(3) {
              survivalRate(
                  percent(
                      obsLive[311][0] + obsLive[311][1],
                      densities[311][0] + densities[311][1],
                  )
              )
              species(0, survivalRate = percent(obsLive[311][0], densities[311][0]))
              species(1, survivalRate = percent(obsLive[311][1], densities[311][1]))
              plot(311) {
                survivalRate(
                    percent(
                        obsLive[311][0] + obsLive[311][1],
                        densities[311][0] + densities[311][1],
                    )
                )
                species(0, survivalRate = percent(obsLive[311][0], densities[311][0]))
                species(1, survivalRate = percent(obsLive[311][1], densities[311][1]))
              }
            }
          }
        }
      }

      assertResultsMatchNumbersFromObsLive()

      obsLive[111][0] = 18
      observationEdited(1) {
        plot(111) { //
          species(0, live = 18)
        }
      }

      assertResultsMatchNumbersFromObsLive()

      eventPublisher.assertEventPublished(
          MonitoringSpeciesTotalsEditedEvent(
              certainty = RecordedSpeciesCertainty.Known,
              changedFrom = MonitoringSpeciesTotalsEditedEventValues(totalLive = 9),
              changedTo = MonitoringSpeciesTotalsEditedEventValues(totalLive = 18),
              monitoringPlotId = monitoringPlotIds[111]!!,
              observationId = observationIds[1]!!,
              organizationId = inserted.organizationId,
              plantingSiteId = plantingSiteId,
              speciesId = speciesIds[0]!!,
              speciesName = null,
          )
      )
    }
  }

  @Test
  fun `survival rates in later observations are not affected by edit of non-t0 observation`() {
    scenario {
      siteCreated {
        stratum(1) {
          substratum(1) {
            plot(111) //
          }
        }
      }

      t0DensitySet { plot(111) { species(0, density = 10) } }

      observation(1) { plot(111) { species(0, live = 9) } }

      observation(2) { plot(111) { species(0, live = 8) } }

      observation(3) { plot(111) { species(0, live = 7) } }

      expectResults(observation = 1) {
        survivalRate(90)
        species(0, survivalRate = 90)
        stratum(1) {
          survivalRate(90)
          species(0, survivalRate = 90)
          substratum(1) {
            survivalRate(90)
            species(0, survivalRate = 90)
            plot(111) {
              survivalRate(90)
              species(0, survivalRate = 90)
            }
          }
        }
      }

      val baseline2 =
          expectResults(observation = 2) {
            survivalRate(80)
            species(0, survivalRate = 80)
            stratum(1) {
              survivalRate(80)
              species(0, survivalRate = 80)
              substratum(1) {
                survivalRate(80)
                species(0, survivalRate = 80)
                plot(111) {
                  survivalRate(80)
                  species(0, survivalRate = 80)
                }
              }
            }
          }

      val baseline3 =
          expectResults(observation = 3) {
            survivalRate(70)
            species(0, survivalRate = 70)
            stratum(1) {
              survivalRate(70)
              species(0, survivalRate = 70)
              substratum(1) {
                survivalRate(70)
                species(0, survivalRate = 70)
                plot(111) {
                  survivalRate(70)
                  species(0, survivalRate = 70)
                }
              }
            }
          }

      observationEdited(1) {
        plot(111) { //
          species(0, live = 6)
        }
      }

      expectResults(observation = 1) {
        survivalRate(60)
        species(0, survivalRate = 60)
        stratum(1) {
          survivalRate(60)
          species(0, survivalRate = 60)
          substratum(1) {
            survivalRate(60)
            species(0, survivalRate = 60)
            plot(111) {
              survivalRate(60)
              species(0, survivalRate = 60)
            }
          }
        }
      }

      expectUnchangedResults(observation = 2, baseline = baseline2)

      expectUnchangedResults(observation = 3, baseline = baseline3)
    }
  }

  @Test
  fun `edit of earlier observation affects aggregated survival rates of later observations that do not include the edited observation's substratum`() {
    scenario {
      siteCreated {
        stratum(1) {
          substratum(11) { plot(111) }
          substratum(12) { plot(121) }
        }
        stratum(2) { substratum(21) { plot(211) } }
      }

      // Map<plot number, density>
      val densities = nullSafeMapOf(111 to 4, 121 to 5, 211 to 6)
      t0DensitySet {
        plot(111) { species(0, density = densities[111]) }
        plot(121) { species(0, density = densities[121]) }
        plot(211) { species(0, density = densities[211]) }
      }

      // Map<observation number, Map<plot number, live count>>
      val obsLive =
          nullSafeMapOf(
              1 to nullSafeMutableMapOf(111 to 4),
              2 to nullSafeMutableMapOf(121 to 3),
              3 to nullSafeMutableMapOf(211 to 2),
          )

      observation(1) { plot(111) { species(0, live = obsLive[1][111]) } }
      observation(2) { plot(121) { species(0, live = obsLive[2][121]) } }
      observation(3) { plot(211) { species(0, live = obsLive[3][211]) } }

      fun assertResultsMatchNumbersFromObsLive() {
        expectResults(observation = 1) {
          survivalRate(percent(obsLive[1][111], densities[111]))
          stratum(1) {
            survivalRate(percent(obsLive[1][111], densities[111]))
            substratum(11) {
              survivalRate(percent(obsLive[1][111], densities[111]))
              plot(111) {
                survivalRate(percent(obsLive[1][111], densities[111]))
                species(0, survivalRate = percent(obsLive[1][111], densities[111]))
              }
            }
          }
        }

        expectResults(observation = 2) {
          survivalRate(percent(obsLive[1][111] + obsLive[2][121], densities[111] + densities[121]))
          stratum(1) {
            survivalRate(
                percent(obsLive[1][111] + obsLive[2][121], densities[111] + densities[121])
            )
            substratum(12) {
              survivalRate(percent(obsLive[2][121], densities[121]))
              plot(121) {
                survivalRate(percent(obsLive[2][121], densities[121]))
                species(0, survivalRate = percent(3, densities[121]))
              }
            }
          }
        }

        expectResults(observation = 3) {
          survivalRate(
              percent(
                  obsLive[1][111] + obsLive[2][121] + obsLive[3][211],
                  densities[111] + densities[121] + densities[211],
              )
          )
          stratum(2) {
            survivalRate(percent(obsLive[3][211], densities[211]))
            substratum(21) {
              survivalRate(percent(obsLive[3][211], densities[211]))
              plot(211) {
                survivalRate(percent(obsLive[3][211], densities[211]))
                species(0, survivalRate = percent(obsLive[3][211], densities[211]))
              }
            }
          }
        }
      }

      assertResultsMatchNumbersFromObsLive()

      obsLive[1][111] = 1
      observationEdited(1) {
        plot(111) {
          species(0, live = obsLive[1][111]) //
        }
      }

      assertResultsMatchNumbersFromObsLive()
    }
  }

  @Test
  fun `editing t0 observation affects survival rates in all observations that include the substratum`() {
    scenario {
      siteCreated {
        stratum(1) {
          substratum(1) { plot(1) }
          substratum(2) { plot(2) }
        }
        stratum(2) { substratum(3) { plot(3) } }
      }

      // Map<observation number, Map<plot number, live count>>
      val obsLive =
          nullSafeMapOf(
              1 to nullSafeMutableMapOf(1 to 13, 2 to 14, 3 to 15),
              2 to nullSafeMutableMapOf(1 to 23),
              3 to nullSafeMutableMapOf(3 to 35),
          )

      obsLive.forEach { (observationNumber, plantsPerPlot) ->
        observation(observationNumber) {
          plantsPerPlot.forEach { (plotNumber, liveCount) ->
            plot(plotNumber) { species(0, live = liveCount) }
          }
        }
      }

      val densities = nullSafeMutableMapOf(1 to obsLive[1][1], 2 to 40, 3 to 50)

      t0DensitySet {
        plot(1) { observation(1) }
        plot(2) { species(0, densities[2]) }
        plot(3) { species(0, densities[3]) }
      }

      fun assertResultsMatchNumbersFromObsLive() {
        expectResults(observation = 1) {
          survivalRate(
              percent(
                  obsLive[1][1] + obsLive[1][2] + obsLive[1][3],
                  densities[1] + densities[2] + densities[3],
              )
          )
          stratum(1) {
            survivalRate(percent(obsLive[1][1] + obsLive[1][2], densities[1] + densities[2]))
            substratum(1) {
              survivalRate(percent(obsLive[1][1], densities[1]))
              plot(1) { survivalRate(percent(obsLive[1][1], densities[1])) }
            }
            substratum(2) {
              survivalRate(percent(obsLive[1][2], densities[2]))
              plot(2) { survivalRate(percent(obsLive[1][2], densities[2])) }
            }
          }
          stratum(2) {
            survivalRate(percent(obsLive[1][3], densities[3]))
            substratum(3) {
              survivalRate(percent(obsLive[1][3], densities[3]))
              plot(3) { survivalRate(percent(obsLive[1][3], densities[3])) }
            }
          }
        }

        expectResults(observation = 2) {
          survivalRate(
              percent(
                  obsLive[2][1] + obsLive[1][2] + obsLive[1][3],
                  densities[1] + densities[2] + densities[3],
              )
          )
          stratum(1) {
            survivalRate(percent(obsLive[2][1] + obsLive[1][2], densities[1] + densities[2]))
            substratum(1) {
              survivalRate(percent(obsLive[2][1], densities[1]))
              plot(1) { survivalRate(percent(obsLive[2][1], densities[1])) }
            }
            noResultForSubstratum(2)
          }
          noResultForStratum(2)
        }

        expectResults(observation = 3) {
          survivalRate(
              percent(
                  obsLive[2][1] + obsLive[1][2] + obsLive[3][3],
                  densities[1] + densities[2] + densities[3],
              )
          )
          noResultForStratum(1)
          stratum(2) {
            survivalRate(percent(obsLive[3][3], densities[3]))
            noResultForSubstratum(2)
            substratum(3) { survivalRate(percent(obsLive[3][3], densities[3])) }
          }
        }
      }

      assertResultsMatchNumbersFromObsLive()

      // The edit will include dead and existing plants to verify that the dead ones are counted as
      // part of the t0 density.
      val obs1Dead = 20
      obsLive[1][1] = 43
      densities[1] = obsLive[1][1] + obs1Dead
      observationEdited(1) {
        plot(1) { species(0, live = obsLive[1][1], dead = obs1Dead, existing = 50) }
      }

      // This will use the updated obsLive[1][1] value, i.e., it will assert that the results are
      // what we would have gotten if the original observation had used the edited number.
      assertResultsMatchNumbersFromObsLive()
    }
  }

  @Test
  fun `stratum-level survival rates account for map edits that cause substrata to move from one stratum to another`() {
    scenario {
      siteCreated {
        stratum(1) {
          substratum(1) { plot(1) }
          substratum(2) { plot(2) }
        }
        stratum(2) { substratum(3) { plot(3) } }
      }

      val densities = nullSafeMapOf(1 to 30, 2 to 40, 3 to 50)
      t0DensitySet {
        plot(1) { species(0, density = densities[1]) }
        plot(2) { species(0, density = densities[2]) }
        plot(3) { species(0, density = densities[3]) }
      }

      // Map<observation number, Map<plot number, live plant count>>
      val obsLive =
          nullSafeMapOf(
              1 to nullSafeMutableMapOf(1 to 13, 2 to 14, 3 to 15),
              2 to nullSafeMutableMapOf(1 to 23),
              3 to nullSafeMutableMapOf(3 to 35),
          )

      observation(1) {
        plot(1) { species(0, live = obsLive[1][1]) }
        plot(2) { species(0, live = obsLive[1][2]) }
        plot(3) { species(0, live = obsLive[1][3]) }
      }

      observation(2) { plot(1) { species(0, live = obsLive[2][1]) } }

      siteEdited {
        stratum(1) { //
          substratum(1) { plot(1) }
        }
        stratum(2) {
          substratum(2) { plot(2) } // Moved from stratum 1
          substratum(3) { plot(3) }
        }
      }

      observation(3) { plot(3) { species(0, live = obsLive[3][3]) } }

      fun assertResultsMatchNumbersFromObsLive() {
        expectResults(observation = 1) {
          survivalRate(
              percent(
                  obsLive[1][1] + obsLive[1][2] + obsLive[1][3],
                  densities[1] + densities[2] + densities[3],
              )
          )
          stratum(1) {
            survivalRate(percent(obsLive[1][1] + obsLive[1][2], densities[1] + densities[2]))
            substratum(1) {
              survivalRate(percent(obsLive[1][1], densities[1]))
              plot(1) { survivalRate(percent(obsLive[1][1], densities[1])) }
            }
            substratum(2) {
              survivalRate(percent(obsLive[1][2], densities[2]))
              plot(2) { survivalRate(percent(obsLive[1][2], densities[2])) }
            }
          }
          stratum(2) {
            survivalRate(percent(obsLive[1][3], densities[3]))
            substratum(3) {
              survivalRate(percent(obsLive[1][3], densities[3]))
              plot(3) { survivalRate(percent(obsLive[1][3], densities[3])) }
            }
          }
        }

        expectResults(observation = 2) {
          survivalRate(
              percent(
                  obsLive[2][1] + obsLive[1][2] + obsLive[1][3],
                  densities[1] + densities[2] + densities[3],
              )
          )
          stratum(1) {
            survivalRate(percent(obsLive[2][1] + obsLive[1][2], densities[1] + densities[2]))
            substratum(1) {
              survivalRate(percent(obsLive[2][1], densities[1]))
              plot(1) { survivalRate(percent(obsLive[2][1], densities[1])) }
            }
            noResultForSubstratum(2)
          }
          noResultForStratum(2)
        }

        expectResults(observation = 3) {
          survivalRate(
              percent(
                  obsLive[2][1] + obsLive[1][2] + obsLive[3][3],
                  densities[1] + densities[2] + densities[3],
              )
          )
          noResultForStratum(1)
          stratum(2) {
            // Should pull substratum 2 rate from observation 1, but credit it to stratum 2
            // thanks to the map edit
            survivalRate(percent(obsLive[1][2] + obsLive[3][3], densities[2] + densities[3]))
            noResultForSubstratum(2)
            substratum(3) { survivalRate(percent(obsLive[3][3], densities[3])) }
          }
        }
      }

      assertResultsMatchNumbersFromObsLive()

      obsLive[1][2] = 44
      observationEdited(1) { plot(2) { species(0, live = obsLive[1][2]) } }

      // This will use the updated obsLive[1][2] value, i.e., it will assert that the results are
      // what we would have gotten if the original observation had used the edited number.
      assertResultsMatchNumbersFromObsLive()
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = [true, false])
  fun `edits to temporary plot observation data only affect aggregated survival rates if enabled for site`(
      survivalRateIncludesTempPlots: Boolean
  ) {
    scenario {
      siteCreated(survivalRateIncludesTempPlots = survivalRateIncludesTempPlots) {
        stratum(1) {
          substratum(1) {
            plot(1)
            plot(2, permanentIndex = null)
          }
          substratum(2) { plot(3) }
        }
      }

      val obsLive =
          nullSafeMapOf(
              1 to nullSafeMutableMapOf(1 to 11, 2 to 12, 3 to 13),
              2 to nullSafeMutableMapOf(3 to 23),
          )
      val densities = nullSafeMutableMapOf(1 to 10, 2 to 20, 3 to 30)

      t0DensitySet {
        plot(1) { species(0, densities[1]) }
        stratum(1) { species(0, densities[2]) }
        plot(3) { species(0, densities[3]) }
      }

      observation(1) {
        plot(1) { species(0, live = obsLive[1][1]) }
        plot(2, isPermanent = false) { species(0, live = obsLive[1][2]) }
        plot(3) { species(0, live = obsLive[1][3]) }
      }

      observation(2) { plot(3) { species(0, live = obsLive[2][3]) } }

      // Now we'll use obsLive to calculate expected survival rates, and we want to disregard the
      // live count and density for the temporary plot if survivalRateIncludesTempPlots is false.
      if (!survivalRateIncludesTempPlots) {
        obsLive[1][2] = 0
        densities[2] = 0
      }

      fun assertResultsMatchNumbersFromObsLive() {
        expectResults(observation = 1) {
          survivalRate(
              percent(
                  obsLive[1][1] + obsLive[1][2] + obsLive[1][3],
                  densities[1] + densities[2] + densities[3],
              )
          )
          stratum(1) {
            survivalRate(
                percent(
                    obsLive[1][1] + obsLive[1][2] + obsLive[1][3],
                    densities[1] + densities[2] + densities[3],
                )
            )
            substratum(1) {
              survivalRate(percent(obsLive[1][1] + obsLive[1][2], densities[1] + densities[2]))
              plot(1) { survivalRate(percent(obsLive[1][1], densities[1])) }
              plot(2) {
                if (survivalRateIncludesTempPlots) {
                  survivalRate(percent(obsLive[1][2], densities[2]))
                } else {
                  // Survival rate shouldn't be set at all if we aren't counting temp plots
                  survivalRate(null)
                }
              }
            }
            substratum(2) {
              survivalRate(percent(obsLive[1][3], densities[3]))
              plot(3) { survivalRate(percent(obsLive[1][3], densities[3])) }
            }
          }
        }

        expectResults(observation = 2) {
          survivalRate(
              percent(
                  obsLive[1][1] + obsLive[1][2] + obsLive[2][3],
                  densities[1] + densities[2] + densities[3],
              )
          )
          stratum(1) {
            survivalRate(
                percent(
                    obsLive[1][1] + obsLive[1][2] + obsLive[2][3],
                    densities[1] + densities[2] + densities[3],
                )
            )
            noResultForSubstratum(1)
            substratum(2) {
              survivalRate(percent(obsLive[2][3], densities[3]))
              plot(3) { survivalRate(percent(obsLive[2][3], densities[3])) }
            }
          }
        }
      }

      assertResultsMatchNumbersFromObsLive()

      if (survivalRateIncludesTempPlots) {
        obsLive[1][2] = 22
      }
      observationEdited(1) { plot(2) { species(0, live = 22) } }

      assertResultsMatchNumbersFromObsLive()
    }
  }

  @Test
  fun `throws exception if no permission to edit quantities`() {
    scenario {
      siteCreated { stratum(1) { substratum(1) { plot(1) } } }
      observation(1) { plot(1) { species(0, live = 1) } }

      deleteOrganizationUser()
      insertOrganizationUser(role = Role.Contributor)

      assertThrows<AccessDeniedException> {
        observationEdited(1) { plot(1) { species(0, live = 2) } }
      }
    }
  }

  fun scenario(init: ObservationScenario.() -> Unit) {
    ObservationScenario.forTest(this, eventPublisher = eventPublisher).init()
  }
}
