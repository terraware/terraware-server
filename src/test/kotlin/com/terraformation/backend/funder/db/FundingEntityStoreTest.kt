package com.terraformation.backend.funder.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.funder.FundingEntityId
import com.terraformation.backend.db.funder.tables.pojos.FundingEntitiesRow
import com.terraformation.backend.mockUser
import io.mockk.every
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class FundingEntityStoreTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val clock = TestClock()
  private lateinit var store: FundingEntityStore

  private lateinit var fundingEntityId: FundingEntityId

  @BeforeEach
  fun setUp() {
    store = FundingEntityStore(clock, dslContext)

    fundingEntityId = insertFundingEntity()

    every { user.canReadFundingEntities() } returns true
    every { user.canManageFundingEntities() } returns true
  }

  @Test
  fun `fetchById requires user to be able to read funding entities`() {
    every { user.canReadFundingEntities() } returns false

    assertThrows<AccessDeniedException> { store.fetchOneById(fundingEntityId) }
  }

  @Test
  fun `fetchById throws exception when entity doesn't exist`() {
    assertThrows<FundingEntityNotFoundException> { store.fetchOneById(FundingEntityId(1020)) }
  }

  @Test
  fun `fetchById returns correct funding entity`() {
    assertEquals("TestFundingEntity", store.fetchOneById(fundingEntityId).name)
  }

  @Test
  fun `create requires user to be able to manage funding entities`() {
    every { user.canManageFundingEntities() } returns false

    assertThrows<AccessDeniedException> { store.create("Some Other Entity") }
  }

  @Test
  fun `create populates created and modified fields`() {
    val row = FundingEntitiesRow(name = "New Funding Entity")
    val createdModel = store.create(row.name!!)

    val expected =
        row.copy(
            createdBy = user.userId,
            createdTime = clock.instant(),
            id = createdModel.id,
            modifiedBy = user.userId,
            modifiedTime = clock.instant(),
        )
    val actual = fundingEntitiesDao.fetchOneById(createdModel.id)

    assertEquals(expected, actual)
  }

  @Test
  fun `create rejects duplicates by name`() {
    assertThrows<FundingEntityExistsException> { store.create("TestFundingEntity") }
  }
}
