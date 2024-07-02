package org.springframework.test.context.transaction

import java.util.concurrent.ForkJoinPool
import org.jooq.DSLContext
import org.springframework.core.annotation.Order
import org.springframework.test.context.TestContext
import org.springframework.test.context.TestExecutionListener

/**
 * Removes database transactions from new threads before they run their first tests.
 *
 * This is a workaround for a bad interaction involving jOOQ, the jUnit parallel test runner, and
 * the Spring test framework that would otherwise cause random test failures. Here's what happens
 * without this class:
 * 1. jUnit sets up a [ForkJoinPool] to run tests in parallel.
 * 2. Spring's [TransactionContextHolder] creates a thread-local variable to track each test
 *    thread's currently open transaction. This variable is an [InheritableThreadLocal] meaning that
 *    if code on a test thread spawns a new thread of its own, e.g., to do some work in the
 *    background, it inherits the transaction.
 * 3. Before each database-backed test method is run, Spring's [TransactionalTestExecutionListener]
 *    checks whether there's already a transaction associated with the current thread; it throws an
 *    exception if so since transactions are supposed to have been cleared between tests.
 * 4. Spring's [TransactionalTestExecutionListener] begins a new transaction and stores its
 *    information in the thread-local variable in [TransactionContextHolder].
 * 5. As the test runs, the application code calls [DSLContext.transaction]. (This is fine even
 *    though there's already an open transaction.)
 * 6. In order to handle cases where transactions are running in asynchronous code such that the
 *    actual work is done in other threads, jOOQ runs the transactional code in a managed block
 *    using [ForkJoinPool.managedBlock], whose purpose is to prevent deadlocks by ensuring that if
 *    it's called from a pooled thread, the pool has spare capacity available.
 * 8. [ForkJoinPool.managedBlock] detects that it's running in the pool created by jUnit in step 1.
 * 9. If there are tests running in all the threads in the pool, which there will be once the test
 *    run has warmed up, it spawns a new thread as a spare.
 * 10. The new thread inherits the transaction context in [TransactionContextHolder] because the
 *     variable there is an [InheritableThreadLocal].
 * 11. The pool now has an additional thread available, and there's work queued up for the thread
 *     pool because there are still lots of tests remaining to be run, so a new test starts running
 *     on the newly-created thread.
 * 12. The sanity check from step 3 detects that there's a transaction associated with the new
 *     thread, and it throws an exception, marking the test as failed.
 *
 * The workaround is to explicitly clear the transaction context the first time a test runs on each
 * thread, such that the sanity check in [TransactionalTestExecutionListener] doesn't see the
 * inherited value. Subsequent tests on the same thread still get sanity-checked as normal.
 *
 * This class is under `org.springframework` rather than `com.terraformation` because
 * [TransactionContextHolder] is a package-private class.
 */
@Order(3000) // TransactionalTestExecutionListener is order 4000
class InheritedTransactionRemover : TestExecutionListener {
  private val transactionRemoved = ThreadLocal.withInitial { false }

  override fun beforeTestMethod(testContext: TestContext) {
    if (!transactionRemoved.get()) {
      TransactionContextHolder.removeCurrentTransactionContext()
      transactionRemoved.set(true)
    }
  }
}
