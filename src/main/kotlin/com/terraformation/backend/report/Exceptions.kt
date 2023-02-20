package com.terraformation.backend.report

import java.lang.RuntimeException

/** Thrown when a report is missing required information. */
class ReportNotCompleteException(val reason: String) :
    RuntimeException("Report is not complete: $reason")
