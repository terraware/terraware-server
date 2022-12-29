package com.terraformation.backend.db

import javax.inject.Named
import org.jooq.DSLContext
import org.jooq.impl.DSL

/**
 * Manages service-wide locks. Since multiple instances of the server are running at once in
 * production, it's not sufficient to use in-memory locking mechanisms for operations where we need
 * to guarantee exclusive access to something.
 *
 * The current implementation uses
 * [PostgreSQL advisory locks](https://www.postgresql.org/docs/current/explicit-locking.html#ADVISORY-LOCKS)
 * which are lightweight and can participate in transactions.
 */
@Named
class LockService(private val dslContext: DSLContext) {
  /**
   * Acquires an exclusive lock on the given key. The lock is held until the current transaction is
   * committed or rolled back. Blocks until the lock is acquired.
   */
  fun lockExclusiveTransactional(lockType: LockType) {
    lockBlocking("pg_advisory_xact_lock", lockType)
  }

  /**
   * Acquires an exclusive lock on the given key. The lock is held until it is released or the
   * current database session is closed.
   *
   * Recommended usage:
   * ```
   * lockService.lockExclusiveNonTransactional(key).use { ... }
   * ```
   *
   * @return An object that can be closed to release the lock; you'll typically want to use the
   *   [AutoCloseable.use] extension method to ensure the lock is always properly released.
   */
  fun lockExclusiveNonTransactional(lockType: LockType): AutoCloseable {
    lockBlocking("pg_advisory_lock", lockType)
    return HeldLock(lockType)
  }

  /**
   * Attempts to acquire an exclusive lock on the given key. If acquired, the lock is held until the
   * current transaction is committed or rolled back. Does not block waiting for the lock to become
   * available.
   *
   * @return `true` if the lock was successfully acquired. `false` if the lock was already held.
   */
  fun tryExclusiveTransactional(lockType: LockType): Boolean {
    return lockNonBlocking("pg_try_advisory_xact_lock", lockType)
  }

  /**
   * Attempts to acquire an exclusive lock on the given key. If acquired, the lock is held until it
   * is released or the current database session is closed.
   *
   * Recommended usage:
   * ```
   * lockService.tryExclusiveLockNonTransactional(key)
   *     ?.use { ... }
   *     ?: handleFailureToAcquireLock()
   * ```
   *
   * @return An object that can be closed to release the lock.
   */
  fun tryExclusiveNonTransactional(lockType: LockType): AutoCloseable? {
    return if (lockNonBlocking("pg_try_advisory_lock", lockType)) {
      HeldLock(lockType)
    } else {
      null
    }
  }

  private fun lockBlocking(funcName: String, lockType: LockType) {
    dslContext.select(DSL.function(funcName, Void::class.java, DSL.value(lockType.key))).fetch()
  }

  private fun lockNonBlocking(funcName: String, lockType: LockType): Boolean {
    return dslContext
        .select(DSL.function(funcName, Boolean::class.java, DSL.value(lockType.key)))
        .fetchOne()
        ?.value1() == true
  }

  private inner class HeldLock(private val lockType: LockType) : AutoCloseable {
    override fun close() {
      if (!lockNonBlocking("pg_advisory_unlock", lockType)) {
        throw IllegalStateException("Lock $lockType was not held; cannot release it.")
      }
    }
  }
}
