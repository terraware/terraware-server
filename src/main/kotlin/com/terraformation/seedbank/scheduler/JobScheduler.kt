package com.terraformation.seedbank.scheduler

import com.terraformation.seedbank.services.perClassLogger
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.annotation.ManagedBean
import kotlin.math.max
import org.springframework.boot.context.event.ApplicationStartedEvent
import org.springframework.context.event.EventListener

@ManagedBean
class JobScheduler(
    private val jsonSerializer: JobSerializer<String>,
    private val jobRepository: JobRepository,
    private val jobRunners: List<JobRunner>,
    private val executor: ScheduledExecutorService = ScheduledThreadPoolExecutor(2),
    private val clock: Clock = Clock.systemUTC()
) {
  private val runnersByClass = ConcurrentHashMap<Class<*>, JobRunner>()
  private val futuresByJobId = ConcurrentHashMap<Long, ScheduledFuture<*>>()

  private val log = perClassLogger()

  private fun getRunner(jobClass: Class<*>): JobRunner {
    return runnersByClass.computeIfAbsent(jobClass) {
      val matchingRunners = jobRunners.filter { it.canRunJob(jobClass) }
      when (matchingRunners.size) {
        0 -> throw IllegalArgumentException("No runner defined for ${jobClass.name}")
        1 -> matchingRunners[0]
        else -> throw IllegalArgumentException("Multiple runners defined for ${jobClass.name}")
      }
    }
  }

  /** @throws SerializationException The job could not be serialized for storage in the database. */
  fun schedule(job: Any, time: Instant): Long {
    val serializedJob =
        try {
          jsonSerializer.serialize(job)
        } catch (e: Exception) {
          log.error("Unable to serialize ${job.javaClass.name}")
          throw SerializationException("Unable to serialize scheduled job", e)
        }

    val runner = getRunner(job.javaClass)

    val jobId =
        try {
          jobRepository.insert(serializedJob, time)
        } catch (e: Exception) {
          log.error("Unable to save scheduled job in database! It will not be executed.", e)
          throw e
        }

    registerWithExecutor(jobId, job, time, runner)
    // TODO("Catch exception; delete row from database, or maybe record it as a failure?")

    return jobId
  }

  private fun registerWithExecutor(
      jobId: Long,
      job: Any,
      time: Instant,
      runner: JobRunner = getRunner(job.javaClass)
  ) {
    val task = ScheduledJobTask(jobId, job, runner)

    // If the scheduled time is in the past, run the task immediately.
    val delay = max(time.toEpochMilli() - clock.millis(), 1L)
    try {
      val future = executor.schedule(task, delay, TimeUnit.MILLISECONDS)
      futuresByJobId[jobId] = future
    } catch (e: RejectedExecutionException) {
      log.error("Unable to schedule job for execution", e)
      throw e
    }
  }

  fun cancel(jobId: Long): Boolean {
    val futureCancelled = futuresByJobId.remove(jobId)?.cancel(false) ?: false
    val rowDeleted = jobRepository.delete(jobId)

    return futureCancelled && rowDeleted
  }

  fun refresh() {
    val newJobs = jobRepository.fetchNew(futuresByJobId.keys)
    newJobs.forEach { jobData ->
      try {
        val deserializedJob =
            jsonSerializer.deserialize(
                jobData.payloadClass!!, jobData.payloadVersion!!, jobData.payloadData!!.data())
        registerWithExecutor(jobData.id!!, deserializedJob, jobData.scheduledTime!!)
      } catch (e: Exception) {
        log.error("Unable to load job ${jobData.id}", e)
      }
    }
  }

  @EventListener
  fun onApplicationStart(@Suppress("UNUSED_PARAMETER") event: ApplicationStartedEvent) {
    log.debug("Loading scheduled events")
    refresh()
  }

  inner class ScheduledJobTask(val id: Long, val job: Any, val runner: JobRunner) : Runnable {
    override fun run() {
      if (!jobRepository.markAsStarted(id, clock.instant())) {
        log.warn("Couldn't mark scheduled job $id as started; ignoring it")
        return
      }

      futuresByJobId.remove(id)

      log.debug("Starting job $id of type ${job.javaClass.name}")

      try {
        runner.runJob(job)

        log.debug("Job $id completed successfully")

        try {
          if (!jobRepository.delete(id)) {
            // This should never happen since the markAsStarted step should act as an exclusive
            // lock.
            log.error("Unable to delete job $id from database; it may have been executed twice")
          }
        } catch (deleteJobException: Exception) {
          // Ideally we'd record this error as a failure message on the job itself, but if we could
          // do that, the deletion wouldn't have failed in the first place.
          log.error("Unable to delete successful job $id from database", deleteJobException)
        }
      } catch (jobException: Exception) {
        log.error("Job $id of type ${job.javaClass.name} threw an exception", jobException)

        // i18n
        val message = jobException.message ?: "An error occurred while processing this job."
        val details = jobException.stackTraceToString()
        try {
          if (!jobRepository.recordFailure(id, message, details)) {
            log.error("Unable to record failure details for job $id; it may have been deleted")
          }
        } catch (recordFailureException: Exception) {
          log.error("Unable to record failure details for job $id", recordFailureException)
        }
      }
    }
  }
}
