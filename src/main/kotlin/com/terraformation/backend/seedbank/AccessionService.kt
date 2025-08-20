package com.terraformation.backend.seedbank

import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.SeedQuantityUnits
import com.terraformation.backend.db.seedbank.ViabilityTestId
import com.terraformation.backend.db.seedbank.WithdrawalId
import com.terraformation.backend.db.seedbank.WithdrawalPurpose
import com.terraformation.backend.db.seedbank.tables.references.ACCESSIONS
import com.terraformation.backend.nursery.db.BatchStore
import com.terraformation.backend.nursery.db.CrossOrganizationNurseryTransferNotAllowedException
import com.terraformation.backend.nursery.model.ExistingBatchModel
import com.terraformation.backend.nursery.model.NewBatchModel
import com.terraformation.backend.search.SearchFieldPrefix
import com.terraformation.backend.search.SearchNode
import com.terraformation.backend.search.SearchService
import com.terraformation.backend.search.table.SearchTables
import com.terraformation.backend.seedbank.db.AccessionStore
import com.terraformation.backend.seedbank.db.PhotoRepository
import com.terraformation.backend.seedbank.model.AccessionModel
import com.terraformation.backend.seedbank.model.AccessionSummaryStatistics
import com.terraformation.backend.seedbank.model.SeedQuantityModel
import com.terraformation.backend.seedbank.model.ViabilityTestModel
import com.terraformation.backend.seedbank.model.WithdrawalModel
import jakarta.inject.Named
import java.math.BigDecimal
import org.jooq.DSLContext
import org.jooq.Record1
import org.jooq.Select
import org.jooq.impl.DSL

