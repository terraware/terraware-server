package com.terraformation.backend.seedbank.model

import com.terraformation.backend.db.UserId
import java.time.LocalDate

enum class AccessionHistoryType {
  Created,
  StateChanged,
}

data class AccessionHistoryModel(
    val date: LocalDate,
    val description: String,
    val type: AccessionHistoryType,
    val userId: UserId,
    val userName: String?,
)
