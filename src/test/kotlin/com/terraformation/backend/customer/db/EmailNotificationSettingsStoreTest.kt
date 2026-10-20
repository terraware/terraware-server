package com.terraformation.backend.customer.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.customer.model.EmailNotificationSettingsModel
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.AcceleratorPhase
import com.terraformation.backend.db.default_schema.EmailNotificationCategory
import com.terraformation.backend.db.default_schema.UserType
import com.terraformation.backend.db.default_schema.tables.records.UserEmailNotificationCategoriesRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class EmailNotificationSettingsStoreTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val store: EmailNotificationSettingsStore by lazy {
    EmailNotificationSettingsStore(dslContext)
  }

  @Nested
  inner class FetchForCurrentUser {
    @Test
    fun `returns opt-in status of non-accelerator categories for non-accelerator users`() {
      insertOrganization()
      insertOrganizationUser()
      insertProject()
      insertUserEmailNotificationCategory(EmailNotificationCategory.Nursery)
      insertUserEmailNotificationCategory(EmailNotificationCategory.Accelerator)

      assertEquals(
          EmailNotificationSettingsModel(
              mapOf(
                  EmailNotificationCategory.SeedBank to false,
                  EmailNotificationCategory.Nursery to true,
                  EmailNotificationCategory.PlantingAndObservations to false,
              )
          ),
          store.fetchForCurrentUser(),
      )
    }

    @Test
    fun `includes accelerator category for members of organizations with accelerator projects`() {
      insertOrganization()
      insertOrganizationUser()
      insertProject(phase = AcceleratorPhase.Phase1FeasibilityStudy)
      insertUserEmailNotificationCategory(EmailNotificationCategory.Accelerator)

      assertEquals(
          EmailNotificationSettingsModel(
              mapOf(
                  EmailNotificationCategory.SeedBank to false,
                  EmailNotificationCategory.Nursery to false,
                  EmailNotificationCategory.PlantingAndObservations to false,
                  EmailNotificationCategory.Accelerator to true,
              )
          ),
          store.fetchForCurrentUser(),
      )
    }

    @Test
    fun `includes accelerator category for members of organizations with application projects`() {
      insertOrganization()
      insertOrganizationUser()
      insertProject()
      insertApplication()

      assertEquals(
          setOf(
              EmailNotificationCategory.SeedBank,
              EmailNotificationCategory.Nursery,
              EmailNotificationCategory.PlantingAndObservations,
              EmailNotificationCategory.Accelerator,
          ),
          store.fetchForCurrentUser().categories.keys,
      )
    }

    @Test
    fun `does not include accelerator category if user is not a member of accelerator organization`() {
      insertOrganization()
      insertProject(phase = AcceleratorPhase.Phase1FeasibilityStudy)

      assertEquals(
          setOf(
              EmailNotificationCategory.SeedBank,
              EmailNotificationCategory.Nursery,
              EmailNotificationCategory.PlantingAndObservations,
          ),
          store.fetchForCurrentUser().categories.keys,
      )
    }

    @Test
    fun `only returns accelerator category for funders`() {
      switchToUser(insertUser(type = UserType.Funder))
      insertUserEmailNotificationCategory(EmailNotificationCategory.Accelerator)
      insertUserEmailNotificationCategory(EmailNotificationCategory.SeedBank)

      assertEquals(
          EmailNotificationSettingsModel(mapOf(EmailNotificationCategory.Accelerator to true)),
          store.fetchForCurrentUser(),
      )
    }
  }

  @Nested
  inner class UpdateForCurrentUser {
    @Test
    fun `opts into and out of specified categories and leaves others as-is`() {
      val otherUserId = insertUser()
      insertUserEmailNotificationCategory(EmailNotificationCategory.SeedBank, otherUserId)

      insertUserEmailNotificationCategory(EmailNotificationCategory.SeedBank, user.userId)
      insertUserEmailNotificationCategory(EmailNotificationCategory.Nursery, user.userId)

      store.updateForCurrentUser(
          mapOf(
              EmailNotificationCategory.SeedBank to false,
              EmailNotificationCategory.Nursery to true,
              EmailNotificationCategory.PlantingAndObservations to true,
          )
      )

      assertTableEquals(
          listOf(
              UserEmailNotificationCategoriesRecord(
                  otherUserId,
                  EmailNotificationCategory.SeedBank,
              ),
              UserEmailNotificationCategoriesRecord(user.userId, EmailNotificationCategory.Nursery),
              UserEmailNotificationCategoriesRecord(
                  user.userId,
                  EmailNotificationCategory.PlantingAndObservations,
              ),
          )
      )
    }
  }

  @Nested
  inner class OptIntoAllCategoriesIfNoneForCurrentUser {
    @Test
    fun `opts into all categories if user has no opt-ins`() {
      val otherUserId = insertUser()
      insertUserEmailNotificationCategory(EmailNotificationCategory.SeedBank, otherUserId)

      store.optIntoAllCategoriesIfNoneForCurrentUser()

      assertTableEquals(
          listOf(
              UserEmailNotificationCategoriesRecord(otherUserId, EmailNotificationCategory.SeedBank)
          ) +
              EmailNotificationCategory.entries.map {
                UserEmailNotificationCategoriesRecord(user.userId, it)
              }
      )
    }

    @Test
    fun `does not modify existing opt-ins`() {
      insertUserEmailNotificationCategory(EmailNotificationCategory.Nursery)

      store.optIntoAllCategoriesIfNoneForCurrentUser()

      assertTableEquals(
          UserEmailNotificationCategoriesRecord(user.userId, EmailNotificationCategory.Nursery)
      )
    }
  }
}
