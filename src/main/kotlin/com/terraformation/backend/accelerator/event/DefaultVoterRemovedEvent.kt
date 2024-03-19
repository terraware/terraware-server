package com.terraformation.backend.accelerator.event

import com.terraformation.backend.db.default_schema.UserId

data class DefaultVoterRemovedEvent(val userId: UserId)
