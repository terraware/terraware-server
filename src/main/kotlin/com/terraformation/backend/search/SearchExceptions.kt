package com.terraformation.backend.search

import jakarta.ws.rs.ClientErrorException
import jakarta.ws.rs.core.Response

/** An exception thrown when a non-exportable search field is requested for export to CSV. */
class SearchFieldNotExportableException(fieldName: String) :
    ClientErrorException(
        "The search field $fieldName is not exportable",
        Response.Status.BAD_REQUEST,
    )
