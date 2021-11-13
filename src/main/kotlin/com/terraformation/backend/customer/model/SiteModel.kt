package com.terraformation.backend.customer.model

import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.db.tables.pojos.SitesRow
import com.terraformation.backend.db.tables.references.SITES
import java.time.Instant
import net.postgis.jdbc.geometry.Point
import org.jooq.Field
import org.jooq.Record

data class SiteModel(
    val id: SiteId,
    val projectId: ProjectId,
    val name: String,
    val location: Point,
    val createdTime: Instant,
    val modifiedTime: Instant,
    val locale: String? = null,
    val timezone: String? = null,
    val facilities: List<FacilityModel>? = null,
) {
  constructor(
      record: Record,
      facilitiesMultiset: Field<List<FacilityModel>?>? = null,
  ) : this(
      record[SITES.ID] ?: throw IllegalArgumentException("ID is required"),
      record[SITES.PROJECT_ID] ?: throw IllegalArgumentException("Project ID is required"),
      record[SITES.NAME] ?: throw IllegalArgumentException("Name is required"),
      record[SITES.LOCATION]?.firstPoint ?: throw IllegalArgumentException("Location is required"),
      record[SITES.CREATED_TIME] ?: throw IllegalArgumentException("Created time is required"),
      record[SITES.MODIFIED_TIME] ?: throw IllegalArgumentException("Modified time is required"),
      record[SITES.LOCALE],
      record[SITES.TIMEZONE],
      record[facilitiesMultiset],
  )
}

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
