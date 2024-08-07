package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.ApplicationStatus

enum class ExternalApplicationStatus {
  NotSubmitted,
  FailedPreScreen,
  PassedPreScreen,
  InReview,
  Accepted,
  NotAccepted,
  Waitlist;

  fun shouldNotify(): Boolean {
    return when (this) {
      NotSubmitted,
      FailedPreScreen,
      PassedPreScreen,
      InReview -> false
      Accepted,
      NotAccepted,
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
        ApplicationStatus.PLReview,
        ApplicationStatus.ReadyForReview,
        ApplicationStatus.PreCheck,
        ApplicationStatus.NeedsFollowUp,
        ApplicationStatus.CarbonEligible -> InReview
        ApplicationStatus.IssueActive,
        ApplicationStatus.IssuePending,
        ApplicationStatus.IssueResolved -> Waitlist
        ApplicationStatus.Accepted -> Accepted
        ApplicationStatus.NotAccepted -> NotAccepted
      }
    }
  }
}
