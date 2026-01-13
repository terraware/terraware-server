package com.terraformation.backend.seedbank.db.accessionStore

import com.terraformation.backend.seedbank.model.Geolocation
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class AccessionStoreLocationTest : AccessionStoreTest() {
  @Test
  fun `geolocations are inserted and deleted as needed`() {
    val initial =
        store.create(
            accessionModel(
                geolocations =
                    setOf(
                        Geolocation(BigDecimal(1), BigDecimal(2), BigDecimal(100)),
                        Geolocation(BigDecimal(3), BigDecimal(4)),
                    )
            )
        )
    val initialGeos = geolocationsDao.fetchByAccessionId(initial.id!!)

    assertEquals(100.0, initialGeos.firstNotNullOf { it.gpsAccuracy }, 0.1, "Accuracy is recorded")

    val desired =
        initial.copy(
            geolocations =
                setOf(
                    Geolocation(BigDecimal(1), BigDecimal(2), BigDecimal(100)),
                    Geolocation(BigDecimal(5), BigDecimal(6)),
                )
        )

    store.update(desired)

    val updatedGeos = geolocationsDao.fetchByAccessionId(initial.id)

    assertTrue(
        updatedGeos.any { it.latitude?.toInt() == 5 && it.longitude?.toInt() == 6 },
        "New geo inserted",
    )
    assertTrue(updatedGeos.none { it.latitude == BigDecimal(3) }, "Missing geo deleted")
    assertEquals(
        initialGeos.filter { it.latitude == BigDecimal(1) },
        updatedGeos.filter { it.latitude == BigDecimal(1) },
        "Existing geo retained",
    )
  }

  @Test
  fun `valid sub-locations are accepted`() {
    val locationName = "Test Location"
    val locationId = insertSubLocation(name = locationName)

    val initial = store.create(accessionModel())
    store.update(initial.copy(subLocation = locationName))

    assertEquals(
        locationId,
        accessionsDao.fetchOneById(initial.id!!)?.subLocationId,
        "Existing sub-location ID was used",
    )

    val updated = store.fetchOneById(initial.id)
    assertEquals(locationName, updated.subLocation, "Location name")
  }

  @Test
  fun `unknown sub-locations are rejected`() {
    assertThrows<IllegalArgumentException> {
      val initial = store.create(accessionModel())
      store.update(initial.copy(subLocation = "bogus"))
    }
  }
}
