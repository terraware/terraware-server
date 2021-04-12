package com.terraformation.seedbank.db

class AccessionNotFoundException(val accessionNumber: String) :
    Exception("Accession $accessionNumber not found")

class SpeciesNotFoundException(val speciesId: Long) : Exception("Species $speciesId not found")
