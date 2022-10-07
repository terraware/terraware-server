package com.terraformation.backend.seedbank.search

import com.terraformation.backend.customer.model.Role
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.UserType
import com.terraformation.backend.search.AndNode
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.NoConditionNode
import com.terraformation.backend.search.SearchFieldPrefix
import com.terraformation.backend.search.SearchResults
import com.terraformation.backend.search.SearchSortField
import io.mockk.every
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class SearchServiceUserSearchTest : SearchServiceTest() {
  private val organizationsPrefix = SearchFieldPrefix(tables.organizations)
  private val usersPrefix = SearchFieldPrefix(tables.users)

  private val otherOrganizationId = OrganizationId(2)
  private val bothOrgsUserId = UserId(4)
  private val otherOrgUserId = UserId(5)
  private val deviceManagerUserId = UserId(6)

  @BeforeEach
  fun insertOtherUsers() {
    insertUser(deviceManagerUserId, type = UserType.DeviceManager)
    insertUser(bothOrgsUserId)
    insertUser(otherOrgUserId)

    insertOrganization(otherOrganizationId)

    insertOrganizationUser(deviceManagerUserId)
    insertOrganizationUser(bothOrgsUserId, role = Role.ADMIN)
    insertOrganizationUser(bothOrgsUserId, otherOrganizationId, Role.ADMIN)
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
                                                        mapOf("id" to "$organizationId")))))))),
            null)

    val actual = searchService.search(organizationsPrefix, fields, NoConditionNode(), sortOrder)

    assertEquals(expected, actual)
  }

  @Test
  fun `should not see inaccessible organizations of other users via flattened sublists`() {
    val userIdField = usersPrefix.resolve("id")
    val userOrganizationIdField = usersPrefix.resolve("organizationMemberships_organization_id")

    val fields = listOf(userIdField, userOrganizationIdField)
    val sortOrder = fields.map { SearchSortField(it) }

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
                )),
            null)

    val actual = searchService.search(usersPrefix, fields, NoConditionNode(), sortOrder)

    assertEquals(expected, actual)
  }

  @Test
  fun `should not see users with no mutual organizations`() {
    val userIdField = usersPrefix.resolve("id")
    val fields = listOf(userIdField)
    val sortOrder = fields.map { SearchSortField(it) }

    val expected =
        SearchResults(
            listOf(mapOf("id" to "${user.userId}"), mapOf("id" to "$bothOrgsUserId")), null)

    val actual = searchService.search(usersPrefix, fields, NoConditionNode(), sortOrder)

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
                FieldNode(roleNameField, listOf(Role.ADMIN.displayName)),
                FieldNode(organizationIdField, listOf("$organizationId"))))
    val sortOrder = fields.map { SearchSortField(it) }

    insertOrganizationUser(organizationId = otherOrganizationId, role = Role.ADMIN)
    every { user.organizationRoles } returns
        mapOf(
            organizationId to Role.ADMIN,
            otherOrganizationId to Role.ADMIN,
        )

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "organization_id" to "$organizationId",
                    "roleName" to Role.ADMIN.displayName,
                )),
            null)

    val actual = searchService.search(membersPrefix, fields, criteria, sortOrder)
    assertEquals(expected, actual)
  }
}
