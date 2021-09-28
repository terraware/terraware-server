package com.terraformation.backend.customer.model

import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.db.tables.pojos.SitesRow
import java.time.Instant
import net.postgis.jdbc.geometry.Point

data class SiteModel(
    val id: SiteId,
    val projectId: ProjectId,
    val name: String,
    val location: Point,
    val createdTime: Instant,
    val modifiedTime: Instant,
    val locale: String? = null,
    val timezone: String? = null,
)

fun SitesRow.toModel() =
    SiteModel(
        id ?: throw IllegalArgumentException("ID is required"),
        projectId ?: throw IllegalArgumentException("Project ID is required"),
        name ?: throw IllegalArgumentException("Name is required"),
        location?.firstPoint ?: throw IllegalArgumentException("Location is required"),
        createdTime ?: throw IllegalArgumentException("Created time is required"),
        modifiedTime ?: throw IllegalArgumentException("Modified time is required"),
        locale,
        timezone)
