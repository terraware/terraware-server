package com.terraformation.backend.db

class AccessionNotFoundException(val accessionNumber: String) :
    Exception("Accession $accessionNumber not found")

class DeviceNotFoundException(override val message: String) : Exception(message)

class LayerNotFoundException(val layerId: LayerId) : Exception("Layer $layerId not found")

class SpeciesNotFoundException(val speciesId: SpeciesId) :
    Exception("Species $speciesId not found")
