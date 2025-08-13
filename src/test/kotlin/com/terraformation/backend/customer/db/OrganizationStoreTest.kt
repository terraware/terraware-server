package com.terraformation.backend.customer.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.event.OrganizationAbandonedEvent
import com.terraformation.backend.customer.event.OrganizationTimeZoneChangedEvent
import com.terraformation.backend.customer.model.FacilityModel
import com.terraformation.backend.customer.model.InternalTagIds
import com.terraformation.backend.customer.model.OrganizationModel
import com.terraformation.backend.customer.model.OrganizationUserModel
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.CannotRemoveLastOwnerException
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.InvalidTerraformationContactEmail
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.UserNotFoundException
import com.terraformation.backend.db.default_schema.FacilityConnectionState
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.ManagedLocationType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.OrganizationType
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.UserType
import com.terraformation.backend.db.default_schema.tables.pojos.OrganizationsRow
import com.terraformation.backend.db.default_schema.tables.pojos.UserPreferencesRow
import com.terraformation.backend.db.default_schema.tables.records.OrganizationManagedLocationTypesRecord
import com.terraformation.backend.db.default_schema.tables.references.USER_PREFERENCES
import com.terraformation.backend.mockUser
import io.mockk.every
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.jooq.JSONB
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class OrganizationStoreTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val clock = TestClock()
  private lateinit var permissionStore: PermissionStore
  private val publisher = TestEventPublisher()
  private lateinit var store: OrganizationStore

  private lateinit var facilityId: FacilityId
  private val facilityModel: FacilityModel by lazy {
    FacilityModel(
        connectionState = FacilityConnectionState.NotConnected,
        createdTime = Instant.EPOCH,
        description = "Description 1",
        facilityNumber = 1,
        id = facilityId,
        modifiedTime = Instant.EPOCH,
        name = "Facility 1",
        organizationId = organizationId,
        lastTimeseriesTime = null,
        maxIdleMinutes = 30,
        nextNotificationTime = Instant.EPOCH,
        timeZone = null,
        type = FacilityType.SeedBank,
    )
  }
  private val organizationModel: OrganizationModel by lazy {
    OrganizationModel(
        id = organizationId,
        name = "Organization 1",
        countryCode = "US",
        countrySubdivisionCode = "US-HI",
        createdTime = Instant.EPOCH,
        facilities = listOf(facilityModel),
        internalTags = setOf(InternalTagIds.Reporter),
        timeZone = null,
        totalUsers = 0,
    )
  }

  private lateinit var organizationId: OrganizationId
  private lateinit var timeZone: ZoneId

  @BeforeEach
  fun setUp() {
    permissionStore = PermissionStore(dslContext)
    store = OrganizationStore(clock, dslContext, organizationsDao, publisher)

    organizationId = insertOrganization(countryCode = "US", countrySubdivisionCode = "US-HI")
    insertOrganizationInternalTag(organizationId, InternalTagIds.Reporter)
    facilityId = insertFacility()

    every { user.canCreateEntityWithOwner(any()) } returns true
    every { user.canReadOrganization(any()) } returns true
    every { user.canUpdateOrganization(any()) } returns true
    every { user.canAddOrganizationUser(any()) } returns true
    every { user.canListOrganizationUsers(any()) } returns true
    every { user.canRemoveOrganizationUser(any(), any()) } returns true
    every { user.canSetOrganizationUserRole(any(), any()) } returns true
    every { user.canReadFacility(any()) } returns true

    every { user.canAddTerraformationContact(any()) } returns false
    every { user.canRemoveTerraformationContact(any()) } returns false

    every { user.facilityRoles } returns mapOf(facilityId to Role.Owner)
    every { user.organizationRoles } returns mapOf(organizationId to Role.Owner)

    timeZone = ZoneId.of("Pacific/Honolulu")
  }

  @Test
  fun `fetchAll honors fetch depth`() {
    assertEquals(
        listOf(organizationModel),
        store.fetchAll(OrganizationStore.FetchDepth.Facility),
        "Fetch depth = Facility")

    assertEquals(
        listOf(organizationModel.copy(facilities = null)),
        store.fetchAll(OrganizationStore.FetchDepth.Organization),
        "Fetch depth = Organization")
  }

  @Test
  fun `fetchById honors fetch depth`() {
    assertEquals(
        organizationModel,
        store.fetchOneById(organizationId, OrganizationStore.FetchDepth.Facility),
        "Fetch depth = Facility")

    assertEquals(
        organizationModel.copy(facilities = null),
        store.fetchOneById(organizationId, OrganizationStore.FetchDepth.Organization),
        "Fetch depth = Organization")
  }

  @Test
  fun `fetchById requires user to be able to read the organization`() {
    every { user.canReadOrganization(organizationId) } returns false

    assertThrows<OrganizationNotFoundException> { store.fetchOneById(organizationId) }
  }

  @Test
  fun `fetchById returns correct total user count`() {
    val expectedTotalUsers = 10

    repeat(expectedTotalUsers) {
      insertUser()
      insertOrganizationUser(inserted.userId)
    }

    assertEquals(expectedTotalUsers, store.fetchOneById(organizationId).totalUsers)
  }

  @Test
  fun `fetchById does not return facility data if user has read access but is not a member`() {
    every { user.facilityRoles } returns emptyMap()
    every { user.organizationRoles } returns emptyMap()

    assertEquals(
        organizationModel.copy(facilities = emptyList()),
        store.fetchOneById(organizationId, OrganizationStore.FetchDepth.Facility))
  }

  @Test
  fun `fetchAll excludes organizations the user is not in`() {
    every { user.organizationRoles } returns emptyMap()

    assertEquals(emptyList<OrganizationModel>(), store.fetchAll())
  }

  @Test
  fun `fetchById excludes projects the user is not in`() {
    every { user.facilityRoles } returns emptyMap()

    val expected = organizationModel.copy(facilities = emptyList())

    val actual = store.fetchOneById(organizationId, OrganizationStore.FetchDepth.Facility)
    assertEquals(expected, actual)
  }

  @Test
  fun `fetchAll excludes projects the user is not in`() {
    every { user.facilityRoles } returns emptyMap()

    val expected = listOf(organizationModel.copy(facilities = emptyList()))

    val actual = store.fetchAll(OrganizationStore.FetchDepth.Facility)
    assertEquals(expected, actual)
  }

  @Test
  fun `createWithAdmin populates organization details`() {
    val row =
        OrganizationsRow(
            countryCode = "US",
            countrySubdivisionCode = "US-HI",
            description = "Test description",
            name = "Test Org",
            organizationTypeId = OrganizationType.ForProfit,
            timeZone = timeZone,
            website = "website url",
        )
    val createdModel = store.createWithAdmin(row)

    val expected =
        row.copy(
            createdBy = user.userId,
            createdTime = clock.instant(),
            id = createdModel.id,
            modifiedBy = user.userId,
            modifiedTime = clock.instant(),
        )
    val actual = organizationsDao.fetchOneById(createdModel.id)!!

    assertEquals(expected, actual)
    assertEquals(1, createdModel.totalUsers, "Total users")
  }

  @Test
  fun `createWithAdmin folds country and subdivision codes to all-caps`() {
    val createdModel =
        store.createWithAdmin(
            OrganizationsRow(
                countryCode = "us",
                countrySubdivisionCode = "us-hi",
                name = "Test Org",
            ))
    val row = organizationsDao.fetchOneById(createdModel.id)!!

    assertEquals("US", row.countryCode, "Country code")
    assertEquals("US-HI", row.countrySubdivisionCode, "Subdivision code")
  }

  @Test
  fun `createWithAdmin rejects invalid country codes`() {
    assertThrows<IllegalArgumentException> {
      store.createWithAdmin(OrganizationsRow(countryCode = "XX", name = "Test Org"))
    }
  }

  @Test
  fun `createWithAdmin rejects invalid country subdivision codes`() {
    assertThrows<IllegalArgumentException> {
      store.createWithAdmin(
          OrganizationsRow(countryCode = "US", countrySubdivisionCode = "US-XX", name = "Test Org"))
    }
  }

  @Test
  fun `createWithAdmin rejects mismatched country and subdivision codes`() {
    assertThrows<IllegalArgumentException> {
      store.createWithAdmin(
          OrganizationsRow(countryCode = "GB", countrySubdivisionCode = "US-HI", name = "Test Org"))
    }
  }

  @Test
  fun `createWithAdmin adds current user as admin`() {
    val createdModel = store.createWithAdmin(OrganizationsRow(name = "Test Org"))
    val roles = permissionStore.fetchOrganizationRoles(user.userId)

    assertEquals(mapOf(createdModel.id to Role.Owner), roles)
  }

  @Test
  fun `createWithAdmin populates managed location details`() {
    val row =
        OrganizationsRow(
            countryCode = "US",
            countrySubdivisionCode = "US-HI",
            description = "Test description",
            name = "Test Org",
            timeZone = timeZone,
        )
    val createdModel =
        store.createWithAdmin(row, setOf(ManagedLocationType.Nursery, ManagedLocationType.SeedBank))

    assertTableEquals(
        listOf(
            OrganizationManagedLocationTypesRecord(
                organizationId = createdModel.id,
                managedLocationTypeId = ManagedLocationType.Nursery),
            OrganizationManagedLocationTypesRecord(
                organizationId = createdModel.id,
                managedLocationTypeId = ManagedLocationType.SeedBank)))
  }

  @Test
  fun `createWithAdmin creates with organization type 'Other' and non-empty additional details`() {
    val row =
        OrganizationsRow(
            countryCode = "US",
            countrySubdivisionCode = "US-HI",
            description = "Test description",
            name = "Test Org",
            organizationTypeId = OrganizationType.Other,
            organizationTypeDetails = "Here's more info",
            timeZone = timeZone,
        )

    val createdModel = store.createWithAdmin(row)

    val actual = organizationsDao.fetchOneById(createdModel.id)!!

    assertEquals(OrganizationType.Other, actual.organizationTypeId)
    assertEquals("Here's more info", actual.organizationTypeDetails)
  }

  @Test
  fun `createWithAdmin rejects organization type 'Other' with null additional details`() {
    val row =
        OrganizationsRow(
            countryCode = "US",
            countrySubdivisionCode = "US-HI",
            description = "Test description",
            name = "Test Org",
            organizationTypeId = OrganizationType.Other,
            timeZone = timeZone,
        )

    assertThrows<IllegalArgumentException> { store.createWithAdmin(row) }
  }

  @Test
  fun `createWithAdmin rejects organization type 'Other' with empty additional details`() {
    val row =
        OrganizationsRow(
            countryCode = "US",
            countrySubdivisionCode = "US-HI",
            description = "Test description",
            name = "Test Org",
            organizationTypeId = OrganizationType.Other,
            organizationTypeDetails = "",
            timeZone = timeZone,
        )

    assertThrows<IllegalArgumentException> { store.createWithAdmin(row) }
  }

  @Test
  fun `createWithAdmin rejects organization type that is not 'Other' and with additional details`() {
    val row =
        OrganizationsRow(
            countryCode = "US",
            countrySubdivisionCode = "US-HI",
            description = "Test description",
            name = "Test Org",
            organizationTypeId = OrganizationType.NGO,
            organizationTypeDetails = "Fail!",
            timeZone = timeZone,
        )

    assertThrows<IllegalArgumentException> { store.createWithAdmin(row) }
  }

  @Test
  fun `update populates organization details`() {
    val newTime = clock.instant().plusSeconds(1000)
    clock.instant = newTime

    val newUserId = insertUser()

    val updates =
        OrganizationsRow(
            id = organizationId,
            name = "New Name",
            description = "New Description",
            countryCode = "ZA",
            organizationTypeId = OrganizationType.Academia,
            timeZone = timeZone,
            website = "test site",
        )
    val expected =
        updates.copy(
            createdBy = user.userId,
            createdTime = Instant.EPOCH,
            modifiedBy = newUserId,
            modifiedTime = newTime,
        )

    every { user.userId } returns newUserId

    store.update(updates)

    val actual = organizationsDao.fetchOneById(organizationId)!!

    assertEquals(expected, actual)
  }

  @Test
  fun `update publishes event if time zone is changed`() {
    val existing = organizationsDao.fetchOneById(organizationId)!!
    val newTimeZone = ZoneId.of("Europe/London")

    store.update(existing)
    publisher.assertEventNotPublished<OrganizationTimeZoneChangedEvent>()

    store.update(existing.copy(timeZone = newTimeZone))
    publisher.assertEventPublished(
        OrganizationTimeZoneChangedEvent(organizationId, null, newTimeZone))
  }

  @Test
  fun `update throws exception if user has no permission to update organization`() {
    every { user.canUpdateOrganization(organizationId) } returns false

    assertThrows<AccessDeniedException> {
      store.update(OrganizationsRow(id = organizationId, name = "New Name"))
    }
  }

  @Test
  fun `update rejects invalid country codes`() {
    assertThrows<IllegalArgumentException> {
      store.update(OrganizationsRow(id = organizationId, name = "X", countryCode = "XX"))
    }
  }

  @Test
  fun `fetchUsers returns information about members`() {
    val expected =
        listOf(
                OrganizationUserModel(
                    UserId(0),
                    "${UUID.randomUUID()}@x.com",
                    "First1",
                    "Last1",
                    UserType.Individual,
                    clock.instant().plus(1, ChronoUnit.DAYS),
                    organizationId,
                    Role.Admin),
                OrganizationUserModel(
                    UserId(0),
                    "${UUID.randomUUID()}@x.com",
                    "First2",
                    "Last2",
                    UserType.Individual,
                    clock.instant().plus(2, ChronoUnit.MINUTES),
                    organizationId,
                    Role.Contributor),
            )
            .map { configureUser(it) }

    configureUser(
        OrganizationUserModel(
            UserId(0),
            "balena-${UUID.randomUUID()}",
            "Api",
            "Client",
            UserType.DeviceManager,
            clock.instant(),
            organizationId,
            Role.Contributor))

    val actual = store.fetchUsers(organizationId)

    assertEquals(expected, actual)
  }

  @Test
  fun `fetchUsers throws exception if member has no permission to list users`() {
    every { user.canListOrganizationUsers(organizationId) } returns false

    assertThrows<AccessDeniedException> { store.fetchUsers(organizationId) }
  }

  @Test
  fun `fetchUsers throws exception if member has no permission to see organization`() {
    every { user.canReadOrganization(organizationId) } returns false
    every { user.canListOrganizationUsers(organizationId) } returns false

    assertThrows<OrganizationNotFoundException> { store.fetchUsers(organizationId) }
  }

  @Test
  fun `fetchUsers only requires permission to list users, not to read other organization data`() {
    every { user.canReadOrganization(organizationId) } returns false

    val model =
        configureUser(
            OrganizationUserModel(
                UserId(0),
                "x@y.com",
                "First",
                "Last",
                UserType.Individual,
                clock.instant(),
                organizationId,
                Role.Contributor))

    val expected = listOf(model)
    val actual = store.fetchUsers(organizationId)

    assertEquals(expected, actual)
  }

  @Test
  fun `addUser sets user role`() {
    val newUserId = insertUser()

    store.addUser(organizationId, newUserId, Role.Contributor)

    val model = store.fetchUser(organizationId, newUserId)
    assertEquals(Role.Contributor, model.role)
  }

  @Test
  fun `addUser throws exception if no permission to add users`() {
    every { user.canAddOrganizationUser(organizationId) } returns false

    assertThrows<AccessDeniedException> {
      store.addUser(organizationId, currentUser().userId, Role.Contributor)
    }
  }

  @Test
  fun `addUser throws exception adding Terraformation Contact when no permissions`() {
    assertThrows<AccessDeniedException> {
      store.addUser(organizationId, currentUser().userId, Role.TerraformationContact)
    }
  }

  @Test
  fun `addUser throws exception adding Terraformation Contact with non-terraformation email`() {
    every { user.canAddTerraformationContact(organizationId) } returns true

    val newUserId = insertUser(email = "user@nonterraformation.com")

    assertThrows<InvalidTerraformationContactEmail> {
      store.addUser(organizationId, newUserId, Role.TerraformationContact)
    }
  }

  @Test
  fun `addUser adds Terraformation Contact when permitted`() {
    every { user.canAddTerraformationContact(organizationId) } returns true

    val newUserId = insertUser()

    store.addUser(organizationId, newUserId, Role.TerraformationContact)

    val model = store.fetchUser(organizationId, newUserId)
    assertEquals(Role.TerraformationContact, model.role, "Should have Terraformation Contact role")
  }

  @Test
  fun `addUser adds Terraformation Contact regardless of terraformation domain email case`() {
    every { user.canAddTerraformationContact(organizationId) } returns true

    val newUserId = insertUser(email = "newUser@TerraFormation.COM")

    store.addUser(organizationId, newUserId, Role.TerraformationContact)

    val model = store.fetchUser(organizationId, newUserId)
    assertEquals(Role.TerraformationContact, model.role, "Should have Terraformation Contact role")
  }

  @Test
  fun `addUser allows multiple Terraformation Contacts when permitted to add`() {
    every { user.canAddTerraformationContact(organizationId) } returns true

    val userId1 = insertUser()
    val userId2 = insertUser()

    store.addUser(organizationId, userId1, Role.TerraformationContact)
    store.addUser(organizationId, userId2, Role.TerraformationContact)

    val model1 = store.fetchUser(organizationId, userId1)
    val model2 = store.fetchUser(organizationId, userId2)
    assertEquals(Role.TerraformationContact, model1.role, "Should have Terraformation Contact role")
    assertEquals(Role.TerraformationContact, model2.role, "Should have Terraformation Contact role")
  }

  @Test
  fun `fetchTerraformationContact returns the user id of Terraformation Contact`() {
    assertEquals(
        null,
        store.fetchTerraformationContact(organizationId),
        "Should not find a Terraformation Contact")

    val tfContact = configureUser(organizationUserModel(role = Role.TerraformationContact))

    assertEquals(
        tfContact.userId,
        store.fetchTerraformationContact(organizationId),
        "Should find a Terraformation Contact")
  }

  @Test
  fun `fetchTerraformationContact throws exception if no permission to list organization users`() {
    every { user.canListOrganizationUsers(organizationId) } returns false

    assertThrows<AccessDeniedException> { store.fetchTerraformationContact(organizationId) }
  }

  @Test
  fun `removeUser throws exception if no permission to remove users`() {
    every { user.canRemoveOrganizationUser(organizationId, currentUser().userId) } returns false

    assertThrows<AccessDeniedException> { store.removeUser(organizationId, currentUser().userId) }
  }

  @Test
  fun `removeUser throws exception removing Terraformation Contact when no permissions`() {
    val tfContact = configureUser(organizationUserModel(role = Role.TerraformationContact))

    assertThrows<AccessDeniedException> { store.removeUser(organizationId, tfContact.userId) }
  }

  @Test
  fun `removeUser removes Terraformation Contact when permitted`() {
    val tfContact = configureUser(organizationUserModel(role = Role.TerraformationContact))
    assertNotNull(
        store.fetchUser(organizationId, tfContact.userId),
        "Should find a Terraformation Contact user")

    every { user.canRemoveTerraformationContact(organizationId) } returns true

    store.removeUser(organizationId, tfContact.userId)
    assertThrows<UserNotFoundException> { store.fetchUser(organizationId, tfContact.userId) }
  }

  @Test
  fun `removeUser throws exception if user is not in the organization`() {
    assertThrows<UserNotFoundException> { store.removeUser(organizationId, currentUser().userId) }
  }

  @Test
  fun `removeUser removes user from requested organization`() {
    val model = configureUser(organizationUserModel())

    val otherOrgId = insertOrganization()
    insertOrganizationUser(model.userId)

    dslContext
        .insertInto(
            USER_PREFERENCES,
            USER_PREFERENCES.USER_ID,
            USER_PREFERENCES.ORGANIZATION_ID,
            USER_PREFERENCES.PREFERENCES)
        .values(model.userId, null, JSONB.valueOf("{\"org\":\"null\"}"))
        .values(model.userId, organizationId, JSONB.valueOf("{\"org\":\"1\"}"))
        .execute()

    store.removeUser(organizationId, model.userId)

    assertThrows<UserNotFoundException> { store.fetchUser(organizationId, model.userId) }
    assertNotNull(
        store.fetchUser(otherOrgId, model.userId), "User should still belong to other org")
    assertEquals(
        listOf(UserPreferencesRow(model.userId, null, JSONB.valueOf("{\"org\":\"null\"}"))),
        dslContext.selectFrom(USER_PREFERENCES).fetchInto(UserPreferencesRow::class.java),
        "User preferences for organization should be removed")
  }

  @Test
  fun `removeUser allows removing owner if there is another owner`() {
    val owner1 = configureUser(organizationUserModel(role = Role.Owner))
    configureUser(organizationUserModel(role = Role.Owner))

    store.removeUser(organizationId, owner1.userId)

    assertThrows<UserNotFoundException> { store.fetchUser(organizationId, owner1.userId) }
  }

  @Test
  fun `removeUser does not allow removing the only owner by default`() {
    val owner = configureUser(organizationUserModel(role = Role.Owner))
    configureUser(organizationUserModel(role = Role.Admin))

    assertThrows<CannotRemoveLastOwnerException> { store.removeUser(organizationId, owner.userId) }
  }

  @Test
  fun `removeUser publishes OrganizationAbandonedEvent if last user is removed`() {
    val owner = configureUser(organizationUserModel(role = Role.Owner))

    store.removeUser(organizationId, owner.userId, allowRemovingLastOwner = true)

    publisher.assertEventPublished(OrganizationAbandonedEvent(organizationId))
  }

  @Test
  fun `setUserRole allows demoting owner if there is another owner`() {
    val owner1 = configureUser(organizationUserModel(role = Role.Owner))
    configureUser(organizationUserModel(role = Role.Owner))

    store.setUserRole(organizationId, owner1.userId, Role.Admin)

    assertEquals(
        Role.Admin, store.fetchUser(organizationId, owner1.userId).role, "Should find Admin role")
  }

  @Test
  fun `setUserRole does not allow demoting the only owner`() {
    val owner = configureUser(organizationUserModel(role = Role.Owner))
    configureUser(organizationUserModel(role = Role.Admin))

    assertThrows<CannotRemoveLastOwnerException> {
      store.setUserRole(organizationId, owner.userId, Role.Admin)
    }
  }

  @Test
  fun `setUserRole throws exception when setting new role as Terraformation Contact when not permitted`() {
    val owner = configureUser(organizationUserModel(role = Role.Owner))

    assertThrows<AccessDeniedException> {
      store.setUserRole(organizationId, owner.userId, Role.TerraformationContact)
    }
  }

  @Test
  fun `setUserRole throws exception when setting new role as Terraformation Contact for non-terraformation email`() {
    val owner =
        configureUser(
            organizationUserModel(email = "user@nonterraformation.com", role = Role.Owner))

    every { user.canAddTerraformationContact(organizationId) } returns true
    every { user.canSetOrganizationUserRole(organizationId, any()) } returns false

    assertThrows<InvalidTerraformationContactEmail> {
      store.setUserRole(organizationId, owner.userId, Role.TerraformationContact)
    }
  }

  @Test
  fun `setUserRole throws exception when updating a Terraformation Contact user when not permitted`() {
    val tfContact = configureUser(organizationUserModel(role = Role.TerraformationContact))

    every { user.canRemoveTerraformationContact(organizationId) } returns false

    assertThrows<AccessDeniedException> {
      store.setUserRole(organizationId, tfContact.userId, Role.Admin)
    }
  }

  @Test
  fun `setUserRole allows updating a Terraformation Contact user role when permitted`() {
    val tfContact = configureUser(organizationUserModel(role = Role.TerraformationContact))

    every { user.canAddTerraformationContact(organizationId) } returns true
    every { user.canRemoveTerraformationContact(organizationId) } returns true
    every { user.canSetOrganizationUserRole(organizationId, any()) } returns false

    store.setUserRole(organizationId, tfContact.userId, Role.Admin)
    assertEquals(
        Role.Admin,
        store.fetchUser(organizationId = organizationId, userId = tfContact.userId).role,
        "Should find updated Admin role")
  }

  @Test
  fun `setUserRole allows setting new role as Terraformation Contact when permitted`() {
    configureUser(organizationUserModel(role = Role.Owner))
    val admin =
        configureUser(organizationUserModel(email = "admin@terraformation.com", role = Role.Admin))

    every { user.canAddTerraformationContact(organizationId) } returns true
    every { user.canSetOrganizationUserRole(organizationId, any()) } returns false

    store.setUserRole(organizationId, admin.userId, Role.TerraformationContact)
    assertEquals(
        Role.TerraformationContact,
        store.fetchUser(organizationId = organizationId, userId = admin.userId).role,
        "Should find updated Terraformation Contact role")
  }

  @Test
  fun `countRoleUsers includes counts for roles with no users`() {
    listOf(Role.Owner, Role.Owner, Role.Contributor).forEach { role ->
      configureUser(organizationUserModel(role = role))
    }

    val expected =
        mapOf(
            Role.Admin to 0,
            Role.Manager to 0,
            Role.Contributor to 1,
            Role.Owner to 2,
            Role.TerraformationContact to 0,
        )
    val actual = store.countRoleUsers(organizationId)

    assertEquals(expected, actual)
  }

  @Test
  fun `countRoleUsers requires permission to list users`() {
    every { user.canListOrganizationUsers(organizationId) } returns false

    assertThrows<AccessDeniedException> { store.countRoleUsers(organizationId) }
  }

  @Test
  fun `deleting an org should also delete managed location data`() {
    every { user.canDeleteOrganization(any()) } returns true
    val row =
        OrganizationsRow(
            countryCode = "US",
            countrySubdivisionCode = "US-HI",
            description = "Test description",
            name = "Test Org",
            timeZone = timeZone,
        )
    val createdModel =
        store.createWithAdmin(row, setOf(ManagedLocationType.Nursery, ManagedLocationType.SeedBank))

    assertTableEquals(
        listOf(
            OrganizationManagedLocationTypesRecord(
                organizationId = createdModel.id,
                managedLocationTypeId = ManagedLocationType.Nursery),
            OrganizationManagedLocationTypesRecord(
                organizationId = createdModel.id,
                managedLocationTypeId = ManagedLocationType.SeedBank)))

    store.delete(createdModel.id)

    val expectedAfterDeletion = emptyList<ManagedLocationType>()
    val actualAfterDeletion = organizationManagedLocationTypesDao.findAll()
    assertEquals(expectedAfterDeletion, actualAfterDeletion)
  }

  private fun organizationUserModel(
      email: String = "${UUID.randomUUID()}@y.com",
      firstName: String = "First",
      lastName: String = "Last",
      userType: UserType = UserType.Individual,
      createdTime: Instant = clock.instant(),
      organizationId: OrganizationId = this.organizationId,
      role: Role = Role.Contributor,
  ): OrganizationUserModel {
    return OrganizationUserModel(
        UserId(0), email, firstName, lastName, userType, createdTime, organizationId, role)
  }

  private fun configureUser(model: OrganizationUserModel): OrganizationUserModel {
    val userId = insertUser(null, model.email, model.firstName, model.lastName, model.userType)
    insertOrganizationUser(
        userId, model.organizationId, model.role, createdTime = model.createdTime)
    return model.copy(userId = userId)
  }
}
