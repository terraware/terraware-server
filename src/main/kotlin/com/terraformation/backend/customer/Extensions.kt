package com.terraformation.backend.customer

import com.terraformation.backend.db.default_schema.ProjectInternalRole

val TF_CONTACT_PROJECT_ROLES =
    setOf(ProjectInternalRole.ProjectLead, ProjectInternalRole.RestorationLead)

fun ProjectInternalRole.shouldBeTfContact(): Boolean {
  return this in TF_CONTACT_PROJECT_ROLES
}
