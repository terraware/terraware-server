package com.terraformation.backend.customer

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.auth.InMemoryKeycloakAdminClient
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.PermissionStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.event.AcceleratorAdminInvitedEvent
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.tables.records.UserGlobalRolesRecord
import com.terraformation.backend.dummyKeycloakInfo
import com.terraformation.backend.mockUser
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.access.AccessDeniedException

internal class UserServiceTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  @Autowired private lateinit var config: TerrawareServerConfig

  private val clock = TestClock()
  private val publisher = TestEventPublisher()
  private val userStore: UserStore by lazy {
    UserStore(
        clock,
        config,
        dslContext,
        mockk(),
        InMemoryKeycloakAdminClient(),
        dummyKeycloakInfo(),
        OrganizationStore(clock, dslContext, organizationsDao, publisher),
        ParentStore(dslContext),
        PermissionStore(dslContext),
        publisher,
        usersDao,
    )
  }
  private val service: UserService by lazy { UserService(dslContext, publisher, userStore) }

  @BeforeEach
  fun setUp() {
    every { user.canUpdateSpecificGlobalRoles(any()) } returns true
  }

  @Nested
  inner class InviteAdmin {
    @Test
    fun `creates a new user, grants roles, and publishes the event`() {
      val email = "newadmin@terraformation.com"

      val result = service.inviteAdmin(email, setOf(GlobalRole.TFExpert))

      assertEquals(email, result.email, "Returned user email")

      val storedUser = userStore.fetchByEmail(email)
      assertNotNull(storedUser, "users row was created")

      assertTableEquals(UserGlobalRolesRecord(storedUser!!.userId, GlobalRole.TFExpert))

      publisher.assertEventPublished(
          AcceleratorAdminInvitedEvent(
              email = email,
              invitedBy = user.userId,
              userId = storedUser.userId,
          )
      )
    }

    @Test
    fun `replaces roles and does not publish event when user already exists`() {
      val email = "existing@terraformation.com"
      val existingUserId = insertUser(email = email)
      insertUserGlobalRole(userId = existingUserId, role = GlobalRole.AcceleratorAdmin)

      val result = service.inviteAdmin(email, setOf(GlobalRole.TFExpert))

      assertEquals(existingUserId, result.userId, "Should not create a new user")

      assertTableEquals(UserGlobalRolesRecord(existingUserId, GlobalRole.TFExpert))

      publisher.assertEventNotPublished<AcceleratorAdminInvitedEvent>()
    }

    @Test
    fun `throws exception when global roles set is empty`() {
      assertThrows<IllegalArgumentException> {
        service.inviteAdmin("admin@terraformation.com", emptySet())
      }

      assertNull(
          userStore.fetchByEmail("admin@terraformation.com"),
          "Should not create a user",
      )
      publisher.assertNoEventsPublished()
    }

    @Test
    fun `throws AccessDeniedException without permission`() {
      every { user.canUpdateSpecificGlobalRoles(any()) } returns false

      assertThrows<AccessDeniedException> {
        service.inviteAdmin(
            "admin@terraformation.com",
            setOf(GlobalRole.TFExpert),
        )
      }

      assertNull(
          userStore.fetchByEmail("admin@terraformation.com"),
          "Should not create a user",
      )
      publisher.assertNoEventsPublished()
    }

    @Test
    fun `throws when email is not a TF address`() {
      assertThrows<IllegalArgumentException> {
        service.inviteAdmin(
            "outsider@example.com",
            setOf(GlobalRole.TFExpert),
        )
      }

      assertNull(
          userStore.fetchByEmail("outsider@example.com"),
          "Should not create a user",
      )
      publisher.assertNoEventsPublished()
    }
  }
}
