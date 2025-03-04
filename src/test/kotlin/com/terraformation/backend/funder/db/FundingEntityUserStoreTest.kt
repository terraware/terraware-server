package com.terraformation.backend.funder.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.funder.FundingEntityId
import com.terraformation.backend.mockUser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class FundingEntityUserStoreTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private lateinit var store: FundingEntityUserStore
  private lateinit var fundingEntityId: FundingEntityId

  @BeforeEach
  fun setUp() {
    store = FundingEntityUserStore(dslContext)

    fundingEntityId = insertFundingEntity()
  }

  @Test
  fun `getFundingEntityId returns null if userId isn't attached to FundingEntity`() {
    assertNull(store.getFundingEntityId(currentUser().userId))
  }

  @Test
  fun `getFundingEntityId returns correct funding entity id if userId attached to FundingEntity`() {
    insertFundingEntityUser(fundingEntityId, currentUser().userId)

    assertEquals(fundingEntityId, store.getFundingEntityId(currentUser().userId))
  }
}
