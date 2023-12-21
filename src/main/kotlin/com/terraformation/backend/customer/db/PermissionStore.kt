package com.terraformation.backend.customer.db

import com.terraformation.backend.customer.model.IndividualUser
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.references.FACILITIES
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATION_USERS
import com.terraformation.backend.db.default_schema.tables.references.USERS
import com.terraformation.backend.db.default_schema.tables.references.USER_GLOBAL_ROLES
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
            ORGANIZATION_USERS.ROLE_ID.asNonNullable())
  }
}
