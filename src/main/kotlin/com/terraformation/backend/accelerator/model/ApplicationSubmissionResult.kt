package com.terraformation.backend.accelerator.model

data class ApplicationSubmissionResult(
    /**
     * The application after the submission. Its status will reflect the outcome of the submission.
     */
    val application: ExistingApplicationModel,
    /**
     * If the submission was for pre-screening and the application failed, a list of the failure
     * reasons.
     */
    val problems: List<String> = emptyList(),
) {
  val isSuccessful: Boolean
    get() = problems.isEmpty()
}
