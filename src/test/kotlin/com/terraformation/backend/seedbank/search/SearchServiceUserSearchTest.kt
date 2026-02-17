package com.terraformation.backend.seedbank.search

import com.terraformation.backend.assertJsonEquals
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectInternalRole
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.UserType
import com.terraformation.backend.search.AndNode
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.NoConditionNode
import com.terraformation.backend.search.SearchFieldPrefix
import com.terraformation.backend.search.SearchResults
import com.terraformation.backend.search.SearchSortField
import io.mockk.every
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class SearchServiceUserSearchTest : SearchServiceTest() {
  private val organizationsPrefix = SearchFieldPrefix(tables.organizations)
  private val usersPrefix = SearchFieldPrefix(tables.users)

  private lateinit var otherOrganizationId: OrganizationId
  private lateinit var bothOrgsUserId: UserId
  private lateinit var otherOrgUserId: UserId
  private lateinit var deviceManagerUserId: UserId

  @BeforeEach
  fun insertOtherUsers() {
    deviceManagerUserId = insertUser(type = UserType.DeviceManager)
    bothOrgsUserId = insertUser(firstName = "User 1")
    otherOrgUserId = insertUser(firstName = "User 2")

    otherOrganizationId = insertOrganization()

    insertOrganizationUser(deviceManagerUserId, organizationId)
    insertOrganizationUser(bothOrgsUserId, organizationId, Role.Admin)
    insertOrganizationUser(bothOrgsUserId, otherOrganizationId, Role.Admin)
    insertOrganizationUser(otherOrgUserId, otherOrganizationId)
  }

  @Test
  fun `should not see inaccessible organizations of other users`() {
    val roleField = organizationsPrefix.resolve("members.roleName")
    val userIdField = organizationsPrefix.resolve("members.user.id")
    val userOrganizationIdField =
        organizationsPrefix.resolve("members.user.organizationMemberships.organization.id")

    val fields = listOf(userIdField, userOrganizationIdField, roleField)
    val sortOrder = fields.map { SearchSortField(it) }

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "members" to
                        listOf(
                            mapOf(
                                "roleName" to "Manager",
                                "user" to
                                    mapOf(
                                        "id" to "${user.userId}",
                                        "organizationMemberships" to
                                            listOf(
                                                mapOf(
                                                    "organization" to
                                                        mapOf("id" to "$organizationId"),
                                                ),
                                            ),
                                    ),
                            ),
                            mapOf(
                                "roleName" to "Admin",
                                "user" to
                                    mapOf(
                                        "id" to "$bothOrgsUserId",
                                        "organizationMemberships" to
                                            listOf(
                                                mapOf(
                                                    "organization" to
                                                        mapOf("id" to "$organizationId")
                                                )
                                            ),
                                    ),
                            ),
                        )
                )
            )
        )

    val actual =
        searchService.search(
            organizationsPrefix,
            fields,
            mapOf(organizationsPrefix to NoConditionNode()),
            sortOrder,
        )

    assertEquals(expected, actual)
  }

  @Test
  fun `should not see inaccessible organizations of other users via flattened sublists`() {
    val userIdField = usersPrefix.resolve("id")
    val userOrganizationIdField = usersPrefix.resolve("organizationMemberships_organization_id")

    val fields = listOf(userIdField, userOrganizationIdField)
    val sortOrder = listOf(SearchSortField(usersPrefix.resolve("firstName")))

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "${user.userId}",
                    "organizationMemberships_organization_id" to "$organizationId",
                ),
                mapOf(
                    "id" to "$bothOrgsUserId",
                    "organizationMemberships_organization_id" to "$organizationId",
                ),
            )
        )

    val actual =
        searchService.search(
            usersPrefix,
            fields,
            mapOf(usersPrefix to NoConditionNode()),
            sortOrder,
        )

    assertEquals(expected, actual)
  }

  @Test
  fun `should not see users with no mutual organizations`() {
    val userIdField = usersPrefix.resolve("id")
    val fields = listOf(userIdField)
    val sortOrder = fields.map { SearchSortField(it) }

    val expected =
        SearchResults(listOf(mapOf("id" to "${user.userId}"), mapOf("id" to "$bothOrgsUserId")))

    val actual =
        searchService.search(
            usersPrefix,
            fields,
            mapOf(usersPrefix to NoConditionNode()),
            sortOrder,
        )

    assertEquals(expected, actual)
  }

  @Test
  fun `should be able to filter organization members by organization`() {
    val membersPrefix = SearchFieldPrefix(tables.organizationUsers)
    val organizationIdField = membersPrefix.resolve("organization_id")
    val roleNameField = membersPrefix.resolve("roleName")

    val fields = listOf(organizationIdField, roleNameField)
    val criteria =
        AndNode(
            listOf(
                FieldNode(roleNameField, listOf(Role.Admin.getDisplayName(Locale.ENGLISH))),
                FieldNode(organizationIdField, listOf("$organizationId")),
            )
        )
    val sortOrder = fields.map { SearchSortField(it) }

    insertOrganizationUser(organizationId = otherOrganizationId, role = Role.Admin)
    every { user.organizationRoles } returns
        mapOf(
            organizationId to Role.Admin,
            otherOrganizationId to Role.Admin,
        )

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "organization_id" to "$organizationId",
                    "roleName" to Role.Admin.getDisplayName(Locale.ENGLISH),
                )
            )
        )

    val actual =
        searchService.search(membersPrefix, fields, mapOf(membersPrefix to criteria), sortOrder)
    assertEquals(expected, actual)
  }

  @Test
  fun `should be able to retrieve internal users that are not in the same org`() {
    every { user.canReadAllAcceleratorDetails() } returns true

    val projectId =
        insertProject(organizationId = organizationId, phase = CohortPhase.Phase0DueDiligence)
    insertProjectInternalUser(userId = bothOrgsUserId, role = ProjectInternalRole.RegionalExpert)
    insertProjectInternalUser(userId = otherOrgUserId, role = ProjectInternalRole.GISLead)

    val prefix = SearchFieldPrefix(tables.projects)
    val fields = listOf("id", "internalUsers.user_id").map { prefix.resolve(it) }

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$projectId",
                    "internalUsers" to
                        listOf(
                            mapOf("user_id" to "$bothOrgsUserId"),
                            mapOf("user_id" to "$otherOrgUserId"),
                        ),
                )
            )
        )

    val actual = searchService.search(prefix, fields, mapOf(prefix to NoConditionNode()))

    assertJsonEquals(expected, actual)
  }
}
