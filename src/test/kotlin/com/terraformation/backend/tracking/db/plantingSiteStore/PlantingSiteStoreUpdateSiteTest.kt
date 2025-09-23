package com.terraformation.backend.tracking.db.plantingSiteStore

import com.terraformation.backend.customer.event.PlantingSiteTimeZoneChangedEvent
import com.terraformation.backend.db.ProjectInDifferentOrganizationException
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSiteHistoriesRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSitesRow
import com.terraformation.backend.multiPolygon
import com.terraformation.backend.point
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import com.terraformation.backend.tracking.model.PlantingSiteModel
import com.terraformation.backend.util.Turtle
import io.mockk.every
import java.math.BigDecimal
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class PlantingSiteStoreUpdateSiteTest : BasePlantingSiteStoreTest() {
  @Nested
  inner class UpdatePlantingSite {
    @Test
    fun `updates values`() {
      val initialModel =
          store.createPlantingSite(
              PlantingSiteModel.create(
                  boundary = Turtle(point(0)).makeMultiPolygon { square(100) },
                  name = "initial name",
                  organizationId = organizationId,
                  timeZone = timeZone,
              )
          )

      val createdTime = clock.instant()
      val newTimeZone = ZoneId.of("Europe/Paris")
      val now = createdTime.plusSeconds(1000)
      clock.instant = now

      val newBoundary = Turtle(point(1)).makeMultiPolygon { square(200) }

      store.updatePlantingSite(initialModel.id, emptyList()) { model ->
        model.copy(
            boundary = newBoundary,
            description = "new description",
            name = "new name",
            timeZone = newTimeZone,
        )
      }

      assertEquals(
          listOf(
              PlantingSitesRow(
                  areaHa = BigDecimal("4.0"),
                  boundary = newBoundary,
                  gridOrigin = initialModel.gridOrigin,
                  id = initialModel.id,
                  organizationId = organizationId,
                  name = "new name",
                  description = "new description",
                  createdBy = user.userId,
                  createdTime = createdTime,
                  modifiedBy = user.userId,
                  modifiedTime = now,
                  survivalRateIncludesTempPlots = false,
                  timeZone = newTimeZone,
              )
          ),
          plantingSitesDao.findAll(),
          "Planting sites",
      )

      assertEquals(
          setOf(
              PlantingSiteHistoriesRow(
                  areaHa = BigDecimal("1.0"),
                  boundary = initialModel.boundary,
                  createdBy = user.userId,
                  createdTime = createdTime,
                  gridOrigin = initialModel.gridOrigin,
                  plantingSiteId = initialModel.id,
              ),
              PlantingSiteHistoriesRow(
                  areaHa = BigDecimal("4.0"),
                  boundary = newBoundary,
                  createdBy = user.userId,
                  createdTime = clock.instant,
                  gridOrigin = initialModel.gridOrigin,
                  plantingSiteId = initialModel.id,
              ),
          ),
          plantingSiteHistoriesDao.findAll().map { it.copy(id = null) }.toSet(),
          "Planting site histories",
      )
    }

    @Test
    fun `ignores boundary updates on detailed planting sites`() {
      val plantingSiteId = insertPlantingSite(boundary = multiPolygon(1))
      insertPlantingZone()

      store.updatePlantingSite(plantingSiteId, emptyList()) { model ->
        model.copy(boundary = multiPolygon(2))
      }

      assertEquals(multiPolygon(1), plantingSitesDao.findAll().first().boundary)
    }

    @Test
    fun `publishes event if time zone updated`() {
      val plantingSiteId = insertPlantingSite(timeZone = timeZone)
      val initialModel = store.fetchSiteById(plantingSiteId, PlantingSiteDepth.Site)
      val newTimeZone = ZoneId.of("Europe/Paris")

      store.updatePlantingSite(plantingSiteId, emptyList()) { it.copy(timeZone = newTimeZone) }

      val expectedEvent =
          PlantingSiteTimeZoneChangedEvent(
              initialModel.copy(timeZone = newTimeZone),
              timeZone,
              newTimeZone,
          )

      eventPublisher.assertEventPublished(expectedEvent)
    }

    @Test
    fun `does not publish event if time zone not updated`() {
      val plantingSiteId = insertPlantingSite(timeZone = timeZone)

      store.updatePlantingSite(plantingSiteId, emptyList()) { it.copy(description = "edited") }

      eventPublisher.assertEventNotPublished(PlantingSiteTimeZoneChangedEvent::class.java)
    }

    @Test
    fun `throws exception if no permission`() {
      val plantingSiteId = insertPlantingSite()

      every { user.canUpdatePlantingSite(any()) } returns false

      assertThrows<AccessDeniedException> {
        store.updatePlantingSite(plantingSiteId, emptyList()) { it }
      }
    }

    @Test
    fun `throws exception if project is in a different organization`() {
      val plantingSiteId = insertPlantingSite()
      insertOrganization()
      val otherOrgProjectId = insertProject()

      assertThrows<ProjectInDifferentOrganizationException> {
        store.updatePlantingSite(plantingSiteId, emptyList()) {
          it.copy(projectId = otherOrgProjectId)
        }
      }
    }
  }
}
