package com.terraformation.backend.report

import java.lang.RuntimeException

/** Thrown when a report is missing required information. */
class SeedFundReportNotCompleteException(val reason: String) :
    RuntimeException("Seed fund report is not complete: $reason")
