package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.ApplicationStatus

enum class ExternalApplicationStatus {
  NotSubmitted,
  FailedPreScreen,
  PassedPreScreen,
  InReview,
  Accepted,
  NotEligible,
  Waitlist;

  fun shouldNotify(): Boolean {
    return when (this) {
      NotSubmitted,
      FailedPreScreen,
      PassedPreScreen,
      InReview -> false
      Accepted,
      NotEligible,
      Waitlist -> true
    }
  }

  companion object {
    fun of(status: ApplicationStatus): ExternalApplicationStatus {
      return when (status) {
        ApplicationStatus.NotSubmitted -> NotSubmitted
        ApplicationStatus.FailedPreScreen -> FailedPreScreen
        ApplicationStatus.PassedPreScreen -> PassedPreScreen
        ApplicationStatus.Submitted,
        ApplicationStatus.CarbonAssessment,
        ApplicationStatus.GISAssessment,
        ApplicationStatus.ExpertReview,
        ApplicationStatus.P0Eligible,
        ApplicationStatus.SourcingTeamReview -> InReview
        ApplicationStatus.IssueActive,
        ApplicationStatus.IssueReassessment -> Waitlist
        ApplicationStatus.Accepted -> Accepted
        ApplicationStatus.NotEligible -> NotEligible
      }
    }
  }
}
