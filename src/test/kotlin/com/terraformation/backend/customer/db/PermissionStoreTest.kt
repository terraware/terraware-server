package com.terraformation.backend.customer.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.assertSetEquals
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.mockUser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class PermissionStoreTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private lateinit var permissionStore: PermissionStore

  private lateinit var organizationId1: OrganizationId
  private lateinit var organizationId2: OrganizationId
  private lateinit var org1FacilityId: FacilityId
  private lateinit var org2FacilityId: FacilityId
  private lateinit var org2Owner: UserId
  private lateinit var org1Contributor2Manager: UserId

  @BeforeEach
  fun setUp() {
    permissionStore = PermissionStore(dslContext)
  }

  @Test
  fun `fetchFacilityRoles includes all facilities in organizations the user is in`() {
    insertTestData()
    assertEquals(
        mapOf(org1FacilityId to Role.Contributor, org2FacilityId to Role.Manager),
        permissionStore.fetchFacilityRoles(org1Contributor2Manager),
    )
  }

  @Test
  fun `fetchFacilityRoles only includes facilities in organizations the user is in`() {
    insertTestData()
    assertEquals(mapOf(org2FacilityId to Role.Owner), permissionStore.fetchFacilityRoles(org2Owner))
  }

  @Test
  fun `fetchOrganizationRoles only includes organizations the user is in`() {
    insertTestData()
    assertEquals(
        mapOf(organizationId2 to Role.Owner),
        permissionStore.fetchOrganizationRoles(org2Owner),
    )
  }

  @Test
  fun `fetchOrganizationRoles includes all organizations the user is in`() {
    insertTestData()
    assertEquals(
        mapOf(organizationId1 to Role.Contributor, organizationId2 to Role.Manager),
        permissionStore.fetchOrganizationRoles(org1Contributor2Manager),
    )
  }

  @Test
  fun `fetchGlobalRoles returns empty set if user has no global roles`() {
    assertSetEquals(emptySet<GlobalRole>(), permissionStore.fetchGlobalRoles(user.userId))
  }

  @Test
  fun `fetchGlobalRoles returns set of global roles`() {
    insertUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
    insertUserGlobalRole(role = GlobalRole.TFExpert)
    insertUserGlobalRole(role = GlobalRole.ReadOnly)
    insertUserGlobalRole(role = GlobalRole.SuperAdmin)

    assertSetEquals(
        setOf(
            GlobalRole.AcceleratorAdmin,
            GlobalRole.TFExpert,
            GlobalRole.ReadOnly,
            GlobalRole.SuperAdmin,
        ),
        permissionStore.fetchGlobalRoles(user.userId),
    )
  }

  @Test
  fun `fetchFundingEntity returns funding entity`() {
    assertEquals(null, permissionStore.fetchFundingEntity(user.userId))
    val fundingEntityId = insertFundingEntity()
    insertFundingEntityUser(fundingEntityId, user.userId)

    assertEquals(fundingEntityId, permissionStore.fetchFundingEntity(user.userId))
  }

  /**
   * Inserts some test data to exercise the fetch methods. The data set:
   * ```
   * - Organization 1
   *   - Facility org1FacilityId
   * - Organization 2
   *   - Facility org2FacilityId
   *
   * - User
   *   - Org 1 role: manager
   * - User org2Owner
   *   - Org 2 role: owner
   * - User org1Contributor2Manager
   *   - Org 1 role: contributor
   *   - Org 2 role: manager
   * ```
   */
  private fun insertTestData() {
    organizationId1 = insertOrganization()
    org1FacilityId = insertFacility()
    organizationId2 = insertOrganization()
    org2FacilityId = insertFacility()

    configureUser(mapOf(organizationId1 to Role.Manager))
    org2Owner = configureUser(mapOf(organizationId2 to Role.Owner))
    org1Contributor2Manager =
        configureUser(mapOf(organizationId1 to Role.Contributor, organizationId2 to Role.Manager))
  }

  private fun configureUser(roles: Map<OrganizationId, Role>): UserId {
    val userId = insertUser()
    roles.forEach { (orgId, role) -> insertOrganizationUser(userId, orgId, role) }
    return userId
  }
}
