package com.terraformation.backend.funder.db

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

class FundingEntityStoreTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private lateinit var store: FundingEntityStore

  private lateinit var fundingEntityId: FundingEntityId

  @BeforeEach
  fun setUp() {
    store = FundingEntityStore(dslContext)

    fundingEntityId = insertFundingEntity()

    every { user.canReadFundingEntities() } returns true
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
  fun `fetchById respects fetch depth`() {
    insertOrganization()
    val projectId1 = insertProject()
    val projectId2 = insertProject()

    var entity = store.fetchOneById(fundingEntityId, FundingEntityStore.FetchDepth.Project)
    assertEquals(0, entity.projects!!.size)

    insertFundingEntityProject(fundingEntityId, projectId1)
    insertFundingEntityProject(fundingEntityId, projectId2)

    entity = store.fetchOneById(fundingEntityId, FundingEntityStore.FetchDepth.Project)
    assertEquals(2, entity.projects!!.size)
  }
}
