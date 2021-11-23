package com.terraformation.backend.customer.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.FacilityModel
import com.terraformation.backend.customer.model.OrganizationModel
import com.terraformation.backend.customer.model.OrganizationUserModel
import com.terraformation.backend.customer.model.ProjectModel
import com.terraformation.backend.customer.model.Role
import com.terraformation.backend.customer.model.SiteModel
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.customer.model.toModel
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.SRID
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.forMultiset
import com.terraformation.backend.db.tables.daos.OrganizationsDao
import com.terraformation.backend.db.tables.pojos.OrganizationsRow
import com.terraformation.backend.db.tables.references.FACILITIES
import com.terraformation.backend.db.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.tables.references.ORGANIZATION_USERS
import com.terraformation.backend.db.tables.references.PROJECTS
import com.terraformation.backend.db.tables.references.PROJECT_USERS
import com.terraformation.backend.db.tables.references.SITES
import com.terraformation.backend.db.tables.references.USERS
import com.terraformation.backend.db.transformSrid
import com.terraformation.backend.log.perClassLogger
import java.time.Clock
import javax.annotation.ManagedBean
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.security.access.AccessDeniedException

@ManagedBean
class OrganizationStore(
    private val clock: Clock,
    private val dslContext: DSLContext,
    private val organizationsDao: OrganizationsDao
) {
  private val log = perClassLogger()

  /** Returns all the organizations the user has access to. */
  fun fetchAll(depth: FetchDepth = FetchDepth.Organization): List<OrganizationModel> {
    return selectForDepth(depth)
  }

  fun fetchById(
      organizationId: OrganizationId,
      depth: FetchDepth = FetchDepth.Organization
  ): OrganizationModel? {
    val user = currentUser()

    return if (organizationId in user.organizationRoles) {
      selectForDepth(depth, ORGANIZATIONS.ID.eq(organizationId)).firstOrNull()
    } else {
      log.warn("User ${user.userId} attempted to fetch organization $organizationId")
      null
    }
  }

  private fun selectForDepth(
      depth: FetchDepth,
      condition: Condition? = null
  ): List<OrganizationModel> {
    val user = currentUser()
    val organizationIds = user.organizationRoles.keys

    if (organizationIds.isEmpty()) {
      return emptyList()
    }

    val facilitiesMultiset =
        if (depth.level >= FetchDepth.Facility.level) {
          DSL.multiset(
                  DSL.select(FACILITIES.asterisk())
                      .from(FACILITIES)
                      .where(FACILITIES.SITE_ID.eq(SITES.ID))
                      .orderBy(FACILITIES.ID))
              .convertFrom { result -> result.map { FacilityModel(it) } }
        } else {
          DSL.value(null as List<FacilityModel>?)
        }

    val sitesMultiset =
        if (depth.level >= FetchDepth.Site.level) {
          DSL.multiset(
                  DSL.select(
                          SITES.CREATED_TIME,
                          SITES.ENABLED,
                          SITES.ID,
                          SITES.LOCALE,
                          SITES.MODIFIED_TIME,
                          SITES.NAME,
                          SITES.PROJECT_ID,
                          SITES.TIMEZONE,
                          SITES
                              .LOCATION
                              .transformSrid(SRID.LONG_LAT)
                              .forMultiset()
                              .`as`(SITES.LOCATION),
                          facilitiesMultiset)
                      .from(SITES)
                      .where(SITES.PROJECT_ID.eq(PROJECTS.ID))
                      .orderBy(SITES.ID))
              .convertFrom { result ->
                result.map { record -> SiteModel(record, facilitiesMultiset) }
              }
        } else {
          DSL.value(null as List<SiteModel>?)
        }

    val projectsMultiset =
        if (depth.level >= FetchDepth.Project.level) {
          val projectIds = user.projectRoles.keys

          // If the user isn't in any projects, we still want to construct a properly-typed
          // multiset, but it should be empty.
          val condition =
              if (projectIds.isNotEmpty()) {
                PROJECTS.ORGANIZATION_ID.eq(ORGANIZATIONS.ID).and(PROJECTS.ID.`in`(projectIds))
              } else {
                DSL.falseCondition()
              }

          DSL.multiset(
                  DSL.select(PROJECTS.asterisk(), sitesMultiset)
                      .from(PROJECTS)
                      .where(condition)
                      .orderBy(PROJECTS.ID))
              .convertFrom { result -> result.map { ProjectModel(it, sitesMultiset) } }
        } else {
          DSL.value(null as List<ProjectModel>?)
        }

    return dslContext
        .select(ORGANIZATIONS.asterisk(), projectsMultiset)
        .from(ORGANIZATIONS)
        .where(listOfNotNull(ORGANIZATIONS.ID.`in`(organizationIds), condition))
        .orderBy(ORGANIZATIONS.ID)
        .fetch { OrganizationModel(it, projectsMultiset) }
  }

  /** Creates a new organization and makes the current user an owner. */
  fun createWithAdmin(name: String): OrganizationModel {
    val row =
        OrganizationsRow(name = name, createdTime = clock.instant(), modifiedTime = clock.instant())
    organizationsDao.insert(row)

    with(ORGANIZATION_USERS) {
      dslContext
          .insertInto(ORGANIZATION_USERS)
          .set(ORGANIZATION_ID, row.id)
          .set(USER_ID, currentUser().userId)
          .set(ROLE_ID, Role.OWNER.id)
          .set(CREATED_TIME, clock.instant())
          .set(MODIFIED_TIME, clock.instant())
          .execute()
    }

    return row.toModel()
  }

  fun fetchUsers(organizationIds: Collection<OrganizationId>): List<OrganizationUserModel> {
    // Only admins and above can fetch an organization's user list.
    /*
    organizationIds.forEach { organizationId ->
      val role = currentUser().organizationRoles[organizationId]
      if (role != Role.OWNER && role != Role.ADMIN) {
        throw AccessDeniedException("No permission to list organization users")
      }
    }

     */

    return dslContext
        .select(
            USERS.ID,
            USERS.AUTH_ID,
            USERS.EMAIL,
            USERS.FIRST_NAME,
            USERS.LAST_NAME,
            USERS.USER_TYPE_ID,
            USERS.CREATED_TIME,
            ORGANIZATION_USERS.ORGANIZATION_ID,
            ORGANIZATION_USERS.ROLE_ID,
            PROJECT_USERS.PROJECT_ID)
        .from(ORGANIZATION_USERS)
        .join(USERS)
        .on(ORGANIZATION_USERS.USER_ID.eq(USERS.ID))
        .leftJoin(PROJECTS)
        .on(ORGANIZATION_USERS.ORGANIZATION_ID.eq(PROJECTS.ORGANIZATION_ID))
        .leftJoin(PROJECT_USERS)
        .on(PROJECTS.ID.eq(PROJECT_USERS.PROJECT_ID))
        .and(ORGANIZATION_USERS.USER_ID.eq(PROJECT_USERS.USER_ID))
        .where(ORGANIZATION_USERS.ORGANIZATION_ID.`in`(organizationIds))
        .fetchGroups(arrayOf(USERS.ID, ORGANIZATION_USERS.ORGANIZATION_ID))
        .mapNotNull { (_, rows) ->
          val firstRow = rows.first()
          val userId = firstRow[USERS.ID]
          val authId = firstRow[USERS.AUTH_ID]
          val userType = firstRow[USERS.USER_TYPE_ID]
          val createdTime = firstRow[USERS.CREATED_TIME]
          val email = firstRow[USERS.EMAIL]
          val orgId = firstRow[ORGANIZATION_USERS.ORGANIZATION_ID]
          val role = firstRow[ORGANIZATION_USERS.ROLE_ID]?.let { Role.of(it) }

          if (userId != null &&
              authId != null &&
              userType != null &&
              createdTime != null &&
              email != null &&
              orgId != null &&
              role != null) {
            OrganizationUserModel(
                userId,
                authId,
                email,
                firstRow[USERS.FIRST_NAME],
                firstRow[USERS.LAST_NAME],
                userType,
                createdTime,
                orgId,
                role,
                rows.mapNotNull { it[PROJECT_USERS.PROJECT_ID] })
          } else {
            log.error("Missing required fields for $userId: $rows")
            null
          }
        }
  }

  fun addUser(organizationId: OrganizationId, userId: UserId, role: Role) {
    requirePermissions {
      addOrganizationUser(organizationId)
      setOrganizationUserRole(organizationId, role)
    }

    with(ORGANIZATION_USERS) {
      dslContext
          .insertInto(ORGANIZATION_USERS)
          .set(ORGANIZATION_ID, organizationId)
          .set(USER_ID, userId)
          .set(ROLE_ID, role.id)
          .set(CREATED_TIME, clock.instant())
          .set(MODIFIED_TIME, clock.instant())
          .execute()
    }
  }

  /**
   * Removes a user from an organization. Also removes the user from all the organization's
   * projects.
   */
  fun removeUser(organizationId: OrganizationId, userId: UserId): Boolean {
    requirePermissions { removeOrganizationUser(organizationId) }

    var result = false

    dslContext.transaction { _ ->
      dslContext
          .deleteFrom(PROJECT_USERS)
          .where(PROJECT_USERS.USER_ID.eq(userId))
          .and(
              PROJECT_USERS.PROJECT_ID.`in`(
                  DSL.select(PROJECTS.ID)
                      .from(PROJECTS)
                      .where(PROJECTS.ORGANIZATION_ID.eq(organizationId))))
          .execute()

      val rowsDeleted =
          dslContext
              .deleteFrom(ORGANIZATION_USERS)
              .where(ORGANIZATION_USERS.ORGANIZATION_ID.eq(organizationId))
              .and(ORGANIZATION_USERS.USER_ID.eq(userId))
              .execute()

      result = rowsDeleted > 0
    }

    return result
  }

  fun setUserRole(organizationId: OrganizationId, userId: UserId, role: Role): Boolean {
    if (!currentUser().canSetOrganizationUserRole(organizationId, role)) {
      throw AccessDeniedException("No permission to set this role on an organization user")
    }

    val rowsUpdated =
        dslContext
            .update(ORGANIZATION_USERS)
            .set(ORGANIZATION_USERS.ROLE_ID, role.id)
            .set(ORGANIZATION_USERS.MODIFIED_TIME, clock.instant())
            .where(ORGANIZATION_USERS.ORGANIZATION_ID.eq(organizationId))
            .and(ORGANIZATION_USERS.USER_ID.eq(userId))
            .execute()

    return rowsUpdated > 0
  }

  enum class FetchDepth(val level: Int) {
    Organization(1),
    Project(2),
    Site(3),
    Facility(4)
  }
}
