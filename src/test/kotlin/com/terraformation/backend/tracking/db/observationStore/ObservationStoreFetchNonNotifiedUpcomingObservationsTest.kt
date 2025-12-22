package com.terraformation.backend.tracking.db.observationStore

import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.tracking.model.ExistingObservationModel
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ObservationStoreFetchNonNotifiedUpcomingObservationsTest : BaseObservationStoreTest() {
  @BeforeEach
  fun insertFacilityAndSpecies() {
    insertFacility(type = FacilityType.Nursery)
    insertSpecies()
  }

  @Test
  fun `does not return observations whose notifications have been sent already`() {
    val timeZone = ZoneId.of("America/Denver")
    val startDate = LocalDate.of(2023, 4, 1)
    val endDate = LocalDate.of(2023, 4, 30)

    val now = ZonedDateTime.of(startDate, LocalTime.MIDNIGHT, timeZone).toInstant()
    clock.instant = now

    helper.insertPlantedSite(timeZone = timeZone)
    insertObservation(
        endDate = endDate,
        startDate = startDate,
        state = ObservationState.Upcoming,
        upcomingNotificationSentTime = now,
    )

    val actual = store.fetchNonNotifiedUpcomingObservations()

    assertEquals(emptyList<ExistingObservationModel>(), actual)
  }

  @Test
  fun `does not returns observations with no requested subzones`() {
    val timeZone = ZoneId.of("America/Denver")
    val startDate = LocalDate.of(2023, 4, 1)
    val endDate = LocalDate.of(2023, 4, 30)

    val now = ZonedDateTime.of(startDate, LocalTime.MIDNIGHT, timeZone).toInstant()
    clock.instant = now

    insertPlantingSite(timeZone = timeZone)
    insertStratum()
    insertSubstratum()
    insertObservation(endDate = endDate, startDate = startDate, state = ObservationState.Upcoming)

    val actual = store.fetchNonNotifiedUpcomingObservations()

    assertEquals(emptyList<ExistingObservationModel>(), actual)
  }

  @Test
  fun `only returns observations with requested subzones`() {
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
    insertObservationRequestedSubstratum()

    // Another planting site with no requested substrata.
    insertPlantingSite(timeZone = timeZone)
    insertStratum()
    insertSubstratum()
    insertObservation(endDate = endDate, startDate = startDate, state = ObservationState.Upcoming)

    val expected = setOf(startableObservationId)
    val actual = store.fetchNonNotifiedUpcomingObservations().map { it.id }.toSet()

    assertEquals(expected, actual)
  }

  @Test
  fun `honors planting site time zones`() {
    // Three adjacent time zones, 1 hour apart
    val zone1 = ZoneId.of("America/Los_Angeles")
    val zone2 = ZoneId.of("America/Denver")
    val zone3 = ZoneId.of("America/Chicago")

    val startDate = LocalDate.of(2023, 4, 1)
    val endDate = LocalDate.of(2023, 4, 30)

    // Current time in zone 1: 2023-02-28 23:00:00
    // Current time in zone 2: 2023-03-01 00:00:00
    // Current time in zone 3: 2023-03-01 01:00:00
    val now = ZonedDateTime.of(startDate.minusMonths(1), LocalTime.MIDNIGHT, zone2).toInstant()
    clock.instant = now

    organizationsDao.update(organizationsDao.fetchOneById(organizationId)!!.copy(timeZone = zone2))

    // Start date is a month from now at a site that inherits its time zone from its organization.
    helper.insertPlantedSite(timeZone = null)
    val observationId1 =
        insertObservation(
            endDate = endDate,
            startDate = startDate,
            state = ObservationState.Upcoming,
        )
    insertObservationRequestedSubstratum()

    // Start date plus 1 month is an hour ago.
    helper.insertPlantedSite(timeZone = zone3)
    val observationId2 =
        insertObservation(
            endDate = endDate,
            startDate = startDate,
            state = ObservationState.Upcoming,
        )
    insertObservationRequestedSubstratum()

    // Start date plus 1 month hasn't arrived yet in the site's time zone.
    helper.insertPlantedSite(timeZone = zone1)
    insertObservation(endDate = endDate, startDate = startDate, state = ObservationState.Upcoming)
    insertObservationRequestedSubstratum()

    val expected = setOf(observationId1, observationId2)
    val actual = store.fetchNonNotifiedUpcomingObservations().map { it.id }.toSet()

    assertEquals(expected, actual)
  }
}
