package com.terraformation.backend.documentproducer.db

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.UserNotFoundException
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.default_schema.tables.references.USERS
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.docprod.tables.references.VARIABLES
import com.terraformation.backend.db.docprod.tables.references.VARIABLE_OWNERS
import jakarta.inject.Named
import org.jooq.DSLContext

@Named
class VariableOwnerStore(
    private val dslContext: DSLContext,
) {
  fun listOwners(projectId: ProjectId): Map<VariableId, UserId> {
    requirePermissions { readInternalVariableWorkflowDetails(projectId) }

    return with(VARIABLE_OWNERS) {
      dslContext
          .select(VARIABLE_ID, OWNED_BY)
          .from(VARIABLE_OWNERS)
          .where(PROJECT_ID.eq(projectId))
          .fetchMap(VARIABLE_ID.asNonNullable(), OWNED_BY.asNonNullable())
    }
  }

  fun updateOwner(projectId: ProjectId, variableId: VariableId, ownedBy: UserId?) {
    requirePermissions { updateInternalVariableWorkflowDetails(projectId) }

    if (!dslContext.fetchExists(PROJECTS, PROJECTS.ID.eq(projectId))) {
      throw ProjectNotFoundException(projectId)
    }

    if (!dslContext.fetchExists(VARIABLES, VARIABLES.ID.eq(variableId))) {
      throw VariableNotFoundException(variableId)
    }

    if (ownedBy != null && !dslContext.fetchExists(USERS, USERS.ID.eq(ownedBy))) {
      throw UserNotFoundException(ownedBy)
    }

    with(VARIABLE_OWNERS) {
      if (ownedBy != null) {
        dslContext
            .insertInto(VARIABLE_OWNERS)
            .set(PROJECT_ID, projectId)
            .set(VARIABLE_ID, variableId)
            .set(OWNED_BY, ownedBy)
            .onConflict()
            .doUpdate()
            .set(OWNED_BY, ownedBy)
            .execute()
      } else {
        dslContext
            .deleteFrom(VARIABLE_OWNERS)
            .where(PROJECT_ID.eq(projectId))
            .and(VARIABLE_ID.eq(variableId))
            .execute()
      }
    }
  }
}
