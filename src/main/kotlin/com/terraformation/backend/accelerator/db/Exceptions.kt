package com.terraformation.backend.accelerator.db

import com.terraformation.backend.db.EntityNotFoundException
import com.terraformation.backend.db.MismatchedStateException
import com.terraformation.backend.db.default_schema.CohortId
import com.terraformation.backend.db.default_schema.ParticipantId

class CohortNotFoundException(id: CohortId) :
  EntityNotFoundException("Cohort $id not found")

class ParticipantHasProjectsException(id: ParticipantId) :
    MismatchedStateException("Participant $id has projects")

class ParticipantNotFoundException(id: ParticipantId) :
    EntityNotFoundException("Participant $id not found")
