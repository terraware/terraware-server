package com.terraformation.backend.accelerator.db

import com.terraformation.backend.db.EntityNotFoundException
import com.terraformation.backend.db.default_schema.ParticipantId

class ParticipantNotFoundException(id: ParticipantId) :
    EntityNotFoundException("Participant $id not found")
