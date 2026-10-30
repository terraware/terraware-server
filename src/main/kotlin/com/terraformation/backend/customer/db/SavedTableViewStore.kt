package com.terraformation.backend.customer.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.SavedTableViewNameExistsException
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
