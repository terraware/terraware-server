package com.terraformation.backend.seedbank.db.accessionStore

import com.terraformation.backend.db.AccessionNotFoundException
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.seedbank.AccessionId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class AccessionStoreFetchAccessionNumbersTest : AccessionStoreTest() {
  @Test
  fun `returns numbers of requested accessions`() {
    val accessionId1 = insertAccession(number = "ABC")
    val accessionId2 = insertAccession(number = "DEF")
    insertAccession(number = "GHI")

    assertEquals(
        mapOf(accessionId1 to "ABC", accessionId2 to "DEF"),
        store.fetchAccessionNumbers(listOf(accessionId1, accessionId2)),
    )
  }

  @Test
  fun `returns numbers of accessions in different facilities in the same organization`() {
    val accessionId1 = insertAccession(number = "ABC")
    insertFacility()
    val accessionId2 = insertAccession(number = "DEF")

    assertEquals(
        mapOf(accessionId1 to "ABC", accessionId2 to "DEF"),
        store.fetchAccessionNumbers(listOf(accessionId1, accessionId2)),
    )
  }

  @Test
  fun `returns one entry per accession if the same ID is requested more than once`() {
    val accessionId = insertAccession(number = "ABC")

    assertEquals(
        mapOf(accessionId to "ABC"),
        store.fetchAccessionNumbers(listOf(accessionId, accessionId)),
    )
  }

  @Test
  fun `returns empty map if no accession IDs are requested`() {
    assertEquals(emptyMap<AccessionId, String>(), store.fetchAccessionNumbers(emptyList()))
  }

  @Test
  fun `throws exception if an accession does not exist`() {
    val accessionId = insertAccession()
    val nonexistentId = AccessionId(accessionId.value + 1)

    assertThrows<AccessionNotFoundException> {
      store.fetchAccessionNumbers(listOf(accessionId, nonexistentId))
    }
  }

  @Test
  fun `throws exception if none of the accessions exist`() {
    val accessionId = insertAccession()

    assertThrows<AccessionNotFoundException> {
      store.fetchAccessionNumbers(listOf(AccessionId(accessionId.value + 1)))
    }
  }

  @Test
  fun `throws exception if accessions are in different organizations`() {
    val accessionId = insertAccession()

    insertOrganization()
    insertOrganizationUser()
    insertFacility()
    val otherOrgAccessionId = insertAccession()

    assertThrows<IllegalArgumentException> {
      store.fetchAccessionNumbers(listOf(accessionId, otherOrgAccessionId))
    }
  }

  @Test
  fun `throws exception if no permission to read organization`() {
    val accessionId = insertAccession()

    deleteOrganizationUser()

    assertThrows<OrganizationNotFoundException> {
      store.fetchAccessionNumbers(listOf(accessionId))
    }
  }
}
