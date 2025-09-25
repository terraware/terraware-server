package com.terraformation.backend.customer.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.assertSetEquals
import com.terraformation.backend.customer.model.InternalTagIds
import com.terraformation.backend.customer.model.OrganizationModel
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.InternalTagIsSystemDefinedException
import com.terraformation.backend.db.default_schema.InternalTagId
import com.terraformation.backend.db.default_schema.tables.pojos.InternalTagsRow
import com.terraformation.backend.db.default_schema.tables.pojos.OrganizationInternalTagsRow
import com.terraformation.backend.mockUser
import io.mockk.every
import java.time.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
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
    every { user.canManageInternalTags() } returns true
    every { user.canReadInternalTags() } returns true
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
        internalTagsDao.fetchOneById(tagId),
    )
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
        internalTagsDao.fetchOneById(tagId),
    )
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
    val organizationId = insertOrganization()
    val otherTagId = insertInternalTag()
    val otherOrganizationId = insertOrganization()
    insertOrganizationInternalTag(organizationId, InternalTagIds.Reporter)
    insertOrganizationInternalTag(otherOrganizationId, otherTagId)

    assertEquals(
        listOf(organizationId),
        store.fetchOrganizationsByTagId(InternalTagIds.Reporter).map { it.id },
    )
  }

  @Test
  fun `fetchAllOrganizationTagIds handles organizations with multiple tags`() {
    val organizationId = insertOrganization()
    val otherOrganizationId = insertOrganization()
    insertOrganizationInternalTag(organizationId, InternalTagIds.Reporter)
    insertOrganizationInternalTag(organizationId, InternalTagIds.Testing)
    insertOrganizationInternalTag(otherOrganizationId, InternalTagIds.Testing)

    assertEquals(
        mapOf(
            organizationId to setOf(InternalTagIds.Reporter, InternalTagIds.Testing),
            otherOrganizationId to setOf(InternalTagIds.Testing),
        ),
        store.fetchAllOrganizationTagIds(),
    )
  }

  @Nested
  inner class FetchAllOrganizationsWithTagIds {
    @Test
    fun `handles organizations with 0, 1, and multiple tags`() {
      val organizationIdWithNoTags = insertOrganization(name = "No Tags")
      val organizationIdWithOneTag = insertOrganization(name = "One Tag")
      insertOrganizationInternalTag(tagId = InternalTagIds.Testing)
      val organizationIdWithTwoTags = insertOrganization(name = "Two Tags")
      insertOrganizationInternalTag(tagId = InternalTagIds.Accelerator)
      insertOrganizationInternalTag(tagId = InternalTagIds.Reporter)

      val noTagsModel =
          OrganizationModel(
              id = organizationIdWithNoTags,
              name = "No Tags",
              createdTime = Instant.EPOCH,
              totalUsers = 0,
          )
      val oneTagModel = noTagsModel.copy(id = organizationIdWithOneTag, name = "One Tag")
      val twoTagsModel = noTagsModel.copy(id = organizationIdWithTwoTags, name = "Two Tags")

      assertEquals(
          mapOf(
              noTagsModel to emptySet(),
              oneTagModel to setOf(InternalTagIds.Testing),
              twoTagsModel to setOf(InternalTagIds.Accelerator, InternalTagIds.Reporter),
          ),
          store.fetchAllOrganizationsWithTagIds(),
      )
    }

    @Test
    fun `throws exception if no permission to read internal tags`() {
      every { user.canReadInternalTags() } returns false

      assertThrows<AccessDeniedException> { store.fetchAllOrganizationsWithTagIds() }
    }
  }

  @Test
  fun `fetchTagsByOrganization returns correct tag IDs`() {
    val organizationId = insertOrganization()
    val otherOrganizationId = insertOrganization()
    insertOrganizationInternalTag(organizationId, InternalTagIds.Reporter)
    insertOrganizationInternalTag(organizationId, InternalTagIds.Testing)
    insertOrganizationInternalTag(otherOrganizationId, InternalTagIds.Internal)

    assertSetEquals(
        setOf(InternalTagIds.Reporter, InternalTagIds.Testing),
        store.fetchTagsByOrganization(organizationId),
    )
  }

  @Test
  fun `fetchTagById returns existing tag`() {
    val row = store.fetchTagById(InternalTagIds.Reporter)

    assertEquals("Reporter", row.name)
  }

  @Test
  fun `updateOrganizationTags inserts and deletes values`() {
    val organizationId = insertOrganization()
    val otherOrganizationId = insertOrganization()
    insertOrganizationInternalTag(organizationId, InternalTagIds.Reporter)
    insertOrganizationInternalTag(organizationId, InternalTagIds.Internal)
    insertOrganizationInternalTag(otherOrganizationId, InternalTagIds.Reporter)

    val newTime = Instant.ofEpochSecond(1000)
    clock.instant = newTime

    store.updateOrganizationTags(
        organizationId,
        setOf(InternalTagIds.Reporter, InternalTagIds.Testing),
    )

    assertSetEquals(
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
        organizationInternalTagsDao.findAll().toSet(),
    )
  }

  @Test
  fun `write methods throw AccessDeniedException if no permission to manage internal tags`() {
    val organizationId = insertOrganization()
    val tagId = insertInternalTag()

    every { user.canManageInternalTags() } returns false

    fun check(name: String, func: () -> Unit) = Executable {
      assertThrows<AccessDeniedException>(name) { func() }
    }

    assertAll(
        check("deleteTag") { store.deleteTag(tagId) },
        check("updateTag") { store.updateTag(tagId, "x", "y") },
        check("updateOrganizationTags") {
          store.updateOrganizationTags(organizationId, emptySet())
        },
    )
  }

  @Test
  fun `read methods throw AccessDeniedException if no permission to read internal tags`() {
    val organizationId = insertOrganization()
    val tagId = insertInternalTag()

    every { user.canManageInternalTags() } returns false
    every { user.canReadInternalTags() } returns false

    fun check(name: String, func: () -> Unit) = Executable {
      assertThrows<AccessDeniedException>(name) { func() }
    }

    assertAll(
        check("fetchTagById") { store.fetchTagById(tagId) },
        check("findAllTags") { store.findAllTags() },
        check("fetchOrganizationsByTagId") { store.fetchOrganizationsByTagId(tagId) },
        check("fetchAllOrganizationTagIds") { store.fetchAllOrganizationTagIds() },
        check("fetchTagsByOrganization") { store.fetchTagsByOrganization(organizationId) },
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
