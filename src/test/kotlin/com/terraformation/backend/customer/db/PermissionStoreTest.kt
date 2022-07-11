package com.terraformation.backend.customer.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.model.Role
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.UserId
import com.terraformation.backend.mockUser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class PermissionStoreTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private lateinit var permissionStore: PermissionStore

  @BeforeEach
  fun setUp() {
    permissionStore = PermissionStore(dslContext)
  }

  @Test
  fun `fetchFacilityRoles includes all facilities in organizations the user is in`() {
    insertTestData()
    assertEquals(
        mapOf(FacilityId(1000) to Role.CONTRIBUTOR, FacilityId(2000) to Role.MANAGER),
        permissionStore.fetchFacilityRoles(UserId(7)))
  }

  @Test
  fun `fetchFacilityRoles only includes facilities in organizations the user is in`() {
    insertTestData()
    assertEquals(
        mapOf(FacilityId(2000) to Role.OWNER), permissionStore.fetchFacilityRoles(UserId(6)))
  }

  @Test
  fun `fetchOrganizationRoles only includes organizations the user is in`() {
    insertTestData()
    assertEquals(
        mapOf(OrganizationId(2) to Role.OWNER), permissionStore.fetchOrganizationRoles(UserId(6)))
  }

  @Test
  fun `fetchOrganizationRoles includes all organizations the user is in`() {
    insertTestData()
    assertEquals(
        mapOf(OrganizationId(1) to Role.CONTRIBUTOR, OrganizationId(2) to Role.MANAGER),
        permissionStore.fetchOrganizationRoles(UserId(7)))
  }

  /**
   * Inserts some test data to exercise the fetch methods. The data set:
   *
   * ```
   * - Organization 1
   *   - Facility 1000
   * - Organization 2
   *   - Facility 2000
   *
   * - User 5
   *   - Org 1 role: manager
   * - User 6
   *   - Org 2 role: owner
   * - User 7
   *   - Org 1 role: contributor
   *   - Org 2 role: manager
   * ```
   */
  private fun insertTestData() {
    val structure =
        mapOf(
            OrganizationId(1) to listOf(FacilityId(1000)),
            OrganizationId(2) to listOf(FacilityId(2000)))

    insertUser()

    structure.forEach { (organizationId, facilities) ->
      insertOrganization(organizationId)
      facilities.forEach { facilityId -> insertFacility(facilityId, organizationId) }
    }

    configureUser(5, mapOf(1 to Role.MANAGER))
    configureUser(6, mapOf(2 to Role.OWNER))
    configureUser(7, mapOf(1 to Role.CONTRIBUTOR, 2 to Role.MANAGER))
  }

  private fun configureUser(userId: Long, roles: Map<Int, Role>) {
    insertUser(userId)
    roles.forEach { (orgId, role) -> insertOrganizationUser(userId, orgId, role) }
  }
}
