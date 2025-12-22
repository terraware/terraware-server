package com.terraformation.backend.tracking.db.observationStore

import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.tracking.ObservationState
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ObservationStoreFetchStartableObservationsTest : BaseObservationStoreTest() {
  @BeforeEach
  fun insertFacilityAndSpecies() {
    insertFacility(type = FacilityType.Nursery)
    insertSpecies()
  }

  @Test
  fun `only returns observations with requested substrata`() {
    val timeZone = ZoneId.of("America/Denver")
    val startDate = LocalDate.of(2023, 4, 1)
    val endDate = LocalDate.of(2023, 4, 30)

    val now = ZonedDateTime.of(startDate, LocalTime.MIDNIGHT, timeZone).toInstant()
    clock.instant = now

    helper.insertPlantedSite(timeZone = timeZone)
    val startableObservationId =
        insertObservation(
            endDate = endDate,
            startDate = startDate,
            state = ObservationState.Upcoming,
        )
    insertObservationRequestedSubzone()

    // Another planting site with no requested substratum.
    insertPlantingSite(timeZone = timeZone)
    insertPlantingZone()
    insertPlantingSubzone()
    insertObservation(endDate = endDate, startDate = startDate, state = ObservationState.Upcoming)

    val expected = setOf(startableObservationId)
    val actual = store.fetchStartableObservations().map { it.id }.toSet()

    assertEquals(expected, actual)
  }

  @Test
  fun `honors planting site time strata`() {
    // Three adjacent time strata, 1 hour apart
    val zone1 = ZoneId.of("America/Los_Angeles")
    val zone2 = ZoneId.of("America/Denver")
    val zone3 = ZoneId.of("America/Chicago")

    val startDate = LocalDate.of(2023, 4, 1)
    val endDate = LocalDate.of(2023, 4, 30)

    // Current time in zone 1: 2023-03-31 23:00:00
    // Current time in zone 2: 2023-04-01 00:00:00
    // Current time in zone 3: 2023-04-01 01:00:00
    val now = ZonedDateTime.of(startDate, LocalTime.MIDNIGHT, zone2).toInstant()
    clock.instant = now

    organizationsDao.update(organizationsDao.fetchOneById(organizationId)!!.copy(timeZone = zone2))

    // Start date is now at a site that inherits its time zone from its organization.
    helper.insertPlantedSite(timeZone = null)
    val observationId1 =
        insertObservation(
            endDate = endDate,
            startDate = startDate,
            state = ObservationState.Upcoming,
        )
    insertObservationRequestedSubzone()

    // Start date is an hour ago.
    helper.insertPlantedSite(timeZone = zone3)
    val observationId2 =
        insertObservation(
            endDate = endDate,
            startDate = startDate,
            state = ObservationState.Upcoming,
        )
    insertObservationRequestedSubzone()

    // Start date hasn't arrived yet in the site's time zone.
    helper.insertPlantedSite(timeZone = zone1)
    insertObservation(endDate = endDate, startDate = startDate, state = ObservationState.Upcoming)
    insertObservationRequestedSubzone()

    // Observation already in progress; shouldn't be started
    helper.insertPlantedSite(timeZone = zone3)
    insertObservation(endDate = endDate, startDate = startDate, state = ObservationState.InProgress)
    insertObservationRequestedSubzone()

    // Start date is still in the future.
    helper.insertPlantedSite(timeZone = zone3)
    insertObservation(
        endDate = endDate,
        startDate = startDate.plusDays(1),
        state = ObservationState.Upcoming,
    )
    insertObservationRequestedSubzone()

    val expected = setOf(observationId1, observationId2)
    val actual = store.fetchStartableObservations().map { it.id }.toSet()

    assertEquals(expected, actual)
  }

  @Test
  fun `limits results to requested planting site`() {
    val timeZone = ZoneId.of("America/Denver")

    val startDate = LocalDate.of(2023, 4, 1)
    val endDate = LocalDate.of(2023, 4, 30)

    val now = ZonedDateTime.of(startDate, LocalTime.MIDNIGHT, timeZone).toInstant()
    clock.instant = now

    val plantingSiteId = helper.insertPlantedSite(timeZone = timeZone)
    val observationId =
        insertObservation(
            endDate = endDate,
            startDate = startDate,
            state = ObservationState.Upcoming,
        )
    insertObservationRequestedSubzone()

    insertPlantingSite(timeZone = timeZone)
    insertPlantingZone()
    insertPlantingSubzone()
    insertObservation(endDate = endDate, startDate = startDate, state = ObservationState.Upcoming)
    insertObservationRequestedSubzone()

    val expected = setOf(observationId)
    val actual = store.fetchStartableObservations(plantingSiteId).map { it.id }.toSet()

    assertEquals(expected, actual)
  }
}
