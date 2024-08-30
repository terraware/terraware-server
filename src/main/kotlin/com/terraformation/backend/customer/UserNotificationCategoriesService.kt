package com.terraformation.backend.customer

import com.terraformation.backend.accelerator.db.ApplicationRecipientsStore
import com.terraformation.backend.accelerator.db.UserDeliverableCategoriesStore
import com.terraformation.backend.customer.model.UserNotificationCategory
import com.terraformation.backend.db.default_schema.UserId
import jakarta.inject.Named
import org.jooq.Condition

@Named
class UserNotificationCategoriesService(
    private val applicationRecipientsStore: ApplicationRecipientsStore,
    private val userDeliverableCategoriesStore: UserDeliverableCategoriesStore,
) {
  fun get(userId: UserId): Set<UserNotificationCategory> {
    val categories =
        userDeliverableCategoriesStore
            .fetchForUser(userId)
            .map { UserNotificationCategory.of(it) }
            .toMutableSet()

    if (applicationRecipientsStore.contains(userId)) {
      categories.add(UserNotificationCategory.Sourcing)
    }

    return categories
  }

  fun update(userId: UserId, categories: Set<UserNotificationCategory>) {
    val deliverableCategories = categories.mapNotNull { it.toDeliverableCategory() }.toSet()
    userDeliverableCategoriesStore.updateForUser(userId, deliverableCategories)

    if (categories.contains(UserNotificationCategory.Sourcing)) {
      applicationRecipientsStore.add(userId)
    } else {
      applicationRecipientsStore.remove(userId)
    }
  }

  fun conditionForUsers(category: UserNotificationCategory): Condition? {
    return if (category == UserNotificationCategory.Sourcing) {
      applicationRecipientsStore.conditionForUsers()
    } else {
      category.toDeliverableCategory()?.let { deliverableCategory ->
        userDeliverableCategoriesStore.conditionForUsers(deliverableCategory)
      }
    }
  }
}
