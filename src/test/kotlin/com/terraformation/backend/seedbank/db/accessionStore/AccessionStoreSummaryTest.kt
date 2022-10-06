package com.terraformation.backend.seedbank.db.accessionStore

import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.ProcessingMethod
import com.terraformation.backend.db.seedbank.SeedQuantityUnits
import com.terraformation.backend.db.seedbank.tables.pojos.AccessionsRow
import com.terraformation.backend.db.seedbank.tables.references.ACCESSIONS
import com.terraformation.backend.seedbank.model.AccessionModel
import com.terraformation.backend.seedbank.model.AccessionSummaryStatistics
import java.math.BigDecimal
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class AccessionStoreSummaryTest : AccessionStoreTest() {
  @Test
  fun `counts active accessions by facility`() {
    store.create(AccessionModel(facilityId = facilityId))
    assertEquals(1, store.countActive(facilityId))
  }

  @Test
  fun `counts active accessions by organization`() {
    store.create(AccessionModel(facilityId = facilityId))
    assertEquals(1, store.countActive(organizationId))
  }

  @Test
  fun countByState() {
    val otherOrganizationId = OrganizationId(2)
    val otherOrgFacilityId = FacilityId(4)
    val sameOrgFacilityId = FacilityId(5)
    insertOrganization(otherOrganizationId)
    insertFacility(otherOrgFacilityId, otherOrganizationId)
    insertFacility(sameOrgFacilityId)

    val toCreate =
        mapOf(
            facilityId to
                mapOf(
                    AccessionState.AwaitingProcessing to 2,
                    AccessionState.Dried to 1,
                    AccessionState.Drying to 2,
                    AccessionState.InStorage to 3,
                    AccessionState.Nursery to 1,
                    AccessionState.Pending to 4,
                    AccessionState.Processed to 1,
                    AccessionState.UsedUp to 2,
                    AccessionState.Withdrawn to 1,
                ),
            otherOrgFacilityId to
                mapOf(
                    AccessionState.InStorage to 1,
                    AccessionState.Withdrawn to 1,
                ),
            sameOrgFacilityId to
                mapOf(
                    AccessionState.Dried to 1,
                    AccessionState.Processed to 2,
                    AccessionState.Processing to 2,
                    AccessionState.Withdrawn to 1,
                ),
        )

    toCreate.forEach { (targetFacilityId, stateCounts) ->
      stateCounts.forEach { (state, count) ->
        repeat(count) { insertAccession(facilityId = targetFacilityId, stateId = state) }
      }
    }

    assertEquals(
        mapOf(
            AccessionState.AwaitingCheckIn to 0,
            AccessionState.AwaitingProcessing to 2,
            AccessionState.Dried to 1,
            AccessionState.Drying to 2,
            AccessionState.InStorage to 3,
            AccessionState.Pending to 4,
            AccessionState.Processed to 1,
            AccessionState.Processing to 0,
        ),
        store.countByState(facilityId),
        "Counts for single facility")

    assertEquals(
        mapOf(
            AccessionState.AwaitingCheckIn to 0,
            AccessionState.AwaitingProcessing to 2,
            AccessionState.Dried to 2,
            AccessionState.Drying to 2,
            AccessionState.InStorage to 3,
            AccessionState.Pending to 4,
            AccessionState.Processed to 3,
            AccessionState.Processing to 2,
        ),
        store.countByState(organizationId),
        "Counts for organization")
  }

  @Test
  fun `getSummaryStatistics counts seeds remaining`() {
    val otherOrganizationId = OrganizationId(2)
    val otherOrgFacilityId = FacilityId(4)
    val sameOrgFacilityId = FacilityId(5)
    insertOrganization(otherOrganizationId)
    insertFacility(otherOrgFacilityId, otherOrganizationId)
    insertFacility(sameOrgFacilityId)

    listOf(
            AccessionsRow(
                facilityId = facilityId,
                processingMethodId = ProcessingMethod.Count,
                remainingQuantity = BigDecimal(1),
                remainingUnitsId = SeedQuantityUnits.Seeds,
                stateId = AccessionState.Processing,
            ),
            // Second accession at same facility
            AccessionsRow(
                facilityId = facilityId,
                processingMethodId = ProcessingMethod.Count,
                remainingQuantity = BigDecimal(2),
                remainingUnitsId = SeedQuantityUnits.Seeds,
                stateId = AccessionState.Processed,
            ),
            // Wrong facility
            AccessionsRow(
                facilityId = sameOrgFacilityId,
                processingMethodId = ProcessingMethod.Count,
                remainingQuantity = BigDecimal(4),
                remainingUnitsId = SeedQuantityUnits.Seeds,
                stateId = AccessionState.Processing,
            ),
            // Wrong organization
            AccessionsRow(
                facilityId = otherOrgFacilityId,
                processingMethodId = ProcessingMethod.Count,
                remainingQuantity = BigDecimal(8),
                remainingUnitsId = SeedQuantityUnits.Seeds,
                stateId = AccessionState.Processing,
            ),
            // Accession not active
            AccessionsRow(
                facilityId = facilityId,
                processingMethodId = ProcessingMethod.Count,
                remainingQuantity = BigDecimal(16),
                remainingUnitsId = SeedQuantityUnits.Seeds,
                stateId = AccessionState.Withdrawn,
            ),
            // Weight-based accession
            AccessionsRow(
                facilityId = facilityId,
                processingMethodId = ProcessingMethod.Weight,
                remainingGrams = BigDecimal(32),
                remainingQuantity = BigDecimal(32),
                remainingUnitsId = SeedQuantityUnits.Grams,
                stateId = AccessionState.Processing,
                subsetCount = 10,
                subsetWeightGrams = BigDecimal(10),
            ),
        )
        .forEach { insertAccession(it) }

    assertEquals(
        3,
        store.getSummaryStatistics(facilityId).subtotalBySeedCount,
        "Seeds remaining for single facility")
    assertEquals(
        7,
        store.getSummaryStatistics(organizationId).subtotalBySeedCount,
        "Seeds remaining for organization")
    assertEquals(
        1,
        store
            .getSummaryStatistics(
                DSL.select(ACCESSIONS.ID)
                    .from(ACCESSIONS)
                    .where(ACCESSIONS.FACILITY_ID.eq(facilityId))
                    .and(ACCESSIONS.STATE_ID.eq(AccessionState.Processing)))
            .subtotalBySeedCount,
        "Seeds remaining for subquery")
  }

  @Test
  fun `getSummaryStatistics estimates seeds remaining by weight`() {
    val otherOrganizationId = OrganizationId(2)
    val otherOrgFacilityId = FacilityId(4)
    val sameOrgFacilityId = FacilityId(5)
    insertOrganization(otherOrganizationId)
    insertFacility(otherOrgFacilityId, otherOrganizationId)
    insertFacility(sameOrgFacilityId)

    listOf(
            AccessionsRow(
                facilityId = facilityId,
                processingMethodId = ProcessingMethod.Weight,
                remainingGrams = BigDecimal(1),
                remainingQuantity = BigDecimal(1),
                remainingUnitsId = SeedQuantityUnits.Grams,
                stateId = AccessionState.Processing,
                subsetCount = 10,
                subsetWeightGrams = BigDecimal(10),
            ),
            // Second accession at same facility
            AccessionsRow(
                facilityId = facilityId,
                processingMethodId = ProcessingMethod.Weight,
                remainingGrams = BigDecimal(2000),
                remainingQuantity = BigDecimal(2),
                remainingUnitsId = SeedQuantityUnits.Kilograms,
                stateId = AccessionState.Processed,
                subsetCount = 1,
                subsetWeightGrams = BigDecimal(1000),
            ),
            // Wrong facility
            AccessionsRow(
                facilityId = sameOrgFacilityId,
                processingMethodId = ProcessingMethod.Weight,
                remainingGrams = BigDecimal(4),
                remainingQuantity = BigDecimal(4),
                remainingUnitsId = SeedQuantityUnits.Grams,
                stateId = AccessionState.Processing,
                subsetCount = 10,
                subsetWeightGrams = BigDecimal(10),
            ),
            // Wrong organization
            AccessionsRow(
                facilityId = otherOrgFacilityId,
                processingMethodId = ProcessingMethod.Weight,
                remainingGrams = BigDecimal(8),
                remainingQuantity = BigDecimal(8),
                remainingUnitsId = SeedQuantityUnits.Grams,
                stateId = AccessionState.Processing,
                subsetCount = 10,
                subsetWeightGrams = BigDecimal(10),
            ),
            // Accession not active
            AccessionsRow(
                facilityId = facilityId,
                processingMethodId = ProcessingMethod.Weight,
                remainingGrams = BigDecimal(16),
                remainingQuantity = BigDecimal(16),
                remainingUnitsId = SeedQuantityUnits.Grams,
                stateId = AccessionState.Withdrawn,
                subsetCount = 10,
                subsetWeightGrams = BigDecimal(10),
            ),
            // No subset count
            AccessionsRow(
                facilityId = facilityId,
                processingMethodId = ProcessingMethod.Weight,
                remainingGrams = BigDecimal(32),
                remainingQuantity = BigDecimal(32),
                remainingUnitsId = SeedQuantityUnits.Grams,
                stateId = AccessionState.Processing,
                subsetWeightGrams = BigDecimal(10),
            ),
            // No subset weight
            AccessionsRow(
                facilityId = facilityId,
                processingMethodId = ProcessingMethod.Weight,
                remainingGrams = BigDecimal(64),
                remainingQuantity = BigDecimal(64),
                remainingUnitsId = SeedQuantityUnits.Grams,
                stateId = AccessionState.Processing,
                subsetCount = 10,
            ),
            // Seed count, not weight
            AccessionsRow(
                facilityId = facilityId,
                processingMethodId = ProcessingMethod.Count,
                remainingQuantity = BigDecimal(64),
                remainingUnitsId = SeedQuantityUnits.Seeds,
                stateId = AccessionState.Processing,
            ),
        )
        .forEach { insertAccession(it) }

    assertEquals(
        3,
        store.getSummaryStatistics(facilityId).subtotalByWeightEstimate,
        "Seeds remaining for single facility")
    assertEquals(
        7,
        store.getSummaryStatistics(organizationId).subtotalByWeightEstimate,
        "Seeds remaining for organization")
    assertEquals(
        1,
        store
            .getSummaryStatistics(
                DSL.select(ACCESSIONS.ID)
                    .from(ACCESSIONS)
                    .where(ACCESSIONS.FACILITY_ID.eq(facilityId))
                    .and(ACCESSIONS.STATE_ID.eq(AccessionState.Processing)))
            .subtotalByWeightEstimate,
        "Seeds remaining for subquery")
  }

  @Test
  fun `getSummaryStatistics counts unknown-quantity accessions`() {
    val otherOrganizationId = OrganizationId(2)
    val otherOrgFacilityId = FacilityId(4)
    val sameOrgFacilityId = FacilityId(5)
    insertOrganization(otherOrganizationId)
    insertFacility(otherOrgFacilityId, otherOrganizationId)
    insertFacility(sameOrgFacilityId)

    listOf(
            // No subset weight, no subset count
            AccessionsRow(
                facilityId = facilityId,
                processingMethodId = ProcessingMethod.Weight,
                remainingGrams = BigDecimal(1),
                remainingQuantity = BigDecimal(1),
                remainingUnitsId = SeedQuantityUnits.Grams,
                stateId = AccessionState.Processing,
            ),
            // Weight but no count
            AccessionsRow(
                facilityId = facilityId,
                processingMethodId = ProcessingMethod.Weight,
                remainingGrams = BigDecimal(1),
                remainingQuantity = BigDecimal(1),
                remainingUnitsId = SeedQuantityUnits.Grams,
                stateId = AccessionState.Processing,
                subsetWeightGrams = BigDecimal.ONE,
            ),
            // Count but no weight
            AccessionsRow(
                facilityId = facilityId,
                processingMethodId = ProcessingMethod.Weight,
                remainingGrams = BigDecimal(1),
                remainingQuantity = BigDecimal(1),
                remainingUnitsId = SeedQuantityUnits.Grams,
                stateId = AccessionState.Processed,
                subsetCount = 10,
            ),
            // Subset weight/count present
            AccessionsRow(
                facilityId = facilityId,
                processingMethodId = ProcessingMethod.Weight,
                remainingGrams = BigDecimal(1),
                remainingQuantity = BigDecimal(1),
                remainingUnitsId = SeedQuantityUnits.Grams,
                stateId = AccessionState.Processing,
                subsetCount = 10,
                subsetWeightGrams = BigDecimal(10),
            ),
            // Wrong facility
            AccessionsRow(
                facilityId = sameOrgFacilityId,
                processingMethodId = ProcessingMethod.Weight,
                remainingGrams = BigDecimal(1),
                remainingQuantity = BigDecimal(1),
                remainingUnitsId = SeedQuantityUnits.Grams,
                stateId = AccessionState.Processing,
            ),
            // Wrong organization
            AccessionsRow(
                facilityId = otherOrgFacilityId,
                processingMethodId = ProcessingMethod.Weight,
                remainingGrams = BigDecimal(1),
                remainingQuantity = BigDecimal(1),
                remainingUnitsId = SeedQuantityUnits.Grams,
                stateId = AccessionState.Processing,
            ),
            // Count-based accession
            AccessionsRow(
                facilityId = facilityId,
                processingMethodId = ProcessingMethod.Count,
                remainingQuantity = BigDecimal(32),
                remainingUnitsId = SeedQuantityUnits.Seeds,
                stateId = AccessionState.Processing,
            ),
        )
        .forEach { insertAccession(it) }

    assertEquals(
        3,
        store.getSummaryStatistics(facilityId).unknownQuantityAccessions,
        "Accessions of unknown seed quantity for single facility")
    assertEquals(
        4,
        store.getSummaryStatistics(organizationId).unknownQuantityAccessions,
        "Accessions of unknown seed quantity for organization")
    assertEquals(
        2,
        store
            .getSummaryStatistics(
                DSL.select(ACCESSIONS.ID)
                    .from(ACCESSIONS)
                    .where(ACCESSIONS.FACILITY_ID.eq(facilityId))
                    .and(ACCESSIONS.STATE_ID.eq(AccessionState.Processing)))
            .unknownQuantityAccessions,
        "Accessions of unknown seed quantity for subquery")
  }

  @Test
  fun `getSummaryStatistics counts species`() {
    val otherOrganizationId = OrganizationId(2)
    val otherOrgFacilityId = FacilityId(4)
    val sameOrgFacilityId = FacilityId(5)
    val speciesId = SpeciesId(1)
    val sameOrgSpeciesId = SpeciesId(2)
    val inactiveAccessionSpeciesId = SpeciesId(3)
    val otherOrgSpeciesId = SpeciesId(4)

    insertOrganization(otherOrganizationId)
    insertFacility(otherOrgFacilityId, otherOrganizationId)
    insertFacility(sameOrgFacilityId)
    insertSpecies(speciesId)
    insertSpecies(sameOrgSpeciesId)
    insertSpecies(inactiveAccessionSpeciesId)
    insertSpecies(otherOrgSpeciesId, organizationId = otherOrganizationId)

    listOf(
            // No species ID
            AccessionsRow(
                facilityId = facilityId,
                stateId = AccessionState.Pending,
            ),
            // Species in org
            AccessionsRow(
                facilityId = facilityId,
                speciesId = speciesId,
                stateId = AccessionState.Pending,
            ),
            // Second accession with same species at different facility
            AccessionsRow(
                facilityId = sameOrgFacilityId,
                speciesId = speciesId,
                stateId = AccessionState.Pending,
            ),
            // Second species at different facility
            AccessionsRow(
                facilityId = sameOrgFacilityId,
                speciesId = sameOrgSpeciesId,
                stateId = AccessionState.Pending,
            ),
            // Third species, but it is in a different organization
            AccessionsRow(
                facilityId = otherOrgFacilityId,
                speciesId = otherOrgSpeciesId,
                stateId = AccessionState.Pending,
            ),
        )
        .forEach { insertAccession(it) }

    assertEquals(1, store.getSummaryStatistics(facilityId).species, "Species for single facility")
    assertEquals(2, store.getSummaryStatistics(organizationId).species, "Species for organization")
    assertEquals(
        1,
        store
            .getSummaryStatistics(
                DSL.select(ACCESSIONS.ID)
                    .from(ACCESSIONS)
                    .where(ACCESSIONS.FACILITY_ID.eq(facilityId)))
            .species,
        "Species for subquery")
  }

  @Test
  fun `getSummaryStatistics returns all zeroes if no accessions match criteria`() {
    val expected = AccessionSummaryStatistics(0, 0, 0, 0, 0, 0)

    assertEquals(expected, store.getSummaryStatistics(facilityId), "No accessions in facility")
    assertEquals(
        expected, store.getSummaryStatistics(organizationId), "No accessions in organization")
    assertEquals(
        expected,
        store.getSummaryStatistics(
            DSL.select(ACCESSIONS.ID).from(ACCESSIONS).where(DSL.falseCondition())),
        "No accessions for subquery")
  }
}
