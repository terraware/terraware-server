package com.terraformation.backend.customer.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.customer.model.InternalTagIds
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.InternalTagIsSystemDefinedException
import com.terraformation.backend.db.default_schema.InternalTagId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.tables.pojos.InternalTagsRow
import com.terraformation.backend.db.default_schema.tables.pojos.OrganizationInternalTagsRow
import com.terraformation.backend.mockUser
import io.mockk.every
import java.time.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.function.Executable
import org.springframework.security.access.AccessDeniedException

class InternalTagStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()
  private val store: InternalTagStore by lazy {
    InternalTagStore(clock, dslContext, internalTagsDao, organizationInternalTagsDao)
  }

  @BeforeEach
  fun setUp() {
    insertUser()

    every { user.canManageInternalTags() } returns true
  }

  @Test
  fun `createTag populates default values`() {
    val tagId = store.createTag("name", "description")

    assertEquals(
        InternalTagsRow(
            createdBy = user.userId,
            createdTime = clock.instant,
            description = "description",
            id = tagId,
            isSystem = false,
            modifiedBy = user.userId,
            modifiedTime = clock.instant,
            name = "name",
        ),
        internalTagsDao.fetchOneById(tagId))
  }

  @Test
  fun `deleteTag deletes user-defined tag`() {
    val tagId = insertInternalTag()

    store.deleteTag(tagId)

    assertNull(internalTagsDao.fetchOneById(tagId))
  }

  @Test
  fun `deleteTag cannot delete system-defined tag`() {
    assertThrows<InternalTagIsSystemDefinedException> { store.deleteTag(InternalTagIds.Reporter) }
  }

  @Test
  fun `updateTag updates user-defined tag`() {
    val tagId = insertInternalTag()

    clock.instant = Instant.ofEpochSecond(1000)

    store.updateTag(tagId, "new name", "new description")

    assertEquals(
        InternalTagsRow(
            createdBy = user.userId,
            createdTime = Instant.EPOCH,
            description = "new description",
            id = tagId,
            isSystem = false,
            modifiedBy = user.userId,
            modifiedTime = clock.instant,
            name = "new name",
        ),
        internalTagsDao.fetchOneById(tagId))
  }

  @Test
  fun `updateTag cannot update system-defined tag`() {
    assertThrows<InternalTagIsSystemDefinedException> {
      store.updateTag(InternalTagIds.Reporter, "x", "y")
    }
  }

  @Test
  fun `findAllTags includes both system-defined and user-defined tags`() {
    val tagId = insertInternalTag()

    val tags = store.findAllTags()

    assertAll(
        { assertTrue(tags.any { it.id == tagId }, "Includes user-defined tag") },
        {
          assertTrue(tags.any { it.id == InternalTagIds.Reporter }, "Includes system-defined tag")
        },
    )
  }

  @Test
  fun `fetchOrganizationsByTagId returns tagged organizations`() {
    val otherTagId = insertInternalTag()
    val otherOrganizationId = OrganizationId(2)
    insertOrganization(organizationId)
    insertOrganization(otherOrganizationId)
    insertOrganizationInternalTag(organizationId, InternalTagIds.Reporter)
    insertOrganizationInternalTag(otherOrganizationId, otherTagId)

    assertEquals(
        listOf(organizationId),
        store.fetchOrganizationsByTagId(InternalTagIds.Reporter).map { it.id })
  }

  @Test
  fun `fetchAllOrganizationTagIds handles organizations with multiple tags`() {
    val otherOrganizationId = OrganizationId(2)
    insertOrganization(organizationId)
    insertOrganization(otherOrganizationId)
    insertOrganizationInternalTag(organizationId, InternalTagIds.Reporter)
    insertOrganizationInternalTag(organizationId, InternalTagIds.Testing)
    insertOrganizationInternalTag(otherOrganizationId, InternalTagIds.Testing)

    assertEquals(
        mapOf(
            organizationId to setOf(InternalTagIds.Reporter, InternalTagIds.Testing),
            otherOrganizationId to setOf(InternalTagIds.Testing),
        ),
        store.fetchAllOrganizationTagIds())
  }

  @Test
  fun `fetchTagsByOrganization returns correct tag IDs`() {
    val otherOrganizationId = OrganizationId(2)
    insertOrganization(organizationId)
    insertOrganization(otherOrganizationId)
    insertOrganizationInternalTag(organizationId, InternalTagIds.Reporter)
    insertOrganizationInternalTag(organizationId, InternalTagIds.Testing)
    insertOrganizationInternalTag(otherOrganizationId, InternalTagIds.Internal)

    assertEquals(
        setOf(InternalTagIds.Reporter, InternalTagIds.Testing),
        store.fetchTagsByOrganization(organizationId))
  }

  @Test
  fun `fetchTagById returns existing tag`() {
    val row = store.fetchTagById(InternalTagIds.Reporter)

    assertEquals("Reporter", row.name)
  }

  @Test
  fun `updateOrganizationTags inserts and deletes values`() {
    val otherOrganizationId = OrganizationId(2)
    insertOrganization(organizationId)
    insertOrganization(otherOrganizationId)
    insertOrganizationInternalTag(organizationId, InternalTagIds.Reporter)
    insertOrganizationInternalTag(organizationId, InternalTagIds.Internal)
    insertOrganizationInternalTag(otherOrganizationId, InternalTagIds.Reporter)

    val newTime = Instant.ofEpochSecond(1000)
    clock.instant = newTime

    store.updateOrganizationTags(
        organizationId, setOf(InternalTagIds.Reporter, InternalTagIds.Testing))

    assertEquals(
        setOf(
            OrganizationInternalTagsRow(
                createdBy = user.userId,
                createdTime = Instant.EPOCH,
                internalTagId = InternalTagIds.Reporter,
                organizationId = organizationId,
            ),
            OrganizationInternalTagsRow(
                createdBy = user.userId,
                createdTime = Instant.EPOCH,
                internalTagId = InternalTagIds.Reporter,
                organizationId = otherOrganizationId,
            ),
            OrganizationInternalTagsRow(
                createdBy = user.userId,
                createdTime = newTime,
                internalTagId = InternalTagIds.Testing,
                organizationId = organizationId,
            ),
        ),
        organizationInternalTagsDao.findAll().toSet())
  }

  @Test
  fun `all methods throw AccessDeniedException if no permission to manage internal tags`() {
    insertOrganization()
    val tagId = insertInternalTag()

    every { user.canManageInternalTags() } returns false

    fun check(name: String, func: () -> Unit) = Executable {
      assertThrows<AccessDeniedException>(name) { func() }
    }

    assertAll(
        check("fetchTagById") { store.fetchTagById(tagId) },
        check("deleteTag") { store.deleteTag(tagId) },
        check("updateTag") { store.updateTag(tagId, "x", "y") },
        check("findAllTags") { store.findAllTags() },
        check("fetchOrganizationsByTagId") { store.fetchOrganizationsByTagId(tagId) },
        check("fetchAllOrganizationTagIds") { store.fetchAllOrganizationTagIds() },
        check("fetchTagsByOrganization") { store.fetchTagsByOrganization(organizationId) },
        check("updateOrganizationTags") {
          store.updateOrganizationTags(organizationId, emptySet())
        },
    )
  }

  private fun insertInternalTag(name: String = "test", description: String? = null): InternalTagId {
    val row =
        InternalTagsRow(
            createdBy = user.userId,
            createdTime = clock.instant,
            description = description,
            isSystem = false,
            modifiedBy = user.userId,
            modifiedTime = clock.instant,
            name = name,
        )

    internalTagsDao.insert(row)

    return row.id!!
  }
}
