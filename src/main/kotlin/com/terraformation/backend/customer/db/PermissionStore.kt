package com.terraformation.backend.customer.db

import com.terraformation.backend.customer.model.Role
import com.terraformation.backend.customer.model.UserModel
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.UserType
import com.terraformation.backend.db.tables.references.FACILITIES
import com.terraformation.backend.db.tables.references.ORGANIZATION_USERS
import com.terraformation.backend.db.tables.references.PROJECTS
import com.terraformation.backend.db.tables.references.PROJECT_USERS
import com.terraformation.backend.db.tables.references.SITES
import com.terraformation.backend.db.tables.references.USERS
import javax.annotation.ManagedBean
import org.jooq.DSLContext

/**
 * Stores and retrieves user permission information.
 *
 * If you want to read the current user's roles, you will usually want to use the properties on
 * [UserModel] instead of the `fetch` methods on this class.
 */
@ManagedBean
class PermissionStore(private val dslContext: DSLContext) {
  /**
   * Returns a user's role in each facility under the projects they're in.
   *
   * If the user is an owner or admin in an organization, they are treated as being a member of all
   * the organization's projects.
   *
   * Currently, we don't support per-project roles, so this is the user's role in the project's
   * organization for each project that they're a member of.
   */
  fun fetchFacilityRoles(userId: UserId): Map<FacilityId, Role> {
    // Users have read access to all facilities under projects they're a member of, regardless
    // of role. Admins have access to all facilities
    return dslContext
        .select(FACILITIES.ID, ORGANIZATION_USERS.ROLE_ID)
        .from(ORGANIZATION_USERS)
        .join(USERS)
        .on(ORGANIZATION_USERS.USER_ID.eq(USERS.ID))
        .join(PROJECTS)
        .on(ORGANIZATION_USERS.ORGANIZATION_ID.eq(PROJECTS.ORGANIZATION_ID))
        .join(SITES)
        .on(PROJECTS.ID.eq(SITES.PROJECT_ID))
        .join(FACILITIES)
        .on(SITES.ID.eq(FACILITIES.SITE_ID))
        .leftJoin(PROJECT_USERS)
        .on(PROJECTS.ID.eq(PROJECT_USERS.PROJECT_ID))
        .and(ORGANIZATION_USERS.USER_ID.eq(PROJECT_USERS.USER_ID))
        .where(ORGANIZATION_USERS.USER_ID.eq(userId))
        .and(
            ORGANIZATION_USERS
                .ROLE_ID
                .`in`(Role.OWNER.id, Role.ADMIN.id)
                .or(PROJECT_USERS.USER_ID.isNotNull)
                .or(PROJECTS.ORGANIZATION_WIDE.isTrue)
                .or(USERS.USER_TYPE_ID.eq(UserType.APIClient)))
        .fetchMap({ row -> row.value1() }, { row -> row.value2()?.let { Role.of(it) } })
  }

  /** Returns a user's role in each of their organizations. */
  fun fetchOrganizationRoles(userId: UserId): Map<OrganizationId, Role> {
    return dslContext
        .select(ORGANIZATION_USERS.ORGANIZATION_ID, ORGANIZATION_USERS.ROLE_ID)
        .from(ORGANIZATION_USERS)
        .where(ORGANIZATION_USERS.USER_ID.eq(userId))
        .fetchMap({ row -> row.value1() }, { row -> row.value2()?.let { Role.of(it) } })
  }

  /**
   * Returns a user's role in each of their projects.
   *
   * If the user is an owner or admin in an organization, they are treated as being a member of all
   * the organization's projects.
   *
   * Currently, we don't support per-project roles, so this is the user's role in the project's
   * organization for each project that they're a member of.
   */
  fun fetchProjectRoles(userId: UserId): Map<ProjectId, Role> {
    return dslContext
        .select(PROJECTS.ID, ORGANIZATION_USERS.ROLE_ID)
        .from(ORGANIZATION_USERS)
        .join(USERS)
        .on(ORGANIZATION_USERS.USER_ID.eq(USERS.ID))
        .join(PROJECTS)
        .on(ORGANIZATION_USERS.ORGANIZATION_ID.eq(PROJECTS.ORGANIZATION_ID))
        .leftJoin(PROJECT_USERS)
        .on(PROJECTS.ID.eq(PROJECT_USERS.PROJECT_ID))
        .and(ORGANIZATION_USERS.USER_ID.eq(PROJECT_USERS.USER_ID))
        .where(ORGANIZATION_USERS.USER_ID.eq(userId))
        .and(
            ORGANIZATION_USERS
                .ROLE_ID
                .`in`(Role.OWNER.id, Role.ADMIN.id)
                .or(PROJECT_USERS.USER_ID.isNotNull)
                .or(PROJECTS.ORGANIZATION_WIDE.isTrue)
                .or(USERS.USER_TYPE_ID.eq(UserType.APIClient)))
        .fetchMap({ row -> row.value1() }, { row -> row.value2()?.let { Role.of(it) } })
  }

  /**
   * Returns a user's role in each site under the projects they're in.
   *
   * If the user is an owner or admin in an organization, they are treated as being a member of all
   * the organization's projects.
   *
   * Currently, we don't support per-project or per-site roles, so this is the user's role in the
   * organization that owns all the sites under each of the projects the user is in.
   */
  fun fetchSiteRoles(userId: UserId): Map<SiteId, Role> {
    return dslContext
        .select(SITES.ID, ORGANIZATION_USERS.ROLE_ID)
        .from(ORGANIZATION_USERS)
        .join(USERS)
        .on(ORGANIZATION_USERS.USER_ID.eq(USERS.ID))
        .join(PROJECTS)
        .on(ORGANIZATION_USERS.ORGANIZATION_ID.eq(PROJECTS.ORGANIZATION_ID))
        .join(SITES)
        .on(PROJECTS.ID.eq(SITES.PROJECT_ID))
        .leftJoin(PROJECT_USERS)
        .on(PROJECTS.ID.eq(PROJECT_USERS.PROJECT_ID))
        .and(ORGANIZATION_USERS.USER_ID.eq(PROJECT_USERS.USER_ID))
        .where(ORGANIZATION_USERS.USER_ID.eq(userId))
        .and(
            ORGANIZATION_USERS
                .ROLE_ID
                .`in`(Role.OWNER.id, Role.ADMIN.id)
                .or(PROJECT_USERS.USER_ID.isNotNull)
                .or(PROJECTS.ORGANIZATION_WIDE.isTrue)
                .or(USERS.USER_TYPE_ID.eq(UserType.APIClient)))
        .fetchMap({ row -> row.value1() }, { row -> row.value2()?.let { Role.of(it) } })
  }

  private companion object {
    @Suppress("unused")
    fun dummyFunctionToImportSymbolsReferredToInComments(): UserModel? {
      return null
    }
  }
}
