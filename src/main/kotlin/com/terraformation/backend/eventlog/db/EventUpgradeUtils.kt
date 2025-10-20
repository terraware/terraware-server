package com.terraformation.backend.eventlog.db

import com.terraformation.backend.eventlog.UpgradableEvent
import jakarta.inject.Named
import org.jooq.DSLContext

/**
 * Support functions for upgrading events. If a new version of an event adds data that wasn't
 * included in the previous version, [UpgradableEvent.toNextVersion] needs to be able to pull the
 * data from the database. An instance of this class is passed to that method to allow it to do so.
 */
@Named
class EventUpgradeUtils(
    val dslContext: DSLContext,
) {
  // Add any accessor functions needed by UpgradableEvent.toNextVersion implementations.
}
