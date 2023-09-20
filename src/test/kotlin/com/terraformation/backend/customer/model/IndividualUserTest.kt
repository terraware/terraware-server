package com.terraformation.backend.customer.model

import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.UserType
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import java.time.ZoneId
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class IndividualUserTest {
  private val organizationId = OrganizationId(1)
  private val userId = UserId(1)

  private lateinit var parentStore: ParentStore
  private lateinit var user: IndividualUser

  @BeforeEach
  fun setup() {
    parentStore = mockk()
    user =
        spyk(
            IndividualUser(
                userId = UserId(1),
                authId = "authId",
                email = "user@terraformation.com",
                emailNotificationsEnabled = false,
                firstName = "first",
                lastName = "last",
                countryCode = "US",
                locale = Locale.of("en"),
                timeZone = ZoneId.of("America/Los_Angeles"),
                userType = UserType.Individual,
                parentStore = parentStore,
                permissionStore = mockk()))

    every { user.organizationRoles } returns mapOf(organizationId to Role.Owner)
    every { parentStore.getUserRole(any(), any()) } returns Role.Contributor
  }

  @Test
  fun `can set assignable roles`() {
    assertTrue(user.canSetOrganizationUserRole(organizationId, Role.Admin))
  }

  @Test
  fun `cannot set unassignable roles`() {
    assertFalse(user.canSetOrganizationUserRole(organizationId, Role.TerraformationContact))
  }

  @Test
  fun `can remove organization user that is not a Terraformation Contact`() {
    assertTrue(user.canRemoveOrganizationUser(organizationId, userId))
  }

  @Test
  fun `cannot remove organization user that is a Terraformation Contact`() {
    every { parentStore.getUserRole(organizationId, userId) } returns Role.TerraformationContact
    assertFalse(user.canRemoveOrganizationUser(organizationId, userId))
  }

  @Test
  fun `can update organization user that is not a Terraformation Contact`() {
    assertTrue(user.canUpdateOrganizationUser(organizationId, userId))
  }

  @Test
  fun `cannot update organization user that is a Terraformation Contact`() {
    every { parentStore.getUserRole(organizationId, userId) } returns Role.TerraformationContact
    assertFalse(user.canUpdateOrganizationUser(organizationId, userId))
  }
}
