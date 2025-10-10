package com.terraformation.backend.customer.search

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.assertJsonEquals
import com.terraformation.backend.customer.model.InternalTagIds
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectInternalRole
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.mockUser
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.NoConditionNode
import com.terraformation.backend.search.SearchFieldPrefix
import com.terraformation.backend.search.SearchResults
import com.terraformation.backend.search.SearchService
import com.terraformation.backend.search.table.SearchTables
import io.mockk.every
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ProjectInternalUsersSearchTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private lateinit var organizationId: OrganizationId
  private val clock = TestClock()
  private val searchService: SearchService by lazy { SearchService(dslContext) }
  private val searchTables = SearchTables(clock)

  @BeforeEach
  fun setUp() {
    organizationId = insertOrganization()
    insertOrganizationUser(
        userId = inserted.userId,
    )

    every { user.organizationRoles } returns mapOf(inserted.organizationId to Role.Admin)
  }

  @Test
  fun `returns internal users`() {
    val projectId = insertProject()
    val projectLead = insertUser()
    insertOrganizationUser()
    insertProjectInternalUser(role = ProjectInternalRole.ProjectLead)
    val other = insertUser()
    insertOrganizationUser()
    insertProjectInternalUser(roleName = "Other")
    // other org internal users are excluded
    insertOrganization()
    insertProject()
    insertUser()
    insertOrganizationUser()
    insertProjectInternalUser(role = ProjectInternalRole.RestorationLead)

    val prefix = SearchFieldPrefix(searchTables.projectInternalUsers)
    val fields = listOf("user_id", "role", "roleName", "project_id").map { prefix.resolve(it) }
    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "project_id" to "$projectId",
                    "role" to "Project Lead",
                    "user_id" to "$projectLead",
                ),
                mapOf(
                    "project_id" to "$projectId",
                    "roleName" to "Other",
                    "user_id" to "$other",
                ),
            )
        )

    val actual =
        searchService.search(
            prefix,
            fields,
            mapOf(prefix to NoConditionNode()),
        )

    assertJsonEquals(expected, actual)
  }

  @Test
  fun `allows accelerator readers to see internal users of other organizations`() {
    every { user.canReadAllAcceleratorDetails() } returns true
    val projectId = insertProject()
    val projectLead = insertUser()
    insertProjectInternalUser(role = ProjectInternalRole.ProjectLead)
    val otherOrg = insertOrganization()
    val otherProject = insertProject()
    val otherOrgUser = insertUser()
    insertProjectInternalUser(role = ProjectInternalRole.RestorationLead)
    insertOrganizationInternalTag(tagId = InternalTagIds.Accelerator)

    val prefix = SearchFieldPrefix(searchTables.projectInternalUsers)
    val fields =
        listOf("user_id", "project_organization_id", "role", "roleName", "project_id").map {
          prefix.resolve(it)
        }
    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "project_id" to "$projectId",
                    "role" to "Project Lead",
                    "user_id" to "$projectLead",
                    "project_organization_id" to "$organizationId",
                ),
                mapOf(
                    "role" to "Restoration Lead",
                    "project_id" to "$otherProject",
                    "user_id" to "$otherOrgUser",
                    "project_organization_id" to "$otherOrg",
                ),
            )
        )

    val actual =
        searchService.search(
            prefix,
            fields,
            mapOf(prefix to NoConditionNode()),
        )

    assertJsonEquals(expected, actual)
  }

  @Test
  fun `retrieves user details correctly when internal users are filtered`() {
    // this tests the ability to filter on a sublist field when there are flattened fields in the
    // sublist

    val project1 = insertProject()
    insertUser(firstName = "Project", lastName = "Lead")
    insertProjectInternalUser(role = ProjectInternalRole.ProjectLead)
    insertUser(firstName = "Restoration", lastName = "Lead")
    insertProjectInternalUser(role = ProjectInternalRole.RestorationLead)
    val project2 = insertProject()

    val prefix = SearchFieldPrefix(searchTables.projects)
    val fields =
        listOf(
                "id",
                "name",
                "internalUsers.user_firstName",
                "internalUsers.user_lastName",
            )
            .map { prefix.resolve(it) }

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$project1",
                    "name" to "Project 1",
                    "internalUsers" to
                        listOf(
                            mapOf(
                                "user_firstName" to "Project",
                                "user_lastName" to "Lead",
                            )
                        ),
                ),
                mapOf(
                    "id" to "$project2",
                    "name" to "Project 2",
                ),
            )
        )

    val actual =
        searchService.search(
            prefix,
            fields,
            mapOf(
                prefix.relativeSublistPrefix("internalUsers")!! to
                    FieldNode(prefix.resolve("internalUsers.role"), listOf("Project Lead"))
            ),
        )

    assertJsonEquals(expected, actual)
  }
}
