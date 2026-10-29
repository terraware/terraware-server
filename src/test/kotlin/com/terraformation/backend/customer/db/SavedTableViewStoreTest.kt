package com.terraformation.backend.customer.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.SavedTableViewNameExistsException
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.tables.records.SavedTableViewsRecord
import com.terraformation.backend.mockUser
import io.mockk.every
import org.jooq.JSONB
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SavedTableViewStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val store: SavedTableViewStore by lazy { SavedTableViewStore(dslContext) }

  private val settings = JSONB.valueOf("""{"sort":{"field":"name","direction":"asc"}}""")

  private lateinit var organizationId: OrganizationId

  @BeforeEach
  fun setUp() {
    organizationId = insertOrganization()

    every { user.canReadOrganization(any()) } returns true
  }

  @Nested
  inner class Create {
    @Test
    fun `creates organization-specific view`() {
      val id = store.create(organizationId, "species", "My view", settings)

      assertTableEquals(
          SavedTableViewsRecord(
              id = id,
              name = "My view",
              organizationId = organizationId,
              settings = settings,
              tableName = "species",
              userId = user.userId,
          )
      )
    }

    @Test
    fun `creates global view`() {
      val id = store.create(null, "species", "My view", settings)

      assertTableEquals(
          SavedTableViewsRecord(
              id = id,
              name = "My view",
              organizationId = null,
              settings = settings,
              tableName = "species",
              userId = user.userId,
          )
      )
    }

    @Test
    fun `allows same name for different tables, organizations, and users`() {
      val otherOrganizationId = insertOrganization()
      val otherUserId = insertUser()

      store.create(organizationId, "species", "My view", settings)
      store.create(organizationId, "accessions", "My view", settings)
      store.create(otherOrganizationId, "species", "My view", settings)
      store.create(null, "species", "My view", settings)
      insertSavedTableView(organizationId, "species", "My view", settings, otherUserId)

      assertTableEquals(
          listOf(
              SavedTableViewsRecord(
                  name = "My view",
                  organizationId = organizationId,
                  settings = settings,
                  tableName = "species",
                  userId = user.userId,
              ),
              SavedTableViewsRecord(
                  name = "My view",
                  organizationId = organizationId,
                  settings = settings,
                  tableName = "accessions",
                  userId = user.userId,
              ),
              SavedTableViewsRecord(
                  name = "My view",
                  organizationId = otherOrganizationId,
                  settings = settings,
                  tableName = "species",
                  userId = user.userId,
              ),
              SavedTableViewsRecord(
                  name = "My view",
                  organizationId = null,
                  settings = settings,
                  tableName = "species",
                  userId = user.userId,
              ),
              SavedTableViewsRecord(
                  name = "My view",
                  organizationId = organizationId,
                  settings = settings,
                  tableName = "species",
                  userId = otherUserId,
              ),
          )
      )
    }

    @Test
    fun `throws exception if name already exists for organization-specific view`() {
      insertSavedTableView(organizationId, "species", "My view")

      assertThrows<SavedTableViewNameExistsException> {
        store.create(organizationId, "species", "My view", settings)
      }
    }

    @Test
    fun `throws exception if name already exists for global view`() {
      insertSavedTableView(null, "species", "My view")

      assertThrows<SavedTableViewNameExistsException> {
        store.create(null, "species", "My view", settings)
      }
    }

    @Test
    fun `throws exception if no permission to read organization`() {
      every { user.canReadOrganization(organizationId) } returns false

      assertThrows<OrganizationNotFoundException> {
        store.create(organizationId, "species", "My view", settings)
      }
    }
  }
}
