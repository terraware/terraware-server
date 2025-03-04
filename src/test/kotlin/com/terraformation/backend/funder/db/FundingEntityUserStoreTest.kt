package com.terraformation.backend.funder.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.funder.tables.records.FundingEntityUsersRecord
import com.terraformation.backend.mockUser
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class FundingEntityUserStoreTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val store by lazy { FundingEntityUserStore(dslContext) }
  private val fundingEntityId by lazy { insertFundingEntity() }

  @Test
  fun `getFundingEntityId returns null if userId isn't attached to FundingEntity`() {
    assertNull(store.getFundingEntityId(currentUser().userId))
  }

  @Test
  fun `getFundingEntityId returns correct funding entity id if userId attached to FundingEntity`() {
    insertFundingEntityUser(fundingEntityId, currentUser().userId)

    assertTableEquals(
        FundingEntityUsersRecord(fundingEntityId = fundingEntityId, userId = currentUser().userId))
  }
}
