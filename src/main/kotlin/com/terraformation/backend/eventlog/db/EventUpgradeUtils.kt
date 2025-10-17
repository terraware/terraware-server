package com.terraformation.backend.eventlog.db

import jakarta.inject.Named
import org.jooq.DSLContext

@Named
class EventUpgradeUtils(
    val dslContext: DSLContext,
)
