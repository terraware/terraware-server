package com.terraformation.backend.customer.model

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.mockUser
import io.mockk.every
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SimpleUserModelTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val systemUser: SystemUser by lazy { SystemUser(usersDao) }
  private val messages = Messages()

  @BeforeEach
  fun setup() {
    every { user.canReadUser(any()) } returns false
  }

  @Nested
  inner class Create {
    @Test
    fun `uses message for system user`() {
      assertEquals(
          SimpleUserModel(systemUser.userId, "Terraformation Team"),
          SimpleUserModel.create(
              userId = systemUser.userId,
              fullName = "Terraware System",
              email = SystemUser.USERNAME,
              userIsInSameOrg = false,
              userIsDeleted = false,
              messages = messages,
          ),
          "Should convert system user names",
      )
    }

    @Test
    fun `uses message if user not in org`() {
      val userId = UserId(1000)

      assertEquals(
          SimpleUserModel(userId, "Terraformation Team"),
          SimpleUserModel.create(
              userId = userId,
              fullName = "Some Name",
              email = "test@terraformation.com",
              userIsInSameOrg = false,
              userIsDeleted = false,
              messages = messages,
          ),
          "Should convert users not in org",
      )
    }

    @Test
    fun `uses message if user not in org even if deleted`() {
      val userId = UserId(1000)

      assertEquals(
          SimpleUserModel(userId, "Terraformation Team"),
          SimpleUserModel.create(
              userId = userId,
              fullName = "Some Name",
              email = "test@terraformation.com",
              userIsInSameOrg = false,
              userIsDeleted = true,
              messages = messages,
          ),
          "Should convert tf users not in org that have been deleted",
      )
    }

    @Test
    fun `uses message if user has been deleted`() {
      val userId = UserId(1000)

      assertEquals(
          SimpleUserModel(userId, "Former User"),
          SimpleUserModel.create(
              userId = userId,
              fullName = "Some Name",
              email = "test@other.com",
              userIsInSameOrg = true,
              userIsDeleted = true,
              messages = messages,
          ),
          "Should convert users not in org",
      )
    }

    @Test
    fun `keeps name if user is in same org`() {
      val userId = UserId(1001)
      assertEquals(
          SimpleUserModel(userId, "First Last"),
          SimpleUserModel.create(
              userId = userId,
              fullName = "First Last",
              email = "test@terraformation.com",
              userIsInSameOrg = true,
              userIsDeleted = false,
              messages = messages,
          ),
          "Should not convert users in same org",
      )
    }

    @Test
    fun `keeps name if current user can read the other user`() {
      every { user.canReadUser(any()) } returns true
      val userId = UserId(1002)

      assertEquals(
          SimpleUserModel(userId, "First2 Last"),
          SimpleUserModel.create(
              userId = userId,
              fullName = "First2 Last",
              email = "test@terraformation.com",
              userIsInSameOrg = false,
              userIsDeleted = false,
              messages = messages,
          ),
          "Should not convert users when readable",
      )
    }

    @Test
    fun `throws exception if current user cannot read the other user`() {
      val userId = UserId(1003)

      assertEquals(
          SimpleUserModel(userId, ""),
          SimpleUserModel.create(
              userId = userId,
              fullName = "First3 Last",
              email = "test@other.com",
              userIsInSameOrg = false,
              userIsDeleted = false,
              messages = messages,
          ),
          "Should have empty name for unreadable user",
      )
    }
  }
}
