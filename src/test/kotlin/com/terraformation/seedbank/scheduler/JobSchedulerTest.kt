package com.terraformation.seedbank.scheduler

import io.micronaut.core.serialize.exceptions.SerializationException
import io.mockk.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class JobSchedulerTest {
  private val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)!!
  private val executor = mockk<ScheduledExecutorService>()
  private val jsonSerializer = MockSerializer()
  private val jobRepository = mockk<JobRepository>()

  private val stringRunner = MockRunner(String::class.java)

  private val jobId = 123456789012345L
  private val future = mockk<ScheduledFuture<Any>>()

  @BeforeEach
  fun initMocks() {
    every { executor.schedule(any(), any(), any()) } returns future

    every { jobRepository.delete(jobId) } returns true
    every { jobRepository.insert(any(), any()) } returns jobId
    every { jobRepository.markAsStarted(any(), any()) } returns true
    every { jobRepository.recordFailure(any(), any(), any()) } returns true
  }

  private fun makeScheduler(vararg runners: JobRunner): JobScheduler {
    val scheduler = JobScheduler(jsonSerializer, jobRepository, runners.asList(), executor)
    scheduler.clock = clock
    return scheduler
  }

  @Test
  fun `cannot schedule job that has no runners`() {
    val scheduler = makeScheduler()
    assertThrows(IllegalArgumentException::class.java) { scheduler.schedule("job", Instant.EPOCH) }
    verify { jobRepository wasNot Called }
    verify { executor wasNot Called }
  }

  @Test
  fun `cannot schedule job that has multiple possible runners`() {
    val scheduler = makeScheduler(stringRunner, stringRunner)
    assertThrows(IllegalArgumentException::class.java) { scheduler.schedule("job", Instant.EPOCH) }
    verify { jobRepository wasNot Called }
    verify { executor wasNot Called }
  }

  @Test
  fun `cannot schedule job that fails to serialize`() {
    val scheduler = makeScheduler(stringRunner)
    assertThrows(SerializationException::class.java) {
      scheduler.schedule("not serializable", Instant.EPOCH)
    }
    verify { jobRepository wasNot Called }
    verify { executor wasNot Called }
  }

  @Test
  fun `returns job ID if successfully scheduled`() {
    val time = Instant.now()
    val scheduler = makeScheduler(stringRunner)

    val actualJobId = scheduler.schedule("job", time)

    assertEquals(jobId, actualJobId, "Job ID")
    verify { jobRepository.insert(any(), time) }
    verify { executor.schedule(any(), time.toEpochMilli(), TimeUnit.MILLISECONDS) }
  }

  @Test
  fun `marks job as in progress before calling runner`() {
    val taskSlot = slot<Runnable>()
    val future = mockk<ScheduledFuture<String>>()

    every { executor.schedule(capture(taskSlot), any(), any()) } returns future

    val runner =
        MockRunner(String::class.java) { job ->
          verify(exactly = 1) { jobRepository.markAsStarted(jobId, Instant.EPOCH) }
          verify(exactly = 0) { jobRepository.delete(any()) }
        }

    val scheduler = makeScheduler(runner)
    scheduler.schedule("job", Instant.EPOCH)
    val runnable = taskSlot.captured

    // This calls the callback function defined above to verify the job is marked as started
    runnable.run()

    verify(exactly = 1) { jobRepository.delete(jobId) }
  }

  @Test
  fun `records failure message if job throws exception`() {
    val taskSlot = slot<Runnable>()
    val future = mockk<ScheduledFuture<String>>()
    val errorMessage = "message from the exception"

    every { executor.schedule(capture(taskSlot), any(), any()) } returns future

    val runner = MockRunner(String::class.java) { throw RuntimeException(errorMessage) }

    val scheduler = makeScheduler(runner)
    scheduler.schedule("job", Instant.EPOCH)
    val runnable = taskSlot.captured

    runnable.run()

    verify(exactly = 0) { jobRepository.delete(any()) }
    verify {
      jobRepository.recordFailure(
          jobId, errorMessage, match { stacktrace -> JobScheduler::class.java.name in stacktrace })
    }
  }

  class MockSerializer : JobSerializer<String> {
    override fun deserialize(className: String, version: Int, serialized: String): Any {
      return serialized.substring(1)
    }

    override fun serialize(value: Any): SerializedJob<String> {
      if (value == "not serializable") {
        throw SerializationException("not serializable")
      }
      return SerializedJob(value.javaClass.name, 1, "S$value")
    }
  }

  open class MockRunner(
      private val forClass: Class<*>,
      private val runFunc: ((Any) -> Unit)? = null
  ) : JobRunner {
    override fun canRunJob(jobClass: Class<*>): Boolean {
      return jobClass == forClass
    }

    override fun runJob(job: Any) {
      runFunc?.invoke(job)
    }
  }
}
