package com.terraformation.backend.funder.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.funder.FundingEntityId
import com.terraformation.backend.db.funder.tables.records.FundingEntityProjectsRecord
import com.terraformation.backend.db.funder.tables.references.FUNDING_ENTITY_PROJECTS
import com.terraformation.backend.mockUser
import io.mockk.every
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class FundingEntityStoreTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val store by lazy { FundingEntityStore(dslContext) }

  private val fundingEntityId by lazy { insertFundingEntity() }

  @BeforeEach
  fun setUp() {
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
    assertTrue(store.fetchOneById(fundingEntityId).name.startsWith("TestFundingEntity"))
  }

  @Test
  fun `fetchById retrieves projectIds`() {
    insertOrganization()
    val projectId1 = insertProject()
    val projectId2 = insertProject()

    assertTableEmpty(FUNDING_ENTITY_PROJECTS)

    insertFundingEntityProject(fundingEntityId, projectId1)
    insertFundingEntityProject(fundingEntityId, projectId2)

    assertTableEquals(
        listOf(
            FundingEntityProjectsRecord(
                fundingEntityId = fundingEntityId,
                projectId = projectId1,
            ),
            FundingEntityProjectsRecord(
                fundingEntityId = fundingEntityId,
                projectId = projectId2,
            ),
        ))
  }
}
