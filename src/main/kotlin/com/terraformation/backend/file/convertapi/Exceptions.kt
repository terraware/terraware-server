package com.terraformation.backend.file.convertapi

class ConvertApiErrorException(val errorResponse: ConvertApiErrorResponse) :
    RuntimeException(
        "ConvertAPI request failed with error code ${errorResponse.code}: ${errorResponse.message}"
    )

class ConvertApiServiceUnavailableException :
    RuntimeException("ConvertAPI service temporarily unavailable or throttled")
