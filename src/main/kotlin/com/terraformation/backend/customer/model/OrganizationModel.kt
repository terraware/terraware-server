package com.terraformation.backend.customer.model

import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.tables.pojos.OrganizationsRow
import java.time.Instant

/** Represents an organization that has already been committed to the database. */
data class OrganizationModel(
    val id: OrganizationId,
    val name: String,
    val location: String?,
    val disabledTime: Instant? = null
)

fun OrganizationsRow.toModel(): OrganizationModel =
    OrganizationModel(
        id ?: throw IllegalArgumentException("ID is required"),
        name ?: throw IllegalArgumentException("Name is required"),
        location,
        disabledTime)
