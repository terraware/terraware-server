package com.terraformation.backend.customer.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.model.UserModel
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.tables.daos.OrganizationsDao
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class OrganizationStoreTest : DatabaseTest(), RunsAsUser {
  override val user: UserModel = mockk()

  private val clock: Clock = mockk()
  private lateinit var organizationsDao: OrganizationsDao
  private lateinit var store: OrganizationStore

  private val organizationId = OrganizationId(1)

  @BeforeEach
  fun setUp() {
    val jooqConfig = dslContext.configuration()

    organizationsDao = OrganizationsDao(jooqConfig)
    store = OrganizationStore(clock, dslContext, organizationsDao)

    every { user.canReadOrganization(any()) } returns true
    every { user.canReadProject(any()) } returns true
    every { user.canReadSite(any()) } returns true
    every { user.canReadFacility(any()) } returns true

    insertOrganization(organizationId.value)
  }

  @Test
  fun `fetchAll honors Organization depth`() {
    // TODO
  }
}
