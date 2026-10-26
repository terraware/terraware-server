package com.terraformation.backend.integration

import com.terraformation.backend.db.DatabaseTest
import java.time.Duration
import javax.sql.DataSource
import org.jobrunr.jobs.mappers.JobMapper
import org.jobrunr.scheduling.JobScheduler
import org.jobrunr.storage.sql.postgres.PostgresStorageProvider
import org.jobrunr.utils.mapper.jackson3.Jackson3JsonMapper
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.module.kotlin.jacksonMapperBuilder

/**
 * Sanity check to make sure JobRunr doesn't break with the other dependency versions we're using.
 * This isn't intended to test that JobRunr's functionality is as advertised, just to make sure it
 * doesn't blow up when we do things like upgrade to new Kotlin versions.
 */
internal class JobRunrCompatibilityTest : DatabaseTest() {
  @Autowired private lateinit var dataSource: DataSource

  private val scheduler by lazy {
    val storageProvider = PostgresStorageProvider(dataSource)
    storageProvider.setJobMapper(JobMapper(Jackson3JsonMapper(jacksonMapperBuilder())))
    JobScheduler(storageProvider)
  }

  private val jobId = javaClass.simpleName

  @Test
  fun `can schedule a recurrent job using a lambda function`() {
    scheduler.scheduleRecurrently<JobRunrCompatibilityTest>(jobId, Duration.ofDays(1)) {
      doNothing()
    }
  }

  fun doNothing() {}
}
