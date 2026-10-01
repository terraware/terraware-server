package com.terraformation.backend.db

import jakarta.inject.Named
import java.time.Duration
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
   * Attempts to acquire an exclusive lock on one entity of a given type, e.g., one planting site.
   * If acquired, the lock is held until the current transaction is committed or rolled back. Does
   * not block waiting for the lock to become available.
   *
   * The entity ID is folded into 32 bits, so two entities can share a lock. That makes this
   * suitable for work that can safely be skipped and retried later, but not for mutual exclusion
   * that must never block unrelated entities.
   *
   * @return `true` if the lock was successfully acquired. `false` if the lock was already held.
   */
  fun tryExclusiveTransactional(lockType: LockType, entityId: Long): Boolean {
    return dslContext
        .select(
            DSL.function(
                "pg_try_advisory_xact_lock",
                Boolean::class.java,
                DSL.value(lockType.key.toInt()),
                DSL.value(foldEntityId(entityId)),
            )
        )
        .fetchOne()
        ?.value1() == true
  }

  /**
   * Asks the database sessions holding a lock acquired with [tryExclusiveTransactional] to cancel
   * whatever statement they're running. A transaction whose statement is canceled fails and
   * releases the lock when it's rolled back. Has no effect if a holder is between statements when
   * this is called, so callers that need the lock should keep trying.
   *
   * @return `true` if any session held the lock.
   */
  fun cancelExclusiveTransactionalHolders(lockType: LockType, entityId: Long): Boolean {
    return dslContext
        .resultQuery(
            """
            SELECT pg_cancel_backend(pid)
            FROM pg_locks
            WHERE locktype = 'advisory'
              AND classid::bigint = ?
              AND objid::bigint = ?
              AND objsubid = 2
              AND granted
              AND pid <> pg_backend_pid()
            """
                .trimIndent(),
            lockType.key.toInt().toLong(),
            foldEntityId(entityId).toLong() and 0xffffffffL,
        )
        .fetch()
        .isNotEmpty
  }

  /**
   * Makes the current transaction fail, rather than wait, if it has to wait longer than [timeout]
   * for any row or table lock. Applies until the end of the current transaction.
   */
  fun setTransactionLockTimeout(timeout: Duration) {
    dslContext.execute("SET LOCAL lock_timeout = '${timeout.toMillis()}ms'")
  }

  private fun foldEntityId(entityId: Long): Int = (entityId xor (entityId ushr 32)).toInt()

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
