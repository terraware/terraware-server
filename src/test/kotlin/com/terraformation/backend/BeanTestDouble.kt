package com.terraformation.backend

import org.springframework.context.event.EventListener
import org.springframework.test.context.event.AfterTestExecutionEvent

/**
 * A Spring bean that acts as a test double. This adds an event listener that resets the bean's
 * state after each test to isolate test cases from each other.
 *
 * Implementations should be annotated with `@Named` and possibly with `@Priority` if they replace
 * an application bean that has its own `@Priority` annotation.
 */
interface BeanTestDouble {
  /**
   * Resets this test double's state. This can be called programmatically but is also called after
   * each test method is run.
   */
  fun resetState()

  /**
   * Resets the data after each test method when running as a Spring-managed bean. This is needed
   * for test isolation; otherwise users added in one test would be visible in another.
   */
  @EventListener
  fun clearAfterTest(event: AfterTestExecutionEvent) {
    resetState()
  }
}
