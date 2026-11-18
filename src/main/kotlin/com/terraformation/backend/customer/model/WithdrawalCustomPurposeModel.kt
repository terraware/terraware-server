package com.terraformation.backend.customer.model

import com.terraformation.backend.db.default_schema.WithdrawalCustomPurposeId

data class WithdrawalCustomPurposeModel(
    val id: WithdrawalCustomPurposeId,
    val isArchived: Boolean,
    val name: String,
)
