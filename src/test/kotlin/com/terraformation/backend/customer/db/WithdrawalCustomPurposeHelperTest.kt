package com.terraformation.backend.customer.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.api.DuplicateNameException
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.customer.model.WithdrawalCustomPurposeModel
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.WithdrawalCustomPurposeNotFoundException
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.WithdrawalCustomPurposeId
import com.terraformation.backend.db.default_schema.WithdrawalCustomPurposeType
import com.terraformation.backend.db.default_schema.tables.records.WithdrawalCustomPurposesRecord
import com.terraformation.backend.db.default_schema.tables.references.WITHDRAWAL_CUSTOM_PURPOSES
import com.terraformation.backend.db.nursery.tables.references.WITHDRAWAL_SUMMARIES
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows

class WithdrawalCustomPurposeHelperTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser
  private val helper by lazy {
    WithdrawalCustomPurposeHelper(clock, dslContext, ParentFetcher(dslContext))
  }

  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertOrganizationUser(role = Role.Admin)
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

  @Nested
  inner class FetchByOrganizationId {
    @Test
    fun `lists purposes by name with optional archived purposes and excludes other organizations`() {
      val organizationId = inserted.organizationId
      assertEquals(
          emptyList<WithdrawalCustomPurposeModel>(),
          helper.fetchByOrganizationId(organizationId, WithdrawalCustomPurposeType.Nursery),
      )
      val zebraId = insertWithdrawalCustomPurpose(name = "Zebra")
      val archivedId = insertWithdrawalCustomPurpose(isArchived = true, name = "Middle")
      val alphaId = insertWithdrawalCustomPurpose(name = "Alpha")
      insertWithdrawalCustomPurpose(
          name = "Seed Bank only",
          type = WithdrawalCustomPurposeType.SeedBank,
      )
      insertOrganization()
      insertWithdrawalCustomPurpose()

      assertEquals(
          listOf(
              WithdrawalCustomPurposeModel(alphaId, false, "Alpha"),
              WithdrawalCustomPurposeModel(zebraId, false, "Zebra"),
          ),
          helper.fetchByOrganizationId(organizationId, WithdrawalCustomPurposeType.Nursery),
      )
      assertEquals(
          listOf(
              WithdrawalCustomPurposeModel(alphaId, false, "Alpha"),
              WithdrawalCustomPurposeModel(archivedId, true, "Middle"),
              WithdrawalCustomPurposeModel(zebraId, false, "Zebra"),
          ),
          helper.fetchByOrganizationId(
              organizationId,
              WithdrawalCustomPurposeType.Nursery,
              includeArchived = true,
          ),
      )
    }
  }

  @Nested
  inner class Update {
    @Test
    fun `can rename archive and restore purposes while preserving past withdrawal links`() {
      val purposeId = insertWithdrawalCustomPurpose()
      insertFacility()
      val withdrawalId = insertNurseryWithdrawal(customPurposeId = purposeId)
      clock.instant = Instant.ofEpochSecond(3600)
      helper.update(purposeId, name = "  Donation  ")

      val expected =
          WithdrawalCustomPurposesRecord(
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              id = purposeId,
              isArchived = false,
              modifiedBy = user.userId,
              modifiedTime = clock.instant(),
              name = "Donation",
              organizationId = inserted.organizationId,
              typeId = WithdrawalCustomPurposeType.Nursery,
          )
      assertTableEquals(expected)
      assertEquals(
          "Donation",
          dslContext
              .select(WITHDRAWAL_SUMMARIES.CUSTOM_PURPOSE)
              .from(WITHDRAWAL_SUMMARIES)
              .where(WITHDRAWAL_SUMMARIES.ID.eq(withdrawalId))
              .fetchSingle(WITHDRAWAL_SUMMARIES.CUSTOM_PURPOSE),
          "Past withdrawal custom purpose",
      )

      clock.instant = Instant.ofEpochSecond(7200)
      helper.update(purposeId, isArchived = true, name = "DONATION")
      assertTableEquals(
          expected.apply {
            isArchived = true
            modifiedTime = clock.instant()
            name = "DONATION"
          }
      )
      assertEquals(
          emptyList<WithdrawalCustomPurposeModel>(),
          helper.fetchByOrganizationId(
              inserted.organizationId,
              WithdrawalCustomPurposeType.Nursery,
          ),
      )

      clock.instant = Instant.ofEpochSecond(10800)
      helper.update(purposeId, isArchived = false)
      assertTableEquals(
          expected.apply {
            isArchived = false
            modifiedTime = clock.instant()
          }
      )
      assertEquals(
          listOf(WithdrawalCustomPurposeModel(purposeId, false, "DONATION")),
          helper.fetchByOrganizationId(
              inserted.organizationId,
              WithdrawalCustomPurposeType.Nursery,
          ),
      )
    }

    @Test
    fun `cannot rename to another active or archived purpose`() {
      val purposeId = insertWithdrawalCustomPurpose(name = "Donation")
      insertWithdrawalCustomPurpose(name = "Sále")
      insertWithdrawalCustomPurpose(isArchived = true, name = "Gift")
      val original = dslContext.selectFrom(WITHDRAWAL_CUSTOM_PURPOSES).fetch()

      assertAll(
          {
            val error =
                assertThrows<DuplicateNameException> { helper.update(purposeId, name = " sale ") }
            assertEquals(409, error.response.status, "Active name conflict HTTP status")
          },
          {
            val error =
                assertThrows<DuplicateNameException> { helper.update(purposeId, name = "gift") }
            assertEquals(409, error.response.status, "Archived name conflict HTTP status")
          },
      )
      assertTableEquals(original)

      insertWithdrawalCustomPurpose(
          name = "Seed Bank only",
          type = WithdrawalCustomPurposeType.SeedBank,
      )
      val expected = dslContext.selectFrom(WITHDRAWAL_CUSTOM_PURPOSES).fetch()
      helper.update(purposeId, name = "Seed Bank only")
      expected.single { it.id == purposeId }.name = "Seed Bank only"
      assertTableEquals(expected)
    }

    @Test
    fun `rejects blank or overly long names`() {
      val purposeId = insertWithdrawalCustomPurpose()
      val original = dslContext.selectFrom(WITHDRAWAL_CUSTOM_PURPOSES).fetchSingle()
      assertAll(
          {
            assertThrows<IllegalArgumentException>("Blank name") {
              helper.update(purposeId, name = "  ")
            }
          },
          {
            assertThrows<IllegalArgumentException>("Name length") {
              helper.update(purposeId, name = "x".repeat(51))
            }
          },
      )
      assertTableEquals(original)
    }

    @Test
    fun `throws not found for missing or inaccessible purpose IDs`() {
      assertThrows<WithdrawalCustomPurposeNotFoundException> {
        helper.update(WithdrawalCustomPurposeId(-1), name = "Gift")
      }

      insertOrganization()
      val otherOrganizationPurposeId = insertWithdrawalCustomPurpose()
      assertThrows<WithdrawalCustomPurposeNotFoundException> {
        helper.update(otherOrganizationPurposeId, name = "Gift")
      }
    }
  }
}
