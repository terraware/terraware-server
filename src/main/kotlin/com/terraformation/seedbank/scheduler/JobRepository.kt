package com.terraformation.seedbank.scheduler

import com.terraformation.seedbank.db.tables.pojos.ScheduledJob
import com.terraformation.seedbank.db.tables.references.SCHEDULED_JOB
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.inject.Singleton
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.exception.DataAccessException

@Singleton
class JobRepository(private val dslContext: DSLContext) {
  var clock = Clock.systemUTC()!!

  fun insert(job: ScheduledJob): Long {
    with(SCHEDULED_JOB) {
      return dslContext
          .insertInto(SCHEDULED_JOB)
          .set(SCHEDULED_TIME, job.scheduledTime)
          .set(PAYLOAD_CLASS, job.payloadClass)
          .set(PAYLOAD_DATA, job.payloadData)
          .set(PAYLOAD_VERSION, job.payloadVersion)
          .returning(ID)
          .fetchOne()
          ?.id
          ?: throw DataAccessException("Unable to retrieve ID of scheduled job")
    }
  }

  fun insert(job: SerializedJob<String>, time: Instant): Long {
    with(SCHEDULED_JOB) {
      return dslContext
          .insertInto(SCHEDULED_JOB)
          .set(SCHEDULED_TIME, time.atOffset(ZoneOffset.UTC))
          .set(PAYLOAD_CLASS, job.className)
          .set(PAYLOAD_VERSION, job.version)
          .set(PAYLOAD_DATA, JSONB.jsonb(job.serialized))
          .returning(ID)
          .fetchOne()
          ?.id
          ?: throw DataAccessException("Unable to retrieve ID of scheduled job")
    }
  }

  fun delete(jobId: Long): Boolean {
    with(SCHEDULED_JOB) {
      val rowsDeleted = dslContext.deleteFrom(SCHEDULED_JOB).where(ID.eq(jobId)).execute()
      return rowsDeleted == 1
    }
  }

  fun recordFailure(jobId: Long, message: String, details: String? = null): Boolean {
    with(SCHEDULED_JOB) {
      val rowsUpdated =
          dslContext
              .update(SCHEDULED_JOB)
              .set(FAILURE_MESSAGE, message)
              .set(FAILURE_DETAILS, details)
              .execute()
      return rowsUpdated == 1
    }
  }

  fun markAsStarted(jobId: Long, time: Instant = clock.instant()): Boolean {
    with(SCHEDULED_JOB) {
      val rowsUpdated =
          dslContext
              .update(SCHEDULED_JOB)
              .set(STARTED_TIME, time.atOffset(ZoneOffset.UTC))
              .where(ID.eq(jobId))
              .and(STARTED_TIME.isNull)
              .execute()
      return rowsUpdated == 1
    }
  }

  fun fetchNew(knownIds: Collection<Long>): Collection<ScheduledJob> {
    with(SCHEDULED_JOB) {
      return dslContext
          .selectFrom(SCHEDULED_JOB)
          .where(STARTED_TIME.isNull)
          .and(ID.notIn(knownIds))
          .fetchInto(ScheduledJob::class.java)
    }
  }
}
