package com.terraformation.backend

import com.terraformation.backend.db.initializeJooqKeys
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestPlan

/**
 * Initializes jOOQ's generated key classes before tests start running in parallel. See
 * [initializeJooqKeys] for why this is needed.
 */
class JooqKeysInitializationListener : TestExecutionListener {
  override fun testPlanExecutionStarted(testPlan: TestPlan) {
    initializeJooqKeys()
  }
}
