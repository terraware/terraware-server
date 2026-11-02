package com.terraformation.backend.customer.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.SavedTableViewModel
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.SavedTableViewNameExistsException
import com.terraformation.backend.db.SavedTableViewNotFoundException
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SavedTableViewId
import com.terraformation.backend.db.default_schema.tables.references.SAVED_TABLE_VIEWS
import jakarta.inject.Named
import org.jooq.DSLContext
import org.jooq.JSONB
import org.springframework.dao.DuplicateKeyException

@Named
class SavedTableViewStore(private val dslContext: DSLContext) {
  /**
   * Returns one of the current user's saved table views.
   *
   * @throws SavedTableViewNotFoundException The view doesn't exist, belongs to another user, or is
   *   specific to an organization the user can no longer read.
   */
  fun fetchOneById(savedTableViewId: SavedTableViewId): SavedTableViewModel {
    val model =
        dslContext
            .selectFrom(SAVED_TABLE_VIEWS)
            .where(SAVED_TABLE_VIEWS.ID.eq(savedTableViewId))
            .and(SAVED_TABLE_VIEWS.USER_ID.eq(currentUser().userId))
            .fetchOne { SavedTableViewModel.of(it) }
            ?: throw SavedTableViewNotFoundException(savedTableViewId)

    if (model.organizationId != null && !currentUser().canReadOrganization(model.organizationId)) {
      throw SavedTableViewNotFoundException(savedTableViewId)
    }

    return model
  }

  /**
   * Returns the current user's saved views for a table, ordered by name.
   *
   * @param organizationId If non-null, returns the views specific to this organization. If null,
   *   returns the user's global views.
   */
  fun fetchByTableName(
      organizationId: OrganizationId?,
      tableName: String,
  ): List<SavedTableViewModel> {
    requirePermissions { organizationId?.let { readOrganization(it) } }

    return dslContext
        .selectFrom(SAVED_TABLE_VIEWS)
        .where(SAVED_TABLE_VIEWS.USER_ID.eq(currentUser().userId))
        .and(SAVED_TABLE_VIEWS.TABLE_NAME.eq(tableName))
        .and(
            if (organizationId != null) {
              SAVED_TABLE_VIEWS.ORGANIZATION_ID.eq(organizationId)
            } else {
              SAVED_TABLE_VIEWS.ORGANIZATION_ID.isNull
            }
        )
        .orderBy(SAVED_TABLE_VIEWS.NAME, SAVED_TABLE_VIEWS.ID)
        .fetch { SavedTableViewModel.of(it) }
  }

  /**
   * Creates a saved table view owned by the current user.
   *
   * @param organizationId If non-null, the view is specific to this organization. If null, the view
   *   is global to the user.
   * @param baseView Client-defined identifier of the view this view is based on, if any. This can't
   *   be changed once the view is created.
   * @throws SavedTableViewNameExistsException The user already has a view with the same name for
   *   the same table and organization.
   */
  fun create(
      organizationId: OrganizationId?,
      tableName: String,
      name: String,
      settings: JSONB,
      baseView: String? = null,
  ): SavedTableViewId {
    requirePermissions { organizationId?.let { readOrganization(it) } }

    try {
      return dslContext
          .insertInto(SAVED_TABLE_VIEWS)
          .set(SAVED_TABLE_VIEWS.BASE_VIEW, baseView)
          .set(SAVED_TABLE_VIEWS.NAME, name)
          .set(SAVED_TABLE_VIEWS.ORGANIZATION_ID, organizationId)
          .set(SAVED_TABLE_VIEWS.SETTINGS, settings)
          .set(SAVED_TABLE_VIEWS.TABLE_NAME, tableName)
          .set(SAVED_TABLE_VIEWS.USER_ID, currentUser().userId)
          .returningResult(SAVED_TABLE_VIEWS.ID)
          .fetchOne(SAVED_TABLE_VIEWS.ID)!!
    } catch (e: DuplicateKeyException) {
      throw SavedTableViewNameExistsException(name)
    }
  }
}
