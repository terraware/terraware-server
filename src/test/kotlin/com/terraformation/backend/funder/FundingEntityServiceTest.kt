package com.terraformation.backend.funder

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.funder.FundingEntityId
import com.terraformation.backend.mockUser
import io.mockk.every
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class FundingEntityServiceTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private lateinit var fundingEntityId: FundingEntityId
  private lateinit var service: FundingEntityService

  @BeforeEach
  fun setUp() {
    service = FundingEntityService(dslContext)

    fundingEntityId = insertFundingEntity()

    every { user.canManageFundingEntities() } returns true
  }

  @Test
  fun `delete throws exception if user can't manage funding entities`() {
    every { user.canManageFundingEntities() } returns false

    assertThrows<AccessDeniedException> { service.deleteFundingEntity(fundingEntityId) }
  }

  @Test
  fun `delete successfully removes funding entity`() {
    service.deleteFundingEntity(fundingEntityId)

    assertEquals(0, fundingEntitiesDao.findAll().size)
  }
}
