package com.terraformation.backend.nursery.db.batchStore

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.IdentifierGenerator
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.nursery.tables.pojos.BatchesRow
import com.terraformation.backend.db.nursery.tables.references.BATCHES
import com.terraformation.backend.db.nursery.tables.references.BATCH_QUANTITY_HISTORY
import com.terraformation.backend.mockUser
import com.terraformation.backend.nursery.db.BatchStore
import io.mockk.every
import java.time.LocalDate
import org.junit.jupiter.api.BeforeEach

internal abstract class BatchStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  override val tablesToResetSequences = listOf(BATCHES, BATCH_QUANTITY_HISTORY)

  protected val clock = TestClock()
  protected val store: BatchStore by lazy {
    BatchStore(
        batchesDao,
        batchQuantityHistoryDao,
        batchWithdrawalsDao,
        clock,
        dslContext,
        IdentifierGenerator(clock, dslContext),
        ParentStore(dslContext),
        nurseryWithdrawalsDao,
    )
  }

  protected val speciesId = SpeciesId(1)

  @BeforeEach
  fun setUp() {
    insertUser()
    insertOrganization()
    insertFacility(name = "Nursery", type = FacilityType.Nursery)
    insertSpecies(speciesId)

    every { user.canCreateBatch(any()) } returns true
    every { user.canReadBatch(any()) } returns true
    every { user.canReadSpecies(any()) } returns true
    every { user.canUpdateBatch(any()) } returns true
  }

  protected fun makeBatchesRow() =
      BatchesRow(
          addedDate = LocalDate.now(clock),
          createdBy = user.userId,
          createdTime = clock.instant(),
          facilityId = facilityId,
          germinatingQuantity = 0,
          latestObservedGerminatingQuantity = 0,
          latestObservedNotReadyQuantity = 1,
          latestObservedReadyQuantity = 2,
          latestObservedTime = clock.instant(),
          modifiedBy = user.userId,
          modifiedTime = clock.instant(),
          notReadyQuantity = 1,
          organizationId = organizationId,
          readyQuantity = 2,
          speciesId = speciesId,
      )
}
