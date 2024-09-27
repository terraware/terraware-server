package com.terraformation.backend.accelerator

import java.time.Duration

/** Lead time for module event STARTING_SOON and notifications state */
val MODULE_EVENT_NOTIFICATION_LEAD_TIME: Duration = Duration.ofMinutes(15)

/** Suffix added to the project's file naming when creating new folders for their files. */
const val INTERNAL_FOLDER_SUFFIX = " [Internal]"
