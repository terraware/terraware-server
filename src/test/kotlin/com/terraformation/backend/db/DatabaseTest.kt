package com.terraformation.backend.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.db.default_schema.UserType
import io.mockk.every
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.jooq.test.autoconfigure.AutoConfigureJooq
import org.springframework.boot.test.autoconfigure.OverrideAutoConfiguration
import org.springframework.boot.test.context.SpringBootTestContextBootstrapper
import org.springframework.boot.test.context.filter.annotation.TypeExcludeFilters
import org.springframework.test.context.BootstrapWith
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.transaction.annotation.Transactional

/** Superclass for tests that require database access but not the entire set of Spring beans. */
@BootstrapWith(SpringBootTestContextBootstrapper::class)
@ExtendWith(SpringExtension::class)
@OverrideAutoConfiguration(enabled = false)
@TypeExcludeFilters(DatabaseTestTypeExcludeFilter::class)
@Transactional
@AutoConfigureJooq
@ImportAutoConfiguration
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
