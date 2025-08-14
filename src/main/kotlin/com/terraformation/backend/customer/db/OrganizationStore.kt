package com.terraformation.backend.customer.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.event.OrganizationAbandonedEvent
import com.terraformation.backend.customer.event.OrganizationDeletionStartedEvent
import com.terraformation.backend.customer.event.OrganizationTimeZoneChangedEvent
import com.terraformation.backend.customer.model.FacilityModel
import com.terraformation.backend.customer.model.OrganizationModel
import com.terraformation.backend.customer.model.OrganizationUserModel
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.customer.model.toModel
import com.terraformation.backend.db.CannotRemoveLastOwnerException
import com.terraformation.backend.db.InvalidTerraformationContactEmail
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.UserAlreadyInOrganizationException
import com.terraformation.backend.db.UserNotFoundException
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.ManagedLocationType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.OrganizationType
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.UserType
import com.terraformation.backend.db.default_schema.tables.daos.OrganizationsDao
import com.terraformation.backend.db.default_schema.tables.pojos.OrganizationsRow
import com.terraformation.backend.db.default_schema.tables.references.COUNTRIES
import com.terraformation.backend.db.default_schema.tables.references.COUNTRY_SUBDIVISIONS
import com.terraformation.backend.db.default_schema.tables.references.FACILITIES
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATION_INTERNAL_TAGS
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATION_MANAGED_LOCATION_TYPES
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATION_USERS
import com.terraformation.backend.db.default_schema.tables.references.USERS
import com.terraformation.backend.db.default_schema.tables.references.USER_PREFERENCES
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named
import java.time.Clock
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DuplicateKeyException

