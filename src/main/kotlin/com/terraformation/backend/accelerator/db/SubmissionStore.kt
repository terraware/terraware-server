package com.terraformation.backend.accelerator.db

import jakarta.inject.Named
import java.time.InstantSource
import org.jooq.DSLContext

@Named
class SubmissionStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
) {
  /** Inserts a new document for a submission. This calculates the filename */
}
