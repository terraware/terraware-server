package com.terraformation.backend.funder.db

import com.terraformation.backend.db.EntityNotFoundException
import com.terraformation.backend.db.MismatchedStateException
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.funder.FundingEntityId

class FundingEntityNotFoundException : EntityNotFoundException {
  constructor(fundingEntityId: FundingEntityId) : super("Funding entity $fundingEntityId not found")

  constructor(userId: UserId) : super("User with id $userId had no funding entity")
}

class RemoveFunderNotFoundException(fundingEntityId: FundingEntityId, userId: UserId) :
    EntityNotFoundException(
        "Funding entity $fundingEntityId or user $userId not found, or no association between each other") {}

class FundingEntityExistsException(name: String) :
    MismatchedStateException("Funding entity $name already exists")

class EmailExistsException(email: String) : MismatchedStateException("Email $email already exists")
