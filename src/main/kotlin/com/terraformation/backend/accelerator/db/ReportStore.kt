package com.terraformation.backend.accelerator.db

import jakarta.inject.Named
import java.time.InstantSource
import org.jooq.DSLContext

@Named
class ReportStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
) {


}