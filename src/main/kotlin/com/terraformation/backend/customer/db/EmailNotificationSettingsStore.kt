package com.terraformation.backend.customer.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.EmailNotificationSettingsModel
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.accelerator.tables.references.APPLICATIONS
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.EmailNotificationCategory
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.UserType
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATION_USERS
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.default_schema.tables.references.USERS
import com.terraformation.backend.db.default_schema.tables.references.USER_EMAIL_NOTIFICATION_CATEGORIES
import jakarta.inject.Named
import org.jooq.DSLContext
import org.jooq.impl.DSL

/** Manages users' email notification settings. */
@Named
class EmailNotificationSettingsStore(private val dslContext: DSLContext) {
  fun fetchForCurrentUser(): EmailNotificationSettingsModel {
    val user = currentUser()
    val optedInCategories = fetchOptedInCategories(user.userId)

    val emailNotificationsEnabled =
        dslContext
            .select(USERS.EMAIL_NOTIFICATIONS_ENABLED.asNonNullable())
            .from(USERS)
            .where(USERS.ID.eq(user.userId))
            .fetchSingle()
            .value1()

    return EmailNotificationSettingsModel(
        categories = fetchApplicableCategories(user).associateWith { it in optedInCategories },
        emailNotificationsEnabled = emailNotificationsEnabled,
    )
  }

  fun updateForCurrentUser(
      categories: Map<EmailNotificationCategory, Boolean>,
      emailNotificationsEnabled: Boolean? = null,
  ) {
    val userId = currentUser().userId
    val categoriesToOptIn = categories.filterValues { it }.keys
    val categoriesToOptOut = categories.filterValues { !it }.keys

    dslContext.transaction { _ ->
      if (emailNotificationsEnabled != null) {
        dslContext
            .update(USERS)
            .set(USERS.EMAIL_NOTIFICATIONS_ENABLED, emailNotificationsEnabled)
            .where(USERS.ID.eq(userId))
            .execute()
      }

      with(USER_EMAIL_NOTIFICATION_CATEGORIES) {
        if (categoriesToOptOut.isNotEmpty()) {
          dslContext
              .deleteFrom(this)
              .where(USER_ID.eq(userId))
              .and(EMAIL_NOTIFICATION_CATEGORY_ID.`in`(categoriesToOptOut))
              .execute()
        }

        if (categoriesToOptIn.isNotEmpty()) {
          dslContext
              .insertInto(this, USER_ID, EMAIL_NOTIFICATION_CATEGORY_ID)
              .valuesOfRows(categoriesToOptIn.map { DSL.row(userId, it) })
              .onConflictDoNothing()
              .execute()
        }
      }
    }
  }

  /**
   * Returns true if a user has enabled email notifications and, if [category] is non-null, has
   * opted into that category. This doesn't check permissions; it's for use by code that decides who
   * should receive notifications.
   */
  fun isOptedIn(userId: UserId, category: EmailNotificationCategory?): Boolean {
    val categoryCondition =
        if (category != null) {
          DSL.exists(
              DSL.selectOne()
                  .from(USER_EMAIL_NOTIFICATION_CATEGORIES)
                  .where(USER_EMAIL_NOTIFICATION_CATEGORIES.USER_ID.eq(userId))
                  .and(
                      USER_EMAIL_NOTIFICATION_CATEGORIES.EMAIL_NOTIFICATION_CATEGORY_ID.eq(category)
                  )
          )
        } else {
          DSL.trueCondition()
        }

    return dslContext.fetchExists(
        USERS,
        USERS.ID.eq(userId).and(USERS.EMAIL_NOTIFICATIONS_ENABLED.isTrue).and(categoryCondition),
    )
  }

  private fun fetchOptedInCategories(userId: UserId): Set<EmailNotificationCategory> {
    return with(USER_EMAIL_NOTIFICATION_CATEGORIES) {
      dslContext
          .select(EMAIL_NOTIFICATION_CATEGORY_ID)
          .from(this)
          .where(USER_ID.eq(userId))
          .fetchSet(EMAIL_NOTIFICATION_CATEGORY_ID.asNonNullable())
    }
  }

  private fun fetchApplicableCategories(user: TerrawareUser): List<EmailNotificationCategory> {
    return when (user.userType) {
      UserType.Funder -> listOf(EmailNotificationCategory.Accelerator)
      UserType.Individual ->
          if (isMemberOfAcceleratorOrganization(user.userId)) {
            EmailNotificationCategory.entries
          } else {
            EmailNotificationCategory.entries - EmailNotificationCategory.Accelerator
          }
      else -> emptyList()
    }
  }

  private fun isMemberOfAcceleratorOrganization(userId: UserId): Boolean {
    return dslContext.fetchExists(
        DSL.selectOne()
            .from(ORGANIZATION_USERS)
            .join(PROJECTS)
            .on(ORGANIZATION_USERS.ORGANIZATION_ID.eq(PROJECTS.ORGANIZATION_ID))
            .leftJoin(APPLICATIONS)
            .on(PROJECTS.ID.eq(APPLICATIONS.PROJECT_ID))
            .where(ORGANIZATION_USERS.USER_ID.eq(userId))
            .and(PROJECTS.PHASE_ID.isNotNull.or(APPLICATIONS.ID.isNotNull))
    )
  }
}
