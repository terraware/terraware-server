package com.terraformation.backend

import com.terraformation.backend.auth.CurrentUserHolder
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.PermissionStore
import com.terraformation.backend.customer.model.DeviceManagerUser
import com.terraformation.backend.customer.model.FunderUser
import com.terraformation.backend.customer.model.IndividualUser
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseBackedTest
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.UserType
import com.terraformation.backend.db.default_schema.tables.daos.UsersDao
import com.terraformation.backend.db.default_schema.tables.references.USERS
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.fail

/**
 * Indicates that a test should be run with the current user set to an instance of [TerrawareUser]
 * backed by the database rather than a test double.
 *
 * If the test class is a subclass of [DatabaseTest], a user will be inserted automatically and used
 * by default. Tests may insert additional users and switch to them by calling [switchToUser].
 */
interface RunsAsDatabaseUser : RunsAsUser {
  /**
   * User to masquerade as while running tests. You'll almost always want to implement this as
   *
   *     override lateinit var user: TerrawareUser
   *
   * This overrides the `val` version of the property in [RunsAsUser] with a `var` version so that
   * it can be populated after the user is inserted into the database.
   */
  override var user: TerrawareUser

  /**
   * Reads a user from the database and uses it as the currently-active user. Remains in effect
   * until the end of the test method or until this method is called again.
   */
  fun switchToUser(userId: UserId) {
    if (this is DatabaseBackedTest) {
      val record = dslContext.selectFrom(USERS).where(USERS.ID.eq(userId)).fetchSingle()

      user =
          when (record.userTypeId!!) {
            UserType.DeviceManager ->
                DeviceManagerUser(
                    record.id!!,
                    record.authId!!,
                    ParentStore(dslContext),
                    PermissionStore(dslContext),
                )
            UserType.Individual ->
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
            UserType.System -> SystemUser(UsersDao(dslContext.configuration()))
            UserType.Funder ->
                FunderUser(
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
                    ParentStore(dslContext),
                    PermissionStore(dslContext),
                )
          }

      CurrentUserHolder.setCurrentUser(user)
    } else {
      throw UnsupportedOperationException("Cannot run non-database test as a database user")
    }
  }

  @AfterEach
  fun verifyNoPermissionInversions() {
    val testUser = user
    if (testUser is IndividualUser) {
      val permissionChecks = testUser.permissionChecks

      permissionChecks.forEachIndexed { earlierIndex, earlierCheck ->
        permissionChecks.drop(earlierIndex + 1).forEach { laterCheck ->
          if (laterCheck.isGuardedBy(earlierCheck) && laterCheck.isStricterThan(earlierCheck)) {
            fail(
                "$laterCheck guarded by $earlierCheck" +
                    "\nEarlier:" +
                    "\n${earlierCheck.prettyPrintStack()}" +
                    "\nLater:" +
                    "\n${laterCheck.prettyPrintStack()}"
            )
          }
        }
      }

      // Allow tests to explicitly call this method if they are doing multiple permission-sensitive
      // operations in a row where each operation should individually be checked for inversions.
      permissionChecks.clear()
    }
  }
}
