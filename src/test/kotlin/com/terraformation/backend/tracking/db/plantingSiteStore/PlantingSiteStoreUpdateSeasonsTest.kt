package com.terraformation.backend.tracking.db.plantingSiteStore

import com.terraformation.backend.db.tracking.tables.pojos.PlantingSeasonsRow
import com.terraformation.backend.tracking.event.PlantingSeasonRescheduledEvent
import com.terraformation.backend.tracking.event.PlantingSeasonScheduledEvent
import com.terraformation.backend.tracking.model.CannotCreatePastPlantingSeasonException
import com.terraformation.backend.tracking.model.CannotUpdatePastPlantingSeasonException
import com.terraformation.backend.tracking.model.PlantingSeasonTooFarInFutureException
import com.terraformation.backend.tracking.model.PlantingSeasonTooLongException
import com.terraformation.backend.tracking.model.PlantingSeasonTooShortException
import com.terraformation.backend.tracking.model.PlantingSeasonsOverlapException
import com.terraformation.backend.tracking.model.UpdatedPlantingSeasonModel
import com.terraformation.backend.util.toInstant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class PlantingSiteStoreUpdateSeasonsTest : BasePlantingSiteStoreTest() {
  @Nested
  inner class UpdatePlantingSite {
    @BeforeEach
    fun setUp() {
      clock.instant = ZonedDateTime.of(2023, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant()
    }

    @Test
    fun `updates planting seasons`() {
      val season1Start = LocalDate.of(2023, 1, 1)
      val season1End = LocalDate.of(2023, 2, 15)
      val oldSeason2Start = LocalDate.of(2023, 3, 1)
      val newSeason2Start = LocalDate.of(2023, 3, 2)
      val oldSeason2End = LocalDate.of(2023, 4, 15)
      val newSeason2End = LocalDate.of(2023, 4, 16)
      val season3Start = LocalDate.of(2023, 5, 1)
      val season3End = LocalDate.of(2023, 6, 15)
      val season4Start = LocalDate.of(2023, 5, 5)
      val season4End = LocalDate.of(2023, 6, 10)

      val plantingSiteId = insertPlantingSite(timeZone = timeZone)
      val season1Id =
          insertPlantingSeason(startDate = season1Start, endDate = season1End, timeZone = timeZone)
      val season2Id =
          insertPlantingSeason(
              startDate = oldSeason2Start,
              endDate = oldSeason2End,
              timeZone = timeZone,
          )
      insertPlantingSeason(startDate = season3Start, endDate = season3End, timeZone = timeZone)

      val desiredSeasons =
          listOf(
              // Unchanged
              UpdatedPlantingSeasonModel(
                  startDate = season1Start,
                  endDate = season1End,
                  id = season1Id,
              ),
              // Rescheduled (same ID, different dates)
              UpdatedPlantingSeasonModel(
                  startDate = newSeason2Start,
                  endDate = newSeason2End,
                  id = season2Id,
              ),
              // New
              UpdatedPlantingSeasonModel(startDate = season4Start, endDate = season4End),
          )

      store.updatePlantingSite(plantingSiteId, desiredSeasons) { it }

      val actual = plantingSeasonsDao.findAll().sortedBy { it.startDate }

      val expected =
          listOf(
              PlantingSeasonsRow(
                  endDate = season1End,
                  endTime = season1End.plusDays(1).toInstant(timeZone),
                  id = season1Id,
                  isActive = false,
                  plantingSiteId = plantingSiteId,
                  startDate = season1Start,
                  startTime = season1Start.toInstant(timeZone),
              ),
              PlantingSeasonsRow(
                  endDate = newSeason2End,
                  endTime = newSeason2End.plusDays(1).toInstant(timeZone),
                  id = season2Id,
                  isActive = false,
                  plantingSiteId = plantingSiteId,
                  startDate = newSeason2Start,
                  startTime = newSeason2Start.toInstant(timeZone),
              ),
              PlantingSeasonsRow(
                  endDate = season4End,
                  endTime = season4End.plusDays(1).toInstant(timeZone),
                  id = actual.last().id,
                  isActive = false,
                  plantingSiteId = plantingSiteId,
                  startDate = season4Start,
                  startTime = season4Start.toInstant(timeZone),
              ),
          )

      assertEquals(expected, actual)
    }

    @Test
    fun `marks planting season as inactive if it is rescheduled for the future after starting`() {
      val plantingSiteId = insertPlantingSite()
      val seasonId =
          insertPlantingSeason(
              startDate = LocalDate.of(2022, 12, 1),
              endDate = LocalDate.of(2023, 2, 1),
              isActive = true,
          )

      val newStartDate = LocalDate.of(2023, 2, 1)
      val newEndDate = LocalDate.of(2023, 4, 1)
      val desiredSeasons =
          listOf(
              UpdatedPlantingSeasonModel(
                  startDate = newStartDate,
                  endDate = newEndDate,
                  id = seasonId,
              )
          )

      store.updatePlantingSite(plantingSiteId, desiredSeasons) { it }

      val expected =
          listOf(
              PlantingSeasonsRow(
                  endDate = newEndDate,
                  endTime = newEndDate.plusDays(1).toInstant(ZoneOffset.UTC),
                  id = seasonId,
                  isActive = false,
                  plantingSiteId = plantingSiteId,
                  startDate = newStartDate,
                  startTime = newStartDate.toInstant(ZoneOffset.UTC),
              ),
          )

      assertEquals(expected, plantingSeasonsDao.findAll())
    }

    @Test
    fun `marks planting season as active if it is rescheduled to start in the past`() {
      val plantingSiteId = insertPlantingSite()
      val seasonId =
          insertPlantingSeason(
              startDate = LocalDate.of(2023, 2, 1),
              endDate = LocalDate.of(2023, 4, 1),
          )

      val newStartDate = LocalDate.of(2022, 12, 1)
      val newEndDate = LocalDate.of(2023, 2, 1)
      val desiredSeasons =
          listOf(
              UpdatedPlantingSeasonModel(
                  startDate = newStartDate,
                  endDate = newEndDate,
                  id = seasonId,
              )
          )

      store.updatePlantingSite(plantingSiteId, desiredSeasons) { it }

      val expected =
          listOf(
              PlantingSeasonsRow(
                  endDate = newEndDate,
                  endTime = newEndDate.plusDays(1).toInstant(ZoneOffset.UTC),
                  id = seasonId,
                  isActive = true,
                  plantingSiteId = plantingSiteId,
                  startDate = newStartDate,
                  startTime = newStartDate.toInstant(ZoneOffset.UTC),
              ),
          )

      assertEquals(expected, plantingSeasonsDao.findAll())
    }

    @Test
    fun `rejects invalid planting seasons`() {
      insertPlantingSite(timeZone = timeZone)

      // Planting site time zone is Honolulu which is GMT-10, meaning it is 2022-12-31 there;
      // 2024-01-01 is thus more than a year in the future.
      assertPlantingSeasonUpdateThrows<PlantingSeasonTooFarInFutureException>(
          LocalDate.of(2024, 1, 1),
          LocalDate.of(2024, 2, 1),
      )

      assertPlantingSeasonUpdateThrows<CannotCreatePastPlantingSeasonException>(
          LocalDate.of(2022, 1, 1),
          LocalDate.of(2022, 6, 1),
      )
      assertPlantingSeasonUpdateThrows<PlantingSeasonTooShortException>(
          LocalDate.of(2023, 1, 1),
          LocalDate.of(2023, 1, 27),
      )
      assertPlantingSeasonUpdateThrows<PlantingSeasonTooLongException>(
          LocalDate.of(2023, 1, 1),
          LocalDate.of(2024, 1, 2),
      )
    }

    @Test
    fun `rejects overlapping planting seasons`() {
      val plantingSiteId = insertPlantingSite()

      assertThrows<PlantingSeasonsOverlapException> {
        store.updatePlantingSite(
            plantingSiteId,
            listOf(
                UpdatedPlantingSeasonModel(
                    startDate = LocalDate.of(2023, 1, 1),
                    endDate = LocalDate.of(2023, 2, 1),
                ),
                UpdatedPlantingSeasonModel(
                    startDate = LocalDate.of(2023, 2, 1),
                    endDate = LocalDate.of(2023, 4, 1),
                ),
            ),
        ) {
          it
        }
      }
    }

    @Test
    fun `rejects updates of past planting seasons`() {
      val startDate = LocalDate.of(2022, 11, 1)
      val endDate = LocalDate.of(2022, 12, 15)

      val plantingSiteId = insertPlantingSite()
      val plantingSeasonId = insertPlantingSeason(startDate = startDate, endDate = endDate)

      assertThrows<CannotUpdatePastPlantingSeasonException> {
        store.updatePlantingSite(
            plantingSiteId,
            listOf(
                UpdatedPlantingSeasonModel(
                    startDate = startDate,
                    endDate = LocalDate.of(2023, 1, 15),
                    id = plantingSeasonId,
                )
            ),
        ) {
          it
        }
      }
    }

    @Test
    fun `ignores existing past planting seasons`() {
      val startDate = LocalDate.of(2020, 1, 1)
      val endDate = LocalDate.of(2020, 3, 1)

      val plantingSiteId = insertPlantingSite()
      val plantingSeasonId = insertPlantingSeason(startDate = startDate, endDate = endDate)

      val expected = plantingSeasonsDao.findAll()

      store.updatePlantingSite(
          plantingSiteId,
          listOf(
              UpdatedPlantingSeasonModel(
                  startDate = startDate,
                  endDate = endDate,
                  id = plantingSeasonId,
              )
          ),
      ) {
        it
      }

      val actual = plantingSeasonsDao.findAll()

      assertEquals(expected, actual)
    }

    @Test
    fun `ignores deletion of past planting seasons`() {
      val startDate = LocalDate.of(2020, 1, 1)
      val endDate = LocalDate.of(2020, 3, 1)

      val plantingSiteId = insertPlantingSite()
      insertPlantingSeason(startDate = startDate, endDate = endDate)

      val expected = plantingSeasonsDao.findAll()

      store.updatePlantingSite(plantingSiteId, emptyList()) { it }

      assertEquals(expected, plantingSeasonsDao.findAll())
    }

    @Test
    fun `updates planting season start and end times when time zone changes`() {
      val plantingSiteId = insertPlantingSite(timeZone = ZoneOffset.UTC)

      val pastStartDate = LocalDate.of(2020, 1, 1)
      val pastEndDate = LocalDate.of(2020, 3, 1)
      val pastPlantingSeasonId =
          insertPlantingSeason(startDate = pastStartDate, endDate = pastEndDate)
      val activeStartDate = LocalDate.of(2022, 12, 1)
      val activeEndDate = LocalDate.of(2022, 12, 31)
      val activePlantingSeasonId =
          insertPlantingSeason(startDate = activeStartDate, endDate = activeEndDate)
      val futureStartDate = LocalDate.of(2023, 6, 1)
      val futureEndDate = LocalDate.of(2023, 8, 15)
      val futurePlantingSeasonId =
          insertPlantingSeason(startDate = futureStartDate, endDate = futureEndDate)

      store.updatePlantingSite(
          plantingSiteId,
          listOf(
              UpdatedPlantingSeasonModel(
                  startDate = pastStartDate,
                  endDate = pastEndDate,
                  id = pastPlantingSeasonId,
              ),
              UpdatedPlantingSeasonModel(
                  startDate = activeStartDate,
                  endDate = activeEndDate,
                  id = activePlantingSeasonId,
              ),
              UpdatedPlantingSeasonModel(
                  startDate = futureStartDate,
                  endDate = futureEndDate,
                  id = futurePlantingSeasonId,
              ),
          ),
      ) {
        it.copy(timeZone = timeZone)
      }

      val expected =
          listOf(
              PlantingSeasonsRow(
                  endDate = pastEndDate,
                  endTime = pastEndDate.plusDays(1).toInstant(ZoneOffset.UTC),
                  id = pastPlantingSeasonId,
                  isActive = false,
                  plantingSiteId = plantingSiteId,
                  startDate = pastStartDate,
                  startTime = pastStartDate.toInstant(ZoneOffset.UTC),
              ),
              PlantingSeasonsRow(
                  endDate = activeEndDate,
                  endTime = activeEndDate.plusDays(1).toInstant(timeZone),
                  id = activePlantingSeasonId,
                  // New time zone means it is now December 31, not January 1, so this planting
                  // season ending on December 31 has become active.
                  isActive = true,
                  plantingSiteId = plantingSiteId,
                  startDate = activeStartDate,
                  // Start date didn't change and start time is in the past, so it shouldn't be
                  // updated.
                  startTime = activeStartDate.toInstant(ZoneOffset.UTC),
              ),
              PlantingSeasonsRow(
                  endDate = futureEndDate,
                  endTime = futureEndDate.plusDays(1).toInstant(timeZone),
                  id = futurePlantingSeasonId,
                  isActive = false,
                  plantingSiteId = plantingSiteId,
                  startDate = futureStartDate,
                  startTime = futureStartDate.toInstant(timeZone),
              ),
          )

      assertEquals(expected, plantingSeasonsDao.findAll().sortedBy { it.startDate })
    }

    @Test
    fun `publishes event when planting season is added`() {
      val plantingSiteId = insertPlantingSite()
      val startDate = LocalDate.of(2023, 1, 2)
      val endDate = LocalDate.of(2023, 2, 15)

      store.updatePlantingSite(
          plantingSiteId,
          listOf(
              UpdatedPlantingSeasonModel(startDate = startDate, endDate = endDate),
          ),
      ) {
        it
      }

      val plantingSeasonId = plantingSeasonsDao.findAll().first().id!!

      eventPublisher.assertEventPublished(
          PlantingSeasonScheduledEvent(plantingSiteId, plantingSeasonId, startDate, endDate)
      )
    }

    @Test
    fun `publishes event when planting season is modified`() {
      val plantingSiteId = insertPlantingSite()
      val oldStartDate = LocalDate.of(2023, 1, 1)
      val oldEndDate = LocalDate.of(2023, 1, 31)
      val newStartDate = LocalDate.of(2023, 1, 2)
      val newEndDate = LocalDate.of(2023, 2, 15)

      val plantingSeasonId = insertPlantingSeason(startDate = oldStartDate, endDate = oldEndDate)

      store.updatePlantingSite(
          plantingSiteId,
          listOf(
              UpdatedPlantingSeasonModel(
                  startDate = newStartDate,
                  endDate = newEndDate,
                  id = plantingSeasonId,
              ),
          ),
      ) {
        it
      }

      eventPublisher.assertEventPublished(
          PlantingSeasonRescheduledEvent(
              plantingSiteId,
              plantingSeasonId,
              oldStartDate,
              oldEndDate,
              newStartDate,
              newEndDate,
          )
      )
    }

    private inline fun <reified T : Exception> assertPlantingSeasonUpdateThrows(
        startDate: LocalDate,
        endDate: LocalDate,
    ) {
      assertThrows<T> {
        store.updatePlantingSite(
            inserted.plantingSiteId,
            plantingSeasons =
                listOf(UpdatedPlantingSeasonModel(startDate = startDate, endDate = endDate)),
        ) {
          it
        }
      }
    }
  }
}
