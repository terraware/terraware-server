package com.terraformation.backend.eventlog

import com.terraformation.backend.eventlog.db.EventUpgradeUtils

/** Interface implemented by old versions of persistent events. */
interface UpgradableEvent : PersistentEvent {
  /**
   * Converts this event to a newer version. Typically this will be the next version (hence the
   * method name) but skipping versions is allowed.
   *
   * For example, if this event class is `RandomEventV3`, [toNextVersion] would typically return
   * `RandomEventV4`.
   */
  fun toNextVersion(eventUpgradeUtils: EventUpgradeUtils): PersistentEvent
}
