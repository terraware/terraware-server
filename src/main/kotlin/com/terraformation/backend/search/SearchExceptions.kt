package com.terraformation.backend.search

import jakarta.ws.rs.ClientErrorException
import jakarta.ws.rs.core.Response

/** An exception thrown when a non-exportable search field is requested for export to CSV. */
class SearchFieldNotExportableException(fieldName: String) :
    ClientErrorException(
        "The search field $fieldName is not exportable", Response.Status.BAD_REQUEST)

/** An exception thrown when a search path contains the same table multiple times. */
class SearchTableRevisitedException(table: String) :
    IllegalArgumentException("The search path visited $table multiple times")
