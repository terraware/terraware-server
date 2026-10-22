package com.terraformation.backend.tracking

import com.terraformation.backend.tracking.db.PlantingSiteMapInvalidException
import com.terraformation.backend.tracking.db.plantingSiteStore.BasePlantingSiteStoreTest
import com.terraformation.backend.tracking.model.ExistingPlantingSiteModel
import com.terraformation.backend.tracking.model.NewPlantingSiteModel
import com.terraformation.backend.tracking.model.PlantingSiteBuilder.Companion.newSite
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import java.math.BigDecimal
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class PlantingSiteMapEditServiceTest : BasePlantingSiteStoreTest() {
  private val service: PlantingSiteMapEditService by lazy { PlantingSiteMapEditService(store) }

  @Nested
  inner class CalculateEdit {
    @Test
    fun `can skip validation of desired site`() {
      val existing = createSite(newSite())
      val desired = newSite {
        stratum {
          substratum(width = 300)
          substratum(x = 200)
        }
      }

      assertThrows<PlantingSiteMapInvalidException> { service.calculateEdit(existing, desired) }
      assertDoesNotThrow { service.calculateEdit(existing, desired, validate = false) }
    }
  }

  @Nested
  inner class ApplyEdit {
    @Test
    fun `applies calculated edit`() {
      val existing = createSite(newSite(width = 500))
      val edit = service.calculateEdit(existing, newSite(width = 400))

      val edited = service.applyEdit(edit)

      assertEquals(BigDecimal("20.000"), edited.areaHa, "Area")
    }
  }

  private fun createSite(initial: NewPlantingSiteModel): ExistingPlantingSiteModel {
    clock.instant = Instant.EPOCH
    val created = store.createPlantingSite(initial.copy(organizationId = organizationId))

    // createPlantingSite doesn't create monitoring plots since they are expected to be created on
    // demand later on, so we need to create them ourselves.
    initial.strata.forEach { initialStratum ->
      val stratum = created.strata.single { it.name == initialStratum.name }
      initialStratum.substrata.forEach { initialSubstratum ->
        val substratum = stratum.substrata.single { it.name == initialSubstratum.name }
        initialSubstratum.monitoringPlots.forEach { plot ->
          insertMonitoringPlot(
              boundary = plot.boundary,
              permanentIndex = plot.permanentIndex,
              plantingSiteId = created.id,
              plotNumber = plot.plotNumber,
              substratumId = substratum.id,
          )
        }
      }
    }

    return store.fetchSiteById(created.id, PlantingSiteDepth.Plot)
  }
}
