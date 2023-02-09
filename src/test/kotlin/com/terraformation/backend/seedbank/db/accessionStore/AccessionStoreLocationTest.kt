package com.terraformation.backend.seedbank.db.accessionStore

import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.GeolocationId
import com.terraformation.backend.db.seedbank.StorageLocationId
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
                        Geolocation(BigDecimal(3), BigDecimal(4)))))
    val initialGeos = geolocationsDao.fetchByAccessionId(AccessionId(1))

    // Insertion order is not defined by the API.

    assertEquals(
        setOf(GeolocationId(1), GeolocationId(2)),
        initialGeos.map { it.id }.toSet(),
        "Initial location IDs")
    assertEquals(100.0, initialGeos.firstNotNullOf { it.gpsAccuracy }, 0.1, "Accuracy is recorded")

    val desired =
        initial.copy(
            geolocations =
                setOf(
                    Geolocation(BigDecimal(1), BigDecimal(2), BigDecimal(100)),
                    Geolocation(BigDecimal(5), BigDecimal(6))))

    store.update(desired)

    val updatedGeos = geolocationsDao.fetchByAccessionId(AccessionId(1))

    assertTrue(
        updatedGeos.any {
          it.id == GeolocationId(3) && it.latitude?.toInt() == 5 && it.longitude?.toInt() == 6
        },
        "New geo inserted")
    assertTrue(updatedGeos.none { it.latitude == BigDecimal(3) }, "Missing geo deleted")
    assertEquals(
        initialGeos.filter { it.latitude == BigDecimal(1) },
        updatedGeos.filter { it.latitude == BigDecimal(1) },
        "Existing geo retained")
  }

  @Test
  fun `valid storage locations are accepted and cause storage condition to be populated`() {
    val locationId = StorageLocationId(12345678)
    val locationName = "Test Location"
    insertStorageLocation(locationId, name = locationName)

    val initial = store.create(accessionModel())
    store.update(initial.copy(storageLocation = locationName))

    assertEquals(
        locationId,
        accessionsDao.fetchOneById(AccessionId(1))?.storageLocationId,
        "Existing storage location ID was used")

    val updated = store.fetchOneById(initial.id!!)
    assertEquals(locationName, updated.storageLocation, "Location name")
  }

  @Test
  fun `unknown storage locations are rejected`() {
    assertThrows<IllegalArgumentException> {
      val initial = store.create(accessionModel())
      store.update(initial.copy(storageLocation = "bogus"))
    }
  }
}
