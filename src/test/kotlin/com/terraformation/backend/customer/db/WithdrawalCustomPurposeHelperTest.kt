package com.terraformation.backend.customer.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.WithdrawalCustomPurposeType
import com.terraformation.backend.db.default_schema.tables.records.WithdrawalCustomPurposesRecord
import com.terraformation.backend.db.default_schema.tables.references.WITHDRAWAL_CUSTOM_PURPOSES
import com.terraformation.backend.mockUser
import io.mockk.every
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class WithdrawalCustomPurposeHelperTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()
  private val helper by lazy { WithdrawalCustomPurposeHelper(clock, dslContext) }

  @BeforeEach
  fun setUp() {
    insertOrganization()
    every { user.canCreateWithdrawalCustomPurpose(any()) } returns true
  }

  @Nested
  inner class FindOrCreate {
    @Test
    fun `creates trims and reuses purposes including archived matches`() {
      val organizationId = inserted.organizationId
      val otherTypeExpected =
          WithdrawalCustomPurposesRecord(
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              id =
                  insertWithdrawalCustomPurpose(
                      createdBy = user.userId,
                      name = "Sále",
                      type = WithdrawalCustomPurposeType.SeedBank,
                  ),
              isArchived = false,
              modifiedBy = user.userId,
              modifiedTime = Instant.EPOCH,
              name = "Sále",
              organizationId = organizationId,
              typeId = WithdrawalCustomPurposeType.SeedBank,
          )
      val otherOrganizationId = insertOrganization()
      val otherOrganizationExpected =
          otherTypeExpected.copy().apply {
            id = insertWithdrawalCustomPurpose(createdBy = user.userId, name = "Sále")
            this.organizationId = otherOrganizationId
            typeId = WithdrawalCustomPurposeType.Nursery
          }

      val purposeId =
          helper.findOrCreate(organizationId, WithdrawalCustomPurposeType.Nursery, "  Sále  ")
      val expected =
          WithdrawalCustomPurposesRecord(
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              id = purposeId,
              isArchived = false,
              modifiedBy = user.userId,
              modifiedTime = Instant.EPOCH,
              name = "Sále",
              organizationId = organizationId,
              typeId = WithdrawalCustomPurposeType.Nursery,
          )
      assertTableEquals(listOf(otherTypeExpected, otherOrganizationExpected, expected))

      clock.instant = Instant.EPOCH.plusSeconds(1)
      assertEquals(
          purposeId,
          helper.findOrCreate(organizationId, WithdrawalCustomPurposeType.Nursery, "sale"),
          "Reused purpose ID",
      )
      assertTableEquals(listOf(otherTypeExpected, otherOrganizationExpected, expected))

      dslContext
          .update(WITHDRAWAL_CUSTOM_PURPOSES)
          .set(WITHDRAWAL_CUSTOM_PURPOSES.IS_ARCHIVED, true)
          .where(WITHDRAWAL_CUSTOM_PURPOSES.ID.eq(purposeId))
          .execute()
      clock.instant = Instant.EPOCH.plusSeconds(2)
      assertEquals(
          purposeId,
          helper.findOrCreate(organizationId, WithdrawalCustomPurposeType.Nursery, "SALE"),
          "Restored purpose ID",
      )
      expected.modifiedTime = clock.instant()
      assertTableEquals(listOf(otherTypeExpected, otherOrganizationExpected, expected))
    }
  }
}