@Named
class AccessionService(
    private val accessionStore: AccessionStore,
    private val batchStore: BatchStore,
    private val dslContext: DSLContext,
    private val parentStore: ParentStore,
    private val photoRepository: PhotoRepository,
    private val searchService: SearchService,
    tables: SearchTables,
) {
  private val accessionsPrefix = SearchFieldPrefix(tables.accessions)
  private val accessionIdField = accessionsPrefix.resolve("id")

  /** Deletes an accession and all its associated data. */
  fun deleteAccession(accessionId: AccessionId) {
    requirePermissions { deleteAccession(accessionId) }

    // Note that this is not wrapped in a transaction; if this bombs out after having deleted some
    // but not all photos from the file store, we don't want to roll back the removal of the photos
    // that were successfully deleted or else we'll end up with dangling storage URLs.
    photoRepository.deleteAllPhotos(accessionId)
    accessionStore.delete(accessionId)
  }

  fun createWithdrawal(withdrawal: WithdrawalModel): AccessionModel {
    val accessionId =
        withdrawal.accessionId ?: throw IllegalArgumentException("Accession ID must be non-null")

    return updateAccession(accessionId) { it.addWithdrawal(withdrawal) }
  }

  fun updateWithdrawal(
      accessionId: AccessionId,
      withdrawalId: WithdrawalId,
      modify: (WithdrawalModel) -> WithdrawalModel,
  ): AccessionModel {
    return updateAccession(accessionId) { it.updateWithdrawal(withdrawalId, modify) }
  }

  fun deleteWithdrawal(accessionId: AccessionId, withdrawalId: WithdrawalId): AccessionModel {
    return updateAccession(accessionId) { it.deleteWithdrawal(withdrawalId) }
  }

  /**
   * Withdraws seeds from a seed bank and creates a new seedling batch at a nursery.
   *
   * Withdrawal details are pulled from [batch], with the withdrawal quantity set to the sum of the
   * batch's germinating, active-growth, hardening-off and ready quantities.
   *
   * @return The updated accession model and the newly-created batch with its ID populated.
   */
  fun createNurseryTransfer(
      accessionId: AccessionId,
      batch: NewBatchModel,
      withdrawnByUserId: UserId? = null,
  ): Pair<AccessionModel, ExistingBatchModel> {
    requirePermissions {
      createBatch(batch.facilityId)
      updateAccession(accessionId)
    }

    val accession = accessionStore.fetchOneById(accessionId)

    if (accession.speciesId == null) {
      throw IllegalArgumentException("Cannot transfer from accession that has no species")
    }

    if (
        parentStore.getOrganizationId(accession.facilityId!!) !=
            parentStore.getOrganizationId(batch.facilityId)
    ) {
      throw CrossOrganizationNurseryTransferNotAllowedException(
          accession.facilityId,
          batch.facilityId,
      )
    }

    val totalSeeds =
        batch.germinatingQuantity +
            batch.activeGrowthQuantity +
            batch.hardeningOffQuantity +
            batch.readyQuantity

    if (totalSeeds <= 0) {
      throw IllegalArgumentException("Transfers must include at least 1 seed")
    }

    val batchWithAccessionData =
        batch.copy(
            accessionId = accessionId,
            projectId = accession.projectId,
            speciesId = accession.speciesId,
        )

    return dslContext.transactionResult { _ ->
      val updatedBatch = batchStore.create(batchWithAccessionData)

      val withdrawal =
          WithdrawalModel(
              accessionId = accessionId,
              batchId = updatedBatch.id,
              date = batch.addedDate,
              notes = batch.notes,
              purpose = WithdrawalPurpose.Nursery,
              withdrawn = SeedQuantityModel(BigDecimal(totalSeeds), SeedQuantityUnits.Seeds),
              withdrawnByUserId = withdrawnByUserId,
          )
      val updatedAccession = createWithdrawal(withdrawal)

      updatedAccession to updatedBatch
    }
  }

  fun createViabilityTest(viabilityTest: ViabilityTestModel): AccessionModel {
    val accessionId =
        viabilityTest.accessionId ?: throw IllegalArgumentException("Accession ID must be non-null")

    return updateAccession(accessionId) { it.addViabilityTest(viabilityTest) }
  }

  fun updateViabilityTest(
      accessionId: AccessionId,
      viabilityTestId: ViabilityTestId,
      modify: (ViabilityTestModel) -> ViabilityTestModel,
  ): AccessionModel {
    return updateAccession(accessionId) { it.updateViabilityTest(viabilityTestId, modify) }
  }

  fun deleteViabilityTest(
      accessionId: AccessionId,
      viabilityTestId: ViabilityTestId,
  ): AccessionModel {
    return updateAccession(accessionId) { it.deleteViabilityTest(viabilityTestId) }
  }

  /**
   * Returns statistics about accessions that match a set of search criteria.
   *
   * If there are fuzzy search criteria, this method first checks to see if treating them as exact
   * matches returns any results; if not, it does the fuzzy search as requested. This mirrors the
   * behavior of SearchService.search(), such that the summary data will be consistent with the
   * search results.
   */
  fun getSearchSummaryStatistics(originalCriteria: SearchNode): AccessionSummaryStatistics {
    val exactCriteria = originalCriteria.toExactSearch()
    val exactSummary = querySummary(exactCriteria)

    return if (exactSummary.isNonZero() || exactCriteria == originalCriteria) {
      exactSummary
    } else {
      querySummary(originalCriteria)
    }
  }

  private fun querySummary(criteria: SearchNode): AccessionSummaryStatistics {
    @Suppress("UNCHECKED_CAST")
    val query =
        searchService
            .buildQuery(
                accessionsPrefix,
                listOf(accessionIdField),
                mapOf(accessionsPrefix to criteria),
            )
            .toSelectQuery() as Select<Record1<AccessionId?>>

    return accessionStore.getSummaryStatistics(query)
  }

  private fun updateAccession(
      accessionId: AccessionId,
      modify: (AccessionModel) -> AccessionModel,
  ): AccessionModel {
    requirePermissions { updateAccession(accessionId) }

    return dslContext.transactionResult { _ ->
      lockAccession(accessionId)

      val existing = accessionStore.fetchOneById(accessionId)
      val modified = modify(existing)
      accessionStore.updateAndFetch(modified)
    }
  }

  private fun lockAccession(accessionId: AccessionId) {
    dslContext
        .select(DSL.one())
        .from(ACCESSIONS)
        .where(ACCESSIONS.ID.eq(accessionId))
        .forUpdate()
        .execute()
  }
}
