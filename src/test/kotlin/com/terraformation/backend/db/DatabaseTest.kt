package com.terraformation.backend.db

import com.terraformation.backend.RunsAsIndividualUser
import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.PermissionStore
import com.terraformation.backend.customer.model.IndividualUser
import com.terraformation.backend.db.default_schema.tables.references.USERS
import io.mockk.every
import org.junit.jupiter.api.BeforeEach
import org.springframework.boot.test.autoconfigure.jooq.JooqTest

/** Superclass for tests that require database access but not the entire set of Spring beans. */
@JooqTest
abstract class DatabaseTest : DatabaseBackedTest() {
  @BeforeEach
  fun insertMockUser() {
    if (this is RunsAsUser) {
      val userId = insertUser()

      if (this is RunsAsIndividualUser) {
        val record = dslContext.selectFrom(USERS).where(USERS.ID.eq(userId)).fetchSingle()
        user =
            IndividualUser(
                record.createdTime!!,
                record.id!!,
                record.authId,
                record.email!!,
                record.emailNotificationsEnabled!!,
                record.firstName,
                record.lastName,
                record.countryCode,
                record.cookiesConsented,
                record.cookiesConsentedTime,
                record.locale,
                record.timeZone,
                record.userTypeId!!,
                ParentStore(dslContext),
                PermissionStore(dslContext),
            )
      } else {
        every { user.userId } returns userId
      }
    }
  }
}
