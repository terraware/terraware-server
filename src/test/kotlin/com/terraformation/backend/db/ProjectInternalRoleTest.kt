package com.terraformation.backend.db

import com.terraformation.backend.customer.shouldBeTfContact
import com.terraformation.backend.db.default_schema.ProjectInternalRole
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class ProjectInternalRoleTest {

  @Test
  fun `role should be tf contact`() {
    assertTrue(
        ProjectInternalRole.ProjectLead.shouldBeTfContact(), "Project Lead should be TF Contact")
    assertTrue(
        ProjectInternalRole.RestorationLead.shouldBeTfContact(),
        "Restoration Lead should be TF Contact")
    assertFalse(
        ProjectInternalRole.Consultant.shouldBeTfContact(), "Consultant should not be TF Contact")
  }
}
