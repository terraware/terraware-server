package com.terraformation.backend

import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestPlan
import org.springframework.context.ApplicationContext
import org.springframework.context.ConfigurableApplicationContext

/**
 * Workaround for a race between the Spring and Testcontainers JVM shutdown hooks.
 *
 * JVM shutdown hooks are executed in an undefined order on an undefined number of threads. If the
 * Testcontainers hook runs first and stops the database container, and there are Spring services
 * (such as JobRunr) that try to update the database as part of their shutdown processes, the Spring
 * services can fail to shut down cleanly once the Spring shutdown hook runs. This causes error spew
 * at the end of the test run and can cause the suite to take longer to finish because it has to
 * wait for the shutdown to time out.
 *
 * The workaround is to shut down the Spring application contexts before the JVM exits. By the time
 * the Testcontainers hook stops the database container, nothing is using the database any more.
 */
class SpringShutdownListener : TestExecutionListener {
  companion object {
    private val springContexts = mutableSetOf<ConfigurableApplicationContext>()

    fun register(applicationContext: ApplicationContext) {
      if (applicationContext is ConfigurableApplicationContext) {
        synchronized(springContexts) { springContexts.add(applicationContext) }
      }
    }
  }

  override fun testPlanExecutionFinished(testPlan: TestPlan) {
    springContexts.forEach { it.close() }
  }
}
