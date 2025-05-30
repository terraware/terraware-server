package com.terraformation.backend.accelerator.event

import com.terraformation.backend.documentproducer.event.VariableUpdatedSource
import com.terraformation.backend.documentproducer.event.VariablesUpdatedEvent

class ModulesUploadedEvent : VariablesUpdatedEvent(VariableUpdatedSource.ModulesUploaded)
