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
import com.terraformation.backend.db.default_schema.tables.references.USER_EMAIL_NOTIFICATION_CATEGORIES
import jakarta.inject.Named
import org.jooq.DSLContext
import org.jooq.impl.DSL

/** Manages which categories of email notifications users have opted into. */
@Named
class EmailNotificationSettingsStore(private val dslContext: DSLContext) {
  fun fetchForCurrentUser(): EmailNotificationSettingsModel {
    val user = currentUser()
    val optedInCategories = fetchOptedInCategories(user.userId)

    return EmailNotificationSettingsModel(
        fetchApplicableCategories(user).associateWith { it in optedInCategories }
    )
  }

  fun updateForCurrentUser(categories: Map<EmailNotificationCategory, Boolean>) {
    val userId = currentUser().userId
    val categoriesToOptIn = categories.filterValues { it }.keys
    val categoriesToOptOut = categories.filterValues { !it }.keys

    dslContext.transaction { _ ->
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
