package com.terraformation.backend.customer.model

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.mockUser
import io.mockk.every
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SimpleUserModelTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val systemUser: SystemUser by lazy { SystemUser(usersDao) }

  @BeforeEach
  fun setup() {
    every { user.canReadUser(any()) } returns false
  }

  @Nested
  inner class Create {
    @Test
    fun `converts system user`() {
      assertEquals(
          SimpleUserModel(systemUser.userId, "Terraformation", "Team"),
          SimpleUserModel.create(
              systemUser.userId,
              "Terraware",
              "System",
              SystemUser.USERNAME,
              false,
          ),
          "Should convert system user names",
      )
    }

    @Test
    fun `converts if user not in org`() {
      val userId = UserId(1000)

      assertEquals(
          SimpleUserModel(userId, "Terraformation", "Team"),
          SimpleUserModel.create(
              userId,
              "Some",
              "Name",
              "test@terraformation.com",
              false,
          ),
          "Should convert users not in org",
      )
    }

    @Test
    fun `does not convert if user is in same org`() {
      val userId = UserId(1001)
      assertEquals(
          SimpleUserModel(userId, "First", "Last"),
          SimpleUserModel.create(
              userId,
              "First",
              "Last",
              "test@terraformation.com",
              true,
          ),
          "Should not convert users in same org",
      )
    }

    @Test
    fun `does not convert if current user can read the other user`() {
      every { user.canReadUser(any()) } returns true
      val userId = UserId(1002)

      assertEquals(
          SimpleUserModel(userId, "First2", "Last"),
          SimpleUserModel.create(
              userId,
              "First2",
              "Last",
              "test@terraformation.com",
              false,
          ),
          "Should not convert users when readable",
      )
    }

    @Test
    fun `throws exception if current user cannot read the other user`() {
      val userId = UserId(1003)

      assertThrows<IllegalArgumentException> {
        SimpleUserModel.create(userId, "First3", "Last", "test@other.com", false)
      }
    }
  }
}
