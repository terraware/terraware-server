package com.terraformation.backend.tracking.db.observationStore

import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.tables.pojos.ObservationsRow
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ObservationStoreFetchObservationsPastEndDateTest : BaseObservationStoreTest() {
  @Test
  fun `honors planting site time zones`() {
    // Three adjacent time zones, 1 hour apart
    val zone1 = ZoneId.of("America/Los_Angeles")
    val zone2 = ZoneId.of("America/Denver")
    val zone3 = ZoneId.of("America/Chicago")

    val startDate = LocalDate.of(2023, 3, 1)
    val endDate = LocalDate.of(2023, 3, 31)

    // Current time in zone 1: 2023-03-31 23:00:00
    // Current time in zone 2: 2023-04-01 00:00:00
    // Current time in zone 3: 2023-04-01 01:00:00
    val now = ZonedDateTime.of(endDate.plusDays(1), LocalTime.MIDNIGHT, zone2).toInstant()
    clock.instant = now

    organizationsDao.update(organizationsDao.fetchOneById(organizationId)!!.copy(timeZone = zone2))

    // End date ending now at a site that inherits its time zone from its organization.
    insertPlantingSite()
    val observationId1 =
        insertObservation(
            endDate = endDate,
            startDate = startDate,
            state = ObservationState.InProgress,
        )

    // End date ended an hour ago.
    insertPlantingSite(timeZone = zone3)
    val observationId2 =
        insertObservation(
            endDate = endDate,
            startDate = startDate,
            state = ObservationState.InProgress,
        )

    // End date isn't over yet in the site's time zone.
    insertPlantingSite(timeZone = zone1)
    insertObservation(endDate = endDate, startDate = startDate, state = ObservationState.InProgress)

    // Observation already completed; shouldn't be marked as overdue
    insertPlantingSite(timeZone = zone3)
    insertObservation(
        ObservationsRow(completedTime = Instant.EPOCH),
        endDate = endDate,
        startDate = startDate,
        state = ObservationState.Completed,
    )

    // End date is still in the future.
    insertPlantingSite(timeZone = zone3)
    insertObservation(
        endDate = endDate.plusDays(1),
        startDate = startDate,
        state = ObservationState.InProgress,
    )

    val expected = setOf(observationId1, observationId2)
    val actual = store.fetchObservationsPastEndDate().map { it.id }.toSet()

    assertEquals(expected, actual)
  }

  @Test
  fun `limits results to requested planting site`() {
    val timeZone = ZoneId.of("America/Denver")

    val startDate = LocalDate.of(2023, 4, 1)
    val endDate = LocalDate.of(2023, 4, 30)

    val now = ZonedDateTime.of(endDate.plusDays(1), LocalTime.MIDNIGHT, timeZone).toInstant()
    clock.instant = now

    val plantingSiteId = insertPlantingSite(timeZone = timeZone)
    val observationId =
        insertObservation(
            endDate = endDate,
            startDate = startDate,
            state = ObservationState.InProgress,
        )

    insertPlantingSite(timeZone = timeZone)
    insertObservation(endDate = endDate, startDate = startDate, state = ObservationState.InProgress)

    val expected = setOf(observationId)
    val actual = store.fetchObservationsPastEndDate(plantingSiteId).map { it.id }.toSet()

    assertEquals(expected, actual)
  }
}
