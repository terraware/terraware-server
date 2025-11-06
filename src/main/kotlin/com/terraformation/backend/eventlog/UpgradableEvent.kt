package com.terraformation.backend.eventlog

import com.terraformation.backend.db.default_schema.EventLogId
import com.terraformation.backend.eventlog.db.EventUpgradeUtils

/** Interface implemented by old versions of persistent events. */
interface UpgradableEvent : PersistentEvent {
  /**
   * Converts this event to a newer version. Typically this will be the next version (hence the
   * method name) but skipping versions is allowed.
   *
   * For example, if this event class is `RandomEventV3`, [toNextVersion] would typically return
   * `RandomEventV4`.
   *
   * @param eventLogId ID of the event being upgraded. This can be used to, e.g., scan for previous
   *   events of the same type.
   */
  fun toNextVersion(eventLogId: EventLogId, eventUpgradeUtils: EventUpgradeUtils): PersistentEvent
}
