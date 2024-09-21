package com.terraformation.backend.documentproducer.event

import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.docprod.DocumentId
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.ratelimit.RateLimitedEvent
import java.time.Duration

/**
 * Published when a variable that's referenced in a section that's marked as completed, or in a
 * child section that's marked as completed, is updated.
 */
data class CompletedSectionVariableUpdatedEvent(
    val documentId: DocumentId,
    val projectId: ProjectId,
    /** The section that references the updated variable. */
    val referencingSectionVariableId: VariableId,
    /**
     * The section that was affected. This may be a parent of the section that actually references
     * the updated variable.
     */
    val sectionVariableId: VariableId,
) : RateLimitedEvent<CompletedSectionVariableUpdatedEvent> {
  override fun getRateLimitKey(): Any {
    // Rate limit on the affected section, not the referencing section, so that parent section
    // owners don't get repeatedly notified if a variable is referenced in multiple child sections.
    return mapOf("documentId" to documentId, "sectionVariableId" to sectionVariableId)
  }

  override fun getMinimumInterval(): Duration {
    return Duration.ofMinutes(10)
  }
}
