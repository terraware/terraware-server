package com.terraformation.backend

import com.terraformation.backend.auth.CurrentUserHolder
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.PermissionStore
import com.terraformation.backend.customer.model.DeviceManagerUser
import com.terraformation.backend.customer.model.IndividualUser
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseBackedTest
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.UserType
import com.terraformation.backend.db.default_schema.tables.daos.UsersDao
import com.terraformation.backend.db.default_schema.tables.references.USERS

/**
 * Indicates that a test should be run with the current user set to an instance of [TerrawareUser]
 * backed by the database rather than a test double.
 */
interface RunsAsDatabaseUser : RunsAsUser {
  // This overrides the "val" in RunsAsUser with a "var" so that the setup method in DatabaseTest
  // can populate it after inserting the user into the database.
  override var user: TerrawareUser

  fun runTestAs(userId: UserId) {
    if (this is DatabaseBackedTest) {
      val record = dslContext.selectFrom(USERS).where(USERS.ID.eq(userId)).fetchSingle()

      user =
          when (record.userTypeId!!) {
            UserType.DeviceManager ->
                DeviceManagerUser(
                    record.id!!,
                    record.authId!!,
                    ParentStore(dslContext),
                    PermissionStore(dslContext))
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
                    PermissionStore(dslContext))
            UserType.System -> SystemUser(UsersDao(dslContext.configuration()))
          }

      CurrentUserHolder.setCurrentUser(user)
    } else {
      throw UnsupportedOperationException("Cannot run non-database test as a database user")
    }
  }
}