@Named
class OrganizationStore(
    private val clock: Clock,
    private val dslContext: DSLContext,
    private val organizationsDao: OrganizationsDao,
    private val publisher: ApplicationEventPublisher,
) {
  private val log = perClassLogger()

  /** Returns all the organizations the user is a member of. */
  fun fetchAll(depth: FetchDepth = FetchDepth.Organization): List<OrganizationModel> {
    val organizationIds = currentUser().organizationRoles.keys

    if (organizationIds.isEmpty()) {
      return emptyList()
    }

    return selectForDepth(depth, ORGANIZATIONS.ID.`in`(organizationIds))
  }

  fun fetchOneById(
      organizationId: OrganizationId,
      depth: FetchDepth = FetchDepth.Organization
  ): OrganizationModel {
    requirePermissions { readOrganization(organizationId) }

    return selectForDepth(depth, ORGANIZATIONS.ID.eq(organizationId)).firstOrNull()
        ?: throw OrganizationNotFoundException(organizationId)
  }

  private fun selectForDepth(depth: FetchDepth, condition: Condition): List<OrganizationModel> {
    val user = currentUser()

    val internalTagsMultiset =
        DSL.multiset(
                DSL.select(ORGANIZATION_INTERNAL_TAGS.INTERNAL_TAG_ID)
                    .from(ORGANIZATION_INTERNAL_TAGS)
                    .where(ORGANIZATION_INTERNAL_TAGS.ORGANIZATION_ID.eq(ORGANIZATIONS.ID)))
            .convertFrom { result ->
              result.map { it[ORGANIZATION_INTERNAL_TAGS.INTERNAL_TAG_ID.asNonNullable()] }
            }

    val facilitiesMultiset =
        if (depth.level >= FetchDepth.Facility.level) {
          // If the user doesn't have access to any facilities, we still want to construct a
          // properly-typed multiset, but it should be empty.
          val facilityIds = user.facilityRoles.keys
          val facilitiesCondition =
              if (facilityIds.isNotEmpty()) {
                FACILITIES.ID.`in`(facilityIds)
              } else {
                DSL.falseCondition()
              }

          DSL.multiset(
                  DSL.selectFrom(FACILITIES)
                      .where(FACILITIES.ORGANIZATION_ID.eq(ORGANIZATIONS.ID))
                      .and(facilitiesCondition)
                      .orderBy(FACILITIES.ID))
              .convertFrom { result -> result.map { FacilityModel(it) } }
        } else {
          DSL.value(null as List<FacilityModel>?)
        }

    val totalUsersSubquery =
        DSL.field(
            DSL.selectCount()
                .from(ORGANIZATION_USERS)
                .where(ORGANIZATION_USERS.ORGANIZATION_ID.eq(ORGANIZATIONS.ID)))

    return dslContext
        .select(
            ORGANIZATIONS.asterisk(), facilitiesMultiset, internalTagsMultiset, totalUsersSubquery)
        .from(ORGANIZATIONS)
        .where(listOfNotNull(condition))
        .orderBy(ORGANIZATIONS.ID)
        .fetch {
          OrganizationModel(it, facilitiesMultiset, internalTagsMultiset, totalUsersSubquery)
        }
  }

  /** Creates a new organization and adds a user as owner. */
  fun createWithAdmin(
      row: OrganizationsRow,
      managedLocationTypes: Set<ManagedLocationType> = emptySet(),
      ownerUserId: UserId = currentUser().userId,
  ): OrganizationModel {
    requirePermissions { createEntityWithOwner(ownerUserId) }

    validateCountryCode(row.countryCode, row.countrySubdivisionCode)
    validateOrganizationType(row.organizationTypeId, row.organizationTypeDetails)

    val fullRow =
        OrganizationsRow(
            countryCode = row.countryCode?.uppercase(),
            countrySubdivisionCode = row.countrySubdivisionCode?.uppercase(),
            createdBy = currentUser().userId,
            createdTime = clock.instant(),
            description = row.description,
            modifiedBy = currentUser().userId,
            modifiedTime = clock.instant(),
            name = row.name,
            organizationTypeId = row.organizationTypeId,
            organizationTypeDetails = row.organizationTypeDetails,
            timeZone = row.timeZone,
            website = row.website,
        )

    return dslContext.transactionResult { _ ->
      organizationsDao.insert(fullRow)

      with(ORGANIZATION_USERS) {
        dslContext
            .insertInto(ORGANIZATION_USERS)
            .set(CREATED_BY, currentUser().userId)
            .set(CREATED_TIME, clock.instant())
            .set(MODIFIED_BY, currentUser().userId)
            .set(MODIFIED_TIME, clock.instant())
            .set(ORGANIZATION_ID, fullRow.id)
            .set(ROLE_ID, Role.Owner)
            .set(USER_ID, ownerUserId)
            .execute()
      }

      managedLocationTypes.forEach { locationType ->
        with(ORGANIZATION_MANAGED_LOCATION_TYPES) {
          dslContext
              .insertInto(ORGANIZATION_MANAGED_LOCATION_TYPES)
              .set(ORGANIZATION_ID, fullRow.id)
              .set(MANAGED_LOCATION_TYPE_ID, locationType)
              .execute()
        }
      }

      fullRow.toModel(totalUsers = 1)
    }
  }

  fun update(row: OrganizationsRow) {
    val organizationId = row.id ?: throw IllegalArgumentException("Organization ID must be set")

    requirePermissions { updateOrganization(organizationId) }

    validateCountryCode(row.countryCode, row.countrySubdivisionCode)
    validateOrganizationType(row.organizationTypeId, row.organizationTypeDetails)

    val existingRow = organizationsDao.fetchOneById(organizationId)

    with(ORGANIZATIONS) {
      dslContext
          .update(ORGANIZATIONS)
          .set(COUNTRY_CODE, row.countryCode)
          .set(COUNTRY_SUBDIVISION_CODE, row.countrySubdivisionCode)
          .set(DESCRIPTION, row.description)
          .set(MODIFIED_BY, currentUser().userId)
          .set(MODIFIED_TIME, clock.instant())
          .set(NAME, row.name)
          .set(ORGANIZATION_TYPE_ID, row.organizationTypeId)
          .set(ORGANIZATION_TYPE_DETAILS, row.organizationTypeDetails)
          .set(TIME_ZONE, row.timeZone)
          .set(WEBSITE, row.website)
          .where(ID.eq(organizationId))
          .execute()
    }

    if (existingRow?.timeZone != row.timeZone) {
      publisher.publishEvent(
          OrganizationTimeZoneChangedEvent(organizationId, existingRow?.timeZone, row.timeZone))
    }
  }

  /**
   * Deletes the organization from the database. This also has the effect of deleting data from the
   * tree of child tables that reference the organization, even indirectly, and thus can take a long
   * time. Avoid calling this from a request handler; call it from an asynchronous job instead.
   *
   * This can fail if the organization contains data that can't be automatically deleted by the
   * database, such as references to external files that need to be deleted by application code. A
   * good place to do that is in listeners for the [OrganizationDeletionStartedEvent] that is
   * published here before the organization is actually deleted.
   */
  fun delete(organizationId: OrganizationId) {
    requirePermissions { deleteOrganization(organizationId) }

    // Inform the system that we're about to delete the organization and that any external resources
    // tied to the organization should be cleaned up.
    //
    // This is not wrapped in a transaction because listeners are expected to delete external
    // resources and then update the database to remove the references to them; if that happened
    // inside an enclosing transaction, then a listener throwing an exception could cause the system
    // to roll back the updates that recorded the successful removal of external resources by an
    // earlier one.
    //
    // There's an unavoidable tradeoff here: if a listener fails, the organization data will end up
    // partially deleted.
    publisher.publishEvent(OrganizationDeletionStartedEvent(organizationId))

    // Deleting the organization will trigger cascading deletes of data in other tables that refer
    // to the organization. This can cause the deletion to take a long time to finish: consider,
    // for example, an organization with a month's worth of historical sensor data.
    organizationsDao.deleteById(organizationId)
  }

  /** Returns a list of the organization's individual users. */
  fun fetchUsers(organizationId: OrganizationId): List<OrganizationUserModel> {
    requirePermissions { listOrganizationUsers(organizationId) }

    return queryOrganizationUsers(
        ORGANIZATION_USERS.ORGANIZATION_ID.eq(organizationId)
            .and(USERS.USER_TYPE_ID.eq(UserType.Individual)))
  }

  fun fetchUser(organizationId: OrganizationId, userId: UserId): OrganizationUserModel {
    requirePermissions { listOrganizationUsers(organizationId) }

    return queryOrganizationUsers(
            ORGANIZATION_USERS.ORGANIZATION_ID.eq(organizationId)
                .and(ORGANIZATION_USERS.USER_ID.eq(userId)))
        .firstOrNull() ?: throw UserNotFoundException(userId)
  }

  fun fetchOrganizationIds(userId: UserId): List<OrganizationId> {
    val user = currentUser()

    return dslContext
        .select(ORGANIZATION_USERS.ORGANIZATION_ID)
        .from(ORGANIZATION_USERS)
        .where(ORGANIZATION_USERS.USER_ID.eq(userId))
        .fetch(ORGANIZATION_USERS.ORGANIZATION_ID.asNonNullable())
        .filter { user.userId == userId || user.canListOrganizationUsers(it) }
  }

  private fun queryOrganizationUsers(condition: Condition): List<OrganizationUserModel> {
    return dslContext
        .select(
            USERS.ID,
            USERS.EMAIL,
            USERS.FIRST_NAME,
            USERS.LAST_NAME,
            USERS.USER_TYPE_ID,
            ORGANIZATION_USERS.CREATED_TIME,
            ORGANIZATION_USERS.ORGANIZATION_ID,
            ORGANIZATION_USERS.ROLE_ID,
        )
        .from(ORGANIZATION_USERS)
        .join(USERS)
        .on(ORGANIZATION_USERS.USER_ID.eq(USERS.ID))
        .where(condition)
        .orderBy(USERS.ID)
        .fetch()
        .mapNotNull { record ->
          val userId = record[USERS.ID]
          val organizationId = record[ORGANIZATION_USERS.ORGANIZATION_ID]
          val userType = record[USERS.USER_TYPE_ID]
          val createdTime = record[ORGANIZATION_USERS.CREATED_TIME]
          val email = record[USERS.EMAIL]

          val firstName = record[USERS.FIRST_NAME]
          val lastName = record[USERS.LAST_NAME]
          val role = record[ORGANIZATION_USERS.ROLE_ID]

          if (userId != null &&
              organizationId != null &&
              userType != null &&
              createdTime != null &&
              email != null &&
              role != null) {
            OrganizationUserModel(
                userId,
                email,
                firstName,
                lastName,
                userType,
                createdTime,
                organizationId,
                role,
            )
          } else {
            log.error("Missing required fields for $userId: $record")
            null
          }
        }
  }

  /**
   * Returns the email addresses that should receive notifications for an organization.
   *
   * @param requireOptIn Only return the addresses of users who are opted into email notifications.
   * @param roles If nonnull, only return the addresses of users with the specified roles in the
   *   organization.
   */
  fun fetchEmailRecipients(
      organizationId: OrganizationId,
      requireOptIn: Boolean = true,
      roles: Set<Role>? = null,
  ): List<String> {
    if (roles?.isEmpty() == true) {
      return emptyList()
    }

    val optInCondition = if (requireOptIn) USERS.EMAIL_NOTIFICATIONS_ENABLED.isTrue else null
    val roleCondition = roles?.let { ORGANIZATION_USERS.ROLE_ID.`in`(it) }

    return dslContext
        .select(USERS.EMAIL)
        .from(USERS)
        .join(ORGANIZATION_USERS)
        .on(USERS.ID.eq(ORGANIZATION_USERS.USER_ID))
        .where(
            listOfNotNull(
                ORGANIZATION_USERS.ORGANIZATION_ID.eq(organizationId),
                USERS.USER_TYPE_ID.eq(UserType.Individual),
                optInCondition,
                roleCondition))
        .fetch(USERS.EMAIL.asNonNullable())
  }

  /**
   * Adds a user to an organization.
   *
   * @throws UserAlreadyInOrganizationException The user was already a member of the organization.
   */
  fun addUser(organizationId: OrganizationId, userId: UserId, role: Role) {
    val isTerraformationContact = role == Role.TerraformationContact

    requirePermissions {
      if (isTerraformationContact) {
        addTerraformationContact(organizationId)
      } else {
        addOrganizationUser(organizationId)
        setOrganizationUserRole(organizationId, role)
      }
    }

    if (isTerraformationContact) {
      validateTerraformationContactEmail(userId)
    }

    try {
      with(ORGANIZATION_USERS) {
        dslContext
            .insertInto(ORGANIZATION_USERS)
            .set(ORGANIZATION_ID, organizationId)
            .set(USER_ID, userId)
            .set(ROLE_ID, role)
            .set(CREATED_BY, currentUser().userId)
            .set(CREATED_TIME, clock.instant())
            .set(MODIFIED_BY, currentUser().userId)
            .set(MODIFIED_TIME, clock.instant())
            .execute()
      }
    } catch (e: DuplicateKeyException) {
      throw UserAlreadyInOrganizationException(userId, organizationId)
    }

    log.info("Added user $userId to organization $organizationId with role $role")
  }

  /**
   * Removes a user from an organization.
   *
   * The user may still be referenced in the organization's data. For example, if they uploaded
   * photos, `photos.user_id` will still refer to them.
   *
   * @throws CannotRemoveLastOwnerException The user is an owner and the organization has no other
   *   owners.
   * @throws UserNotFoundException The user was not a member of the organization.
   */
  fun removeUser(
      organizationId: OrganizationId,
      userId: UserId,
      allowRemovingLastOwner: Boolean = false,
  ) {
    requirePermissions {
      if (getUserRole(organizationId, userId) == Role.TerraformationContact) {
        removeTerraformationContact(organizationId)
      } else {
        removeOrganizationUser(organizationId, userId)
      }
    }

    dslContext.transaction { _ ->
      if (!allowRemovingLastOwner) {
        ensureOtherOwners(organizationId, userId)
      }

      val rowsDeleted =
          dslContext
              .deleteFrom(ORGANIZATION_USERS)
              .where(ORGANIZATION_USERS.ORGANIZATION_ID.eq(organizationId))
              .and(ORGANIZATION_USERS.USER_ID.eq(userId))
              .execute()

      if (rowsDeleted < 1) {
        throw UserNotFoundException(userId)
      }

      dslContext
          .deleteFrom(USER_PREFERENCES)
          .where(USER_PREFERENCES.USER_ID.eq(userId))
          .and(USER_PREFERENCES.ORGANIZATION_ID.eq(organizationId))
          .execute()

      if (allowRemovingLastOwner) {
        val hasRemainingUsers =
            dslContext
                .selectOne()
                .from(ORGANIZATION_USERS)
                .where(ORGANIZATION_USERS.ORGANIZATION_ID.eq(organizationId))
                .fetch()
                .isNotEmpty

        if (!hasRemainingUsers) {
          log.info("Deleted last owner from organization $organizationId")

          publisher.publishEvent(OrganizationAbandonedEvent(organizationId))
        }
      }
    }
  }

  /**
   * Updates the role of an existing organization user.
   *
   * @throws CannotRemoveLastOwnerException The user is an owner, the requested role is not
   *   [Role.Owner], and the organization has no other owners.
   * @throws UserNotFoundException The user is not a member of the organization.
   */
  fun setUserRole(organizationId: OrganizationId, userId: UserId, role: Role) {
    val isTerraformationContact = role == Role.TerraformationContact

    requirePermissions {
      if (getUserRole(organizationId, userId) == Role.TerraformationContact &&
          !isTerraformationContact) {
        removeTerraformationContact(organizationId)
      } else if (isTerraformationContact) {
        addTerraformationContact(organizationId)
      } else {
        setOrganizationUserRole(organizationId, role)
      }
    }

    if (isTerraformationContact) {
      validateTerraformationContactEmail(userId)
    }

    dslContext.transaction { _ ->
      if (role != Role.Owner) {
        ensureOtherOwners(organizationId, userId)
      }

      val rowsUpdated =
          dslContext
              .update(ORGANIZATION_USERS)
              .set(ORGANIZATION_USERS.ROLE_ID, role)
              .set(ORGANIZATION_USERS.MODIFIED_BY, currentUser().userId)
              .set(ORGANIZATION_USERS.MODIFIED_TIME, clock.instant())
              .where(ORGANIZATION_USERS.ORGANIZATION_ID.eq(organizationId))
              .and(ORGANIZATION_USERS.USER_ID.eq(userId))
              .execute()

      if (rowsUpdated < 1) {
        throw UserNotFoundException(userId)
      }
    }
  }

  /** Returns the number of users with each available role in an organization. */
  fun countRoleUsers(organizationId: OrganizationId): Map<Role, Int> {
    requirePermissions { listOrganizationUsers(organizationId) }

    val countByRoleId =
        with(ORGANIZATION_USERS) {
          dslContext
              .select(ROLE_ID, DSL.count())
              .from(ORGANIZATION_USERS)
              .where(ORGANIZATION_ID.eq(organizationId))
              .groupBy(ROLE_ID)
              .fetchMap(ROLE_ID, DSL.count())
        }

    // The query won't return rows for roles that have no users, but we want to include them in the
    // return value with a count of 0.
    return Role.entries.associateWith { countByRoleId[it] ?: 0 }
  }

  /** Fetches the Terraformation Contact role user in an organization, if one exists. */
  fun fetchTerraformationContact(organizationId: OrganizationId): UserId? {
    requirePermissions { listOrganizationUsers(organizationId) }
    return dslContext
        .select(ORGANIZATION_USERS.USER_ID)
        .from(ORGANIZATION_USERS)
        .where(ORGANIZATION_USERS.ORGANIZATION_ID.eq(organizationId))
        .and(ORGANIZATION_USERS.ROLE_ID.eq(Role.TerraformationContact))
        .orderBy(ORGANIZATION_USERS.USER_ID)
        .limit(1)
        .fetchOne(ORGANIZATION_USERS.USER_ID)
  }

  /**
   * If a user is an owner of an organization, ensures that the organization has other owners.
   *
   * Acquires row locks on [ORGANIZATION_USERS] for the organization's owners; call from within a
   * transaction.
   *
   * @throws CannotRemoveLastOwnerException The user is an owner and the organization has no other
   *   owners.
   * @throws UserNotFoundException The user is not a member of the organization.
   */
  private fun ensureOtherOwners(organizationId: OrganizationId, userId: UserId) {
    val currentRole =
        dslContext
            .select(ORGANIZATION_USERS.ROLE_ID)
            .from(ORGANIZATION_USERS)
            .where(ORGANIZATION_USERS.ORGANIZATION_ID.eq(organizationId))
            .and(ORGANIZATION_USERS.USER_ID.eq(userId))
            .forUpdate()
            .fetchOne(ORGANIZATION_USERS.ROLE_ID) ?: throw UserNotFoundException(userId)

    if (currentRole == Role.Owner) {
      val numOwners =
          dslContext
              .selectOne()
              .from(ORGANIZATION_USERS)
              .where(ORGANIZATION_USERS.ORGANIZATION_ID.eq(organizationId))
              .and(ORGANIZATION_USERS.ROLE_ID.eq(Role.Owner))
              .forUpdate()
              .fetch()
              .size

      if (numOwners < 2) {
        throw CannotRemoveLastOwnerException(organizationId)
      }
    }
  }

  /** Throws [IllegalArgumentException] if country and/or subdivision codes are invalid. */
  private fun validateCountryCode(countryCode: String?, countrySubdivisionCode: String?) {
    if (countryCode != null) {
      if (countryCode.length != 2) {
        throw IllegalArgumentException("Country code must be 2 characters long")
      }

      if (dslContext
          .selectOne()
          .from(COUNTRIES)
          .where(COUNTRIES.CODE.eq(countryCode.uppercase()))
          .fetch()
          .isEmpty()) {
        throw IllegalArgumentException("Country code not recognized")
      }
    }
    if (countrySubdivisionCode != null) {
      if (countryCode == null) {
        throw IllegalArgumentException("Cannot set country subdivision code without country code")
      }

      if (!countrySubdivisionCode.startsWith("$countryCode-")) {
        throw IllegalArgumentException("Country subdivision code must start with country code")
      }

      if (countrySubdivisionCode.length < 4 || countrySubdivisionCode.length > 6) {
        throw IllegalArgumentException(
            "Country subdivision code must be between 4 and 6 characters")
      }

      if (dslContext
          .selectOne()
          .from(COUNTRY_SUBDIVISIONS)
          .where(COUNTRY_SUBDIVISIONS.CODE.eq(countrySubdivisionCode.uppercase()))
          .fetch()
          .isEmpty()) {
        throw IllegalArgumentException("Country subdivision code not recognized")
      }
    }
  }

  /**
   * Throws [IllegalArgumentException] if non-empty organization details are missing when type is
   * 'Other' or present when type is not 'Other'.
   */
  private fun validateOrganizationType(
      organizationType: OrganizationType?,
      organizationTypeDetails: String?
  ) {
    if (organizationTypeDetails.isNullOrBlank() && OrganizationType.Other == organizationType) {
      throw IllegalArgumentException(
          "Organization details not provided for organization type 'Other'")
    }
    if (!organizationTypeDetails.isNullOrBlank() && OrganizationType.Other != organizationType) {
      throw IllegalArgumentException(
          "Organization details provided for organization type that is not 'Other'")
    }
  }

  private fun getUserRole(organizationId: OrganizationId, userId: UserId): Role? =
      dslContext
          .select(ORGANIZATION_USERS.ROLE_ID)
          .from(ORGANIZATION_USERS)
          .where(ORGANIZATION_USERS.ORGANIZATION_ID.eq(organizationId))
          .and(ORGANIZATION_USERS.USER_ID.eq(userId))
          .fetchOne(ORGANIZATION_USERS.ROLE_ID)

  private fun getUserEmail(userId: UserId): String =
      dslContext.select(USERS.EMAIL).from(USERS).where(USERS.ID.eq(userId)).fetchOne(USERS.EMAIL)
          ?: throw UserNotFoundException(userId)

  private fun validateTerraformationContactEmail(userId: UserId) =
      getUserEmail(userId).let { it ->
        if (!it.endsWith(suffix = "@terraformation.com", ignoreCase = true)) {
          throw InvalidTerraformationContactEmail(it)
        }
      }

  enum class FetchDepth(val level: Int) {
    Organization(1),
    Facility(2)
  }
}
