package com.terraformation.backend.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.RunsAsUser
import io.mockk.every
import org.junit.jupiter.api.BeforeEach
import org.springframework.boot.test.autoconfigure.jooq.JooqTest

/** Superclass for tests that require database access but not the entire set of Spring beans. */
@JooqTest
abstract class DatabaseTest : DatabaseBackedTest() {
  @BeforeEach
  fun insertDefaultUser() {
    if (this is RunsAsUser) {
      val userId = insertUser()

      if (this is RunsAsDatabaseUser) {
        switchToUser(userId)
      } else {
        every { user.userId } returns userId
      }
    }
  }
}
