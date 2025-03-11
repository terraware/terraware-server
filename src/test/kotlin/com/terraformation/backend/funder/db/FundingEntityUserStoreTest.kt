package com.terraformation.backend.funder.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.UserNotFoundException
import com.terraformation.backend.db.funder.tables.records.FundingEntityUsersRecord
import com.terraformation.backend.mockUser
import io.mockk.every
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FundingEntityUserStoreTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val store by lazy { FundingEntityUserStore(dslContext) }
  private val fundingEntityId by lazy { insertFundingEntity() }

  private val testUserId by lazy { insertUser() }

  @BeforeEach
  fun setUp() {
    every { user.canReadUser(testUserId) } returns true
  }

  @Test
  fun `getFundingEntityId returns null if userId isn't attached to FundingEntity`() {
    assertNull(store.getFundingEntityId(testUserId))
  }

  @Test
  fun `getFundingEntityId throws exception if missing permissions`() {
    every { user.canReadUser(testUserId) } returns false
    assertThrows<UserNotFoundException> { store.getFundingEntityId(testUserId) }
  }

  @Test
  fun `getFundingEntityId returns correct funding entity id if userId attached to FundingEntity`() {
    insertFundingEntityUser(fundingEntityId, testUserId)

    assertEquals(fundingEntityId, store.getFundingEntityId(testUserId))
    assertTableEquals(
        FundingEntityUsersRecord(fundingEntityId = fundingEntityId, userId = testUserId))
  }

  @Test
  fun `fetchEntityByUserId returns null if userId isn't attached to FundingEntity`() {
    assertNull(store.fetchEntityByUserId(testUserId))
  }

  @Test
  fun `fetchEntityByUserId throws exception if missing permissions`() {
    every { user.canReadUser(testUserId) } returns false
    assertThrows<UserNotFoundException> { store.fetchEntityByUserId(testUserId) }
  }

  @Test
  fun `fetchEntityByUserId returns correct funding entity id if userId attached to FundingEntity`() {
    insertFundingEntityUser(fundingEntityId, testUserId)

    assertEquals(fundingEntityId, store.fetchEntityByUserId(testUserId)!!.id)
  }
}
