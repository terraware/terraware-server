package com.terraformation.backend.db

class AccessionNotFoundException(val accessionNumber: String) :
    Exception("Accession $accessionNumber not found")

class DeviceNotFoundException(override val message: String) : Exception(message)

class SpeciesNotFoundException(val speciesId: Long) : Exception("Species $speciesId not found")
