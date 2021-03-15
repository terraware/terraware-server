package com.terraformation.seedbank.db

class AccessionNotFoundException(val accessionNumber: String) :
    Exception("Accession $accessionNumber not found")
