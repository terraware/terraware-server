package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.assertJsonEquals
import com.terraformation.backend.customer.model.InternalTagIds
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.mockUser
import com.terraformation.backend.search.NoConditionNode
import com.terraformation.backend.search.SearchFieldPrefix
import com.terraformation.backend.search.SearchResults
import com.terraformation.backend.search.SearchService
import com.terraformation.backend.search.table.SearchTables
import io.mockk.every
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AcceleratorSearchTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()
  private val searchService: SearchService by lazy { SearchService(dslContext) }
  private val searchTables = SearchTables(clock)

  @BeforeEach
  fun setUp() {
    insertUser()
    insertOrganization()
    insertOrganizationInternalTag(tagId = InternalTagIds.Accelerator)
    insertParticipant()
    insertProject(participantId = inserted.participantId)

    every { user.canReadAllAcceleratorDetails() } returns true
  }

  @Test
  fun `allows internal users to search accelerator organizations they are not in`() {
    every { user.organizationRoles } returns mapOf(organizationId to Role.TerraformationContact)

    insertOrganizationUser()

    val otherAcceleratorOrgId = insertOrganization(2)
    insertOrganizationInternalTag(otherAcceleratorOrgId, InternalTagIds.Accelerator)

    insertOrganization(3)

    val prefix = SearchFieldPrefix(searchTables.organizations)
    val fields = listOf(prefix.resolve("name"))

    val expected =
        SearchResults(
            listOf(
                mapOf("name" to "Organization 1"),
                mapOf("name" to "Organization 2"),
            ),
            null)

    assertJsonEquals(expected, searchService.search(prefix, fields, NoConditionNode()))
  }

  @Test
  fun `does not allow regular users to search accelerator organizations they are not in`() {
    every { user.canReadAllAcceleratorDetails() } returns false
    every { user.organizationRoles } returns mapOf(organizationId to Role.Contributor)

    insertOrganizationUser()

    val otherAcceleratorOrgId = insertOrganization(2)
    insertOrganizationInternalTag(otherAcceleratorOrgId, InternalTagIds.Accelerator)

    val prefix = SearchFieldPrefix(searchTables.organizations)
    val fields = listOf(prefix.resolve("name"))

    val expected = SearchResults(listOf(mapOf("name" to "Organization 1")), null)

    assertJsonEquals(expected, searchService.search(prefix, fields, NoConditionNode()))
  }

  @Test
  fun `allows internal users to search projects in accelerator organizations they are not in`() {
    every { user.organizationRoles } returns mapOf(organizationId to Role.TerraformationContact)

    insertOrganizationUser()

    val nonAcceleratorOrgId = insertOrganization(2)
    insertProject(organizationId = nonAcceleratorOrgId)

    val prefix = SearchFieldPrefix(searchTables.projects)
    val fields = listOf(prefix.resolve("name"))

    val expected = SearchResults(listOf(mapOf("name" to "Project 1")), null)

    assertJsonEquals(expected, searchService.search(prefix, fields, NoConditionNode()))
  }

  @Test
  fun `does not allow regular users to search projects in accelerator organizations they are not in`() {
    every { user.canReadAllAcceleratorDetails() } returns false
    every { user.organizationRoles } returns mapOf(organizationId to Role.Contributor)

    insertOrganizationUser()

    val otherAcceleratorOrgId = insertOrganization(2)
    insertOrganizationInternalTag(otherAcceleratorOrgId, InternalTagIds.Accelerator)
    insertProject(organizationId = otherAcceleratorOrgId)

    val prefix = SearchFieldPrefix(searchTables.projects)
    val fields = listOf(prefix.resolve("name"))

    val expected = SearchResults(listOf(mapOf("name" to "Project 1")), null)

    assertJsonEquals(expected, searchService.search(prefix, fields, NoConditionNode()))
  }
}
