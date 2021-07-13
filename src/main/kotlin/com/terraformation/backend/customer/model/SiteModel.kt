package com.terraformation.backend.customer.model

import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.db.tables.pojos.SitesRow
import java.math.BigDecimal

data class SiteModel(
    val id: SiteId,
    val projectId: ProjectId,
    val name: String,
    val latitude: BigDecimal,
    val longitude: BigDecimal
)

fun SitesRow.toModel() =
    SiteModel(
        id ?: throw IllegalArgumentException("ID is required"),
        projectId ?: throw IllegalArgumentException("Project ID is required"),
        name ?: throw IllegalArgumentException("Name is required"),
        latitude ?: throw IllegalArgumentException("Latitude is required"),
        longitude ?: throw IllegalArgumentException("Longitude is required"))
