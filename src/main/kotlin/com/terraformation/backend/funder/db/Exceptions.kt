package com.terraformation.backend.funder.db

import com.terraformation.backend.db.EntityNotFoundException
import com.terraformation.backend.db.MismatchedStateException
import com.terraformation.backend.db.funder.FundingEntityId

class FundingEntityNotFoundException(fundingEntityId: FundingEntityId) :
    EntityNotFoundException("Funding entity $fundingEntityId not found")

class FundingEntityExistsException(name: String) :
    MismatchedStateException("Funding entity $name already exists")

class EmailExistsException(email: String) : MismatchedStateException("Email $email already exists")
