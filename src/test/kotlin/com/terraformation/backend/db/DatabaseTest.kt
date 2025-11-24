package com.terraformation.backend.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.db.default_schema.UserType
import io.mockk.every
import org.junit.jupiter.api.BeforeEach
import org.springframework.boot.jooq.test.autoconfigure.JooqTest

/** Superclass for tests that require database access but not the entire set of Spring beans. */
@JooqTest
abstract class DatabaseTest : DatabaseBackedTest() {
  val defaultUserType: UserType
    get() = UserType.Individual

  @BeforeEach
  fun insertDefaultUser() {
    if (this is RunsAsUser) {
      val userId = insertUser(type = defaultUserType, cookiesConsented = true)

      if (this is RunsAsDatabaseUser) {
        switchToUser(userId)
      } else {
        every { user.userId } returns userId
      }
    }
  }
}
