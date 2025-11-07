package com.terraformation.backend.customer.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.customer.model.SimpleUserModel
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.i18n.Messages
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SimpleUserStoreTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val store: SimpleUserStore by lazy { SimpleUserStore(dslContext, Messages()) }

  @Nested
  inner class FetchByIds {
    @Test
    fun `fetches multiple users`() {
      insertOrganization()
      insertOrganizationUser()

      val userInSameOrg = insertUser(email = "sameorg@x.com", firstName = "Same", lastName = "Org")
      insertOrganizationUser(userInSameOrg)
      val userNotInSameOrg =
          insertUser(email = "notsame@x.com", firstName = "Not", lastName = "Same")
      val tfUser =
          insertUser(email = "root@terraformation.com", firstName = "Super", lastName = "User")
      val deletedUser =
          insertUser(
              deletedTime = Instant.EPOCH,
              email = "deleted@x.com",
              firstName = "Mary",
              lastName = "Contrary",
          )
      val nonexistentUser = UserId(-1)

      val expected =
          mapOf(
              userInSameOrg to SimpleUserModel(userInSameOrg, "Same Org"),
              userNotInSameOrg to SimpleUserModel(userNotInSameOrg, "Terraware User"),
              tfUser to SimpleUserModel(tfUser, "Terraformation Team"),
              deletedUser to SimpleUserModel(deletedUser, "Former User"),
              nonexistentUser to SimpleUserModel(nonexistentUser, "Former User"),
          )

      val actual =
          store.fetchSimpleUsersById(
              listOf(userInSameOrg, userNotInSameOrg, tfUser, deletedUser, nonexistentUser)
          )

      assertEquals(expected, actual)
    }

    @Test
    fun `system user can read full name`() {
      val userId = insertUser(email = "random@x.com", firstName = "Random", lastName = "Person")

      val expected = mapOf(userId to SimpleUserModel(userId, "Random Person"))
      val actual = SystemUser(usersDao).run { store.fetchSimpleUsersById(listOf(userId)) }

      assertEquals(expected, actual)
    }
  }
}
