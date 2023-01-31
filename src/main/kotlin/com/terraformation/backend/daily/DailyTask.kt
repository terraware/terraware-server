package com.terraformation.backend.daily

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.db.DisableIfNoDatabase
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty

/**
 * Indicates that a bean class implements a daily task and should only be instantiated if daily
 * tasks are enabled.
 */
@ConditionalOnProperty(TerrawareServerConfig.DAILY_TASKS_ENABLED_PROPERTY, matchIfMissing = true)
@DisableIfNoDatabase
@Target(AnnotationTarget.CLASS)
annotation class DailyTask
