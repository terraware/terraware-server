package com.terraformation.backend.customer.db

import com.terraformation.backend.customer.model.IndividualUser
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.references.FACILITIES
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATION_USERS
import com.terraformation.backend.db.default_schema.tables.references.USERS
import com.terraformation.backend.db.default_schema.tables.references.USER_GLOBAL_ROLES
import com.terraformation.backend.db.funder.FundingEntityId
import com.terraformation.backend.db.funder.tables.references.FUNDING_ENTITY_PROJECTS
import com.terraformation.backend.db.funder.tables.references.FUNDING_ENTITY_USERS
import jakarta.inject.Named
import org.jooq.DSLContext

/**
 * Stores and retrieves user permission information.
 *
 * If you want to read the current user's roles, you will usually want to use the properties on
 * [IndividualUser] instead of the `fetch` methods on this class.
 */
@Named
class PermissionStore(private val dslContext: DSLContext) {
  /**
   * Returns a user's role in each facility under the organizations they're in. The roles are
   * organization-level, so will be the same across all facilities of a given organization.
   */
  fun fetchFacilityRoles(userId: UserId): Map<FacilityId, Role> {
    // Users have read access to all facilities under projects they're a member of, regardless
    // of role. Admins have access to all facilities
    return dslContext
        .select(FACILITIES.ID, ORGANIZATION_USERS.ROLE_ID)
        .from(ORGANIZATION_USERS)
        .join(USERS)
        .on(ORGANIZATION_USERS.USER_ID.eq(USERS.ID))
        .join(FACILITIES)
        .on(ORGANIZATION_USERS.ORGANIZATION_ID.eq(FACILITIES.ORGANIZATION_ID))
        .where(ORGANIZATION_USERS.USER_ID.eq(userId))
        .fetchMap(FACILITIES.ID.asNonNullable(), ORGANIZATION_USERS.ROLE_ID.asNonNullable())
  }

  /** Returns a user's funding entity */
  fun fetchFundingEntity(userId: UserId): FundingEntityId? {
    return dslContext
        .select(FUNDING_ENTITY_USERS.FUNDING_ENTITY_ID)
        .from(FUNDING_ENTITY_USERS)
        .where(FUNDING_ENTITY_USERS.USER_ID.eq(userId))
        .fetchOne { it[FUNDING_ENTITY_USERS.FUNDING_ENTITY_ID] }
  }

  /** Returns a user's global roles. These roles are not tied to organizations. */
  fun fetchGlobalRoles(userId: UserId): Set<GlobalRole> {
    return dslContext
        .select(USER_GLOBAL_ROLES.GLOBAL_ROLE_ID)
        .from(USER_GLOBAL_ROLES)
        .where(USER_GLOBAL_ROLES.USER_ID.eq(userId))
        .fetchSet(USER_GLOBAL_ROLES.GLOBAL_ROLE_ID.asNonNullable())
  }

  /** Returns a user's role in each of their organizations. */
  fun fetchOrganizationRoles(userId: UserId): Map<OrganizationId, Role> {
    return dslContext
        .select(ORGANIZATION_USERS.ORGANIZATION_ID, ORGANIZATION_USERS.ROLE_ID)
        .from(ORGANIZATION_USERS)
        .where(ORGANIZATION_USERS.USER_ID.eq(userId))
        .fetchMap(
            ORGANIZATION_USERS.ORGANIZATION_ID.asNonNullable(),
            ORGANIZATION_USERS.ROLE_ID.asNonNullable(),
        )
  }

  /**
   * Returns the IDs of the projects associated with a user's funding entity. This is only relevant
   * for funder users.
   */
  fun fetchFundingEntityProjects(userId: UserId): Set<ProjectId> {
    return dslContext
        .select(FUNDING_ENTITY_PROJECTS.PROJECT_ID)
        .from(FUNDING_ENTITY_USERS)
        .join(FUNDING_ENTITY_PROJECTS)
        .on(FUNDING_ENTITY_USERS.FUNDING_ENTITY_ID.eq(FUNDING_ENTITY_PROJECTS.FUNDING_ENTITY_ID))
        .where(FUNDING_ENTITY_USERS.USER_ID.eq(userId))
        .fetchSet(FUNDING_ENTITY_PROJECTS.PROJECT_ID.asNonNullable())
  }
}
