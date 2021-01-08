package com.terraformation.seedbank.scheduler

import javax.inject.Singleton

interface JobRunner {
  fun canRunJob(jobClass: Class<*>): Boolean
  fun runJob(job: Any)
}

@Singleton
class RunnableJobRunner : JobRunner {
  override fun canRunJob(jobClass: Class<*>) = Runnable::class.java.isAssignableFrom(jobClass)

  override fun runJob(job: Any) {
    if (job is Runnable) {
      job.run()
    } else {
      throw IllegalStateException("Job is not Runnable; this runner should not have been called")
    }
  }
}
