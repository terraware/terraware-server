package com.terraformation.backend.funder.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.UserNotFoundException
import com.terraformation.backend.db.funder.FundingEntityId
import com.terraformation.backend.db.funder.tables.records.FundingEntityUsersRecord
import com.terraformation.backend.funder.model.FunderUserModel
import com.terraformation.backend.funder.model.FundingEntityModel
import com.terraformation.backend.funder.model.FundingProjectModel
import com.terraformation.backend.mockUser
import io.mockk.every
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class FundingEntityUserStoreTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val fundingEntityName = "Funding entity ${UUID.randomUUID()}"

  private lateinit var fundingEntityId: FundingEntityId

  private val store by lazy { FundingEntityUserStore(dslContext) }
  private val testUserId by lazy { insertUser() }

  @BeforeEach
  fun setUp() {
    fundingEntityId = insertFundingEntity(name = fundingEntityName)

    every { user.canReadUser(testUserId) } returns true
    every { user.canReadFundingEntity(any()) } returns true
    every { user.canListFundingEntityUsers(any()) } returns true
  }

  @Test
  fun `getFundingEntityId returns null if userId isn't attached to FundingEntity`() {
    assertNull(store.getFundingEntityId(testUserId))
  }

  @Test
  fun `getFundingEntityId throws exception if missing permissions`() {
    every { user.canReadUser(testUserId) } returns false
    assertThrows<UserNotFoundException> { store.getFundingEntityId(testUserId) }
  }

  @Test
  fun `getFundingEntityId returns correct funding entity id if userId attached to FundingEntity`() {
    insertFundingEntityUser(fundingEntityId, testUserId)

    assertEquals(fundingEntityId, store.getFundingEntityId(testUserId))
    assertTableEquals(
        FundingEntityUsersRecord(fundingEntityId = fundingEntityId, userId = testUserId)
    )
  }

  @Test
  fun `fetchEntityByUserId returns null if userId isn't attached to FundingEntity`() {
    assertNull(store.fetchEntityByUserId(testUserId))
  }

  @Test
  fun `fetchEntityByUserId throws exception if missing permissions`() {
    every { user.canReadUser(testUserId) } returns false
    assertThrows<UserNotFoundException> { store.fetchEntityByUserId(testUserId) }
  }

  @Test
  fun `fetchEntityByUserId returns correct funding entity if userId attached to FundingEntity`() {
    insertFundingEntityUser(fundingEntityId, testUserId)

    insertOrganization()
    val projectId1 = insertProject(name = "Project name 1")
    insertProjectAcceleratorDetails(dealName = "Deal name 1")
    insertFundingEntityProject()
    val projectId2 = insertProject(name = "Project name 2")
    insertFundingEntityProject()

    // Unrelated funding entity shouldn't be returned
    insertFundingEntity(name = UUID.randomUUID().toString())
    insertProject()
    insertFundingEntityProject()

    assertEquals(
        FundingEntityModel(
            id = fundingEntityId,
            name = fundingEntityName,
            createdTime = Instant.EPOCH,
            modifiedTime = Instant.EPOCH,
            projects =
                listOf(
                    FundingProjectModel(projectId1, "Deal name 1"),
                    FundingProjectModel(projectId2, "Project name 2"),
                ),
        ),
        store.fetchEntityByUserId(testUserId),
    )
  }

  @Test
  fun `fetchFundersForEntity throws exception if missing permissions`() {
    insertFundingEntityUser(fundingEntityId, testUserId)

    every { user.canListFundingEntityUsers(fundingEntityId) } returns false
    assertThrows<AccessDeniedException> { store.fetchFundersForEntity(fundingEntityId) }

    every { user.canReadFundingEntity(fundingEntityId) } returns false
    assertThrows<FundingEntityNotFoundException> { store.fetchFundersForEntity(fundingEntityId) }
  }

  @Test
  fun `fetchFundersForEntity returns list of non-deleted users belonging to the funder entity`() {
    val userId1 =
        insertUser(
            firstName = "Bruce",
            lastName = "Wayne",
            email = "batman@justice.league",
            createdTime = Instant.ofEpochSecond(6000),
            authId = "BATMAN",
        )
    val userId2 =
        insertUser(
            firstName = "Clark",
            lastName = "Kent",
            email = "superman@justice.league",
            createdTime = Instant.ofEpochSecond(3000),
            authId = null,
        )
    val userId3 =
        insertUser(
            firstName = null,
            lastName = null,
            email = "harleyquinn@justice.league",
            createdTime = Instant.ofEpochSecond(9000),
            authId = null,
        )

    val deletedUserId =
        insertUser(
            firstName = "Barry",
            lastName = "Allen",
            email = "flash@justice.league",
            createdTime = Instant.ofEpochSecond(1000),
            deletedTime = Instant.ofEpochSecond(12000),
            authId = "FLASH",
        )

    insertFundingEntityUser(fundingEntityId, userId1)
    insertFundingEntityUser(fundingEntityId, userId2)
    insertFundingEntityUser(fundingEntityId, userId3)
    insertFundingEntityUser(fundingEntityId, deletedUserId)

    assertEquals(
        listOf(
            FunderUserModel(
                userId = userId1,
                firstName = "Bruce",
                lastName = "Wayne",
                email = "batman@justice.league",
                createdTime = Instant.ofEpochSecond(6000),
                accountCreated = true,
            ),
            FunderUserModel(
                userId = userId2,
                firstName = "Clark",
                lastName = "Kent",
                email = "superman@justice.league",
                createdTime = Instant.ofEpochSecond(3000),
                accountCreated = false,
            ),
            FunderUserModel(
                userId = userId3,
                firstName = null,
                lastName = null,
                email = "harleyquinn@justice.league",
                createdTime = Instant.ofEpochSecond(9000),
                accountCreated = false,
            ),
        ),
        store.fetchFundersForEntity(fundingEntityId),
    )
  }
}
