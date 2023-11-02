package com.terraformation.backend.nursery.db.batchStore

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.IdentifierGenerator
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.nursery.tables.references.BATCHES
import com.terraformation.backend.db.nursery.tables.references.BATCH_DETAILS_HISTORY
import com.terraformation.backend.db.nursery.tables.references.BATCH_QUANTITY_HISTORY
import com.terraformation.backend.mockUser
import com.terraformation.backend.nursery.db.BatchStore
import com.terraformation.backend.nursery.model.NewBatchModel
import io.mockk.every
import java.time.LocalDate
import org.junit.jupiter.api.BeforeEach

internal abstract class BatchStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  override val tablesToResetSequences =
      listOf(BATCHES, BATCH_DETAILS_HISTORY, BATCH_QUANTITY_HISTORY)

  protected val clock = TestClock()
  protected val eventPublisher = TestEventPublisher()
  protected val store: BatchStore by lazy {
    BatchStore(
        batchDetailsHistoryDao,
        batchDetailsHistorySubLocationsDao,
        batchesDao,
        batchQuantityHistoryDao,
        batchWithdrawalsDao,
        clock,
        dslContext,
        eventPublisher,
        facilitiesDao,
        IdentifierGenerator(clock, dslContext),
        ParentStore(dslContext),
        projectsDao,
        subLocationsDao,
        nurseryWithdrawalsDao,
    )
  }

  protected lateinit var speciesId: SpeciesId

  @BeforeEach
  fun setUp() {
    insertUser()
    insertOrganization()
    insertFacility(name = "Nursery", type = FacilityType.Nursery)
    speciesId = insertSpecies()

    every { user.canCreateBatch(any()) } returns true
    every { user.canReadBatch(any()) } returns true
    every { user.canReadProject(any()) } returns true
    every { user.canReadSpecies(any()) } returns true
    every { user.canUpdateBatch(any()) } returns true
  }

  protected fun makeNewBatchModel() =
      NewBatchModel(
          addedDate = LocalDate.now(clock),
          facilityId = facilityId,
          germinatingQuantity = 0,
          notReadyQuantity = 1,
          readyQuantity = 2,
          speciesId = speciesId,
      )
}
