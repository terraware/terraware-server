package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.ApplicationId
import com.terraformation.backend.db.accelerator.ApplicationStatus
import com.terraformation.backend.db.accelerator.tables.references.APPLICATIONS
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.util.toMultiPolygon
import java.time.Instant
import org.geotools.api.feature.simple.SimpleFeature
import org.geotools.feature.simple.SimpleFeatureBuilder
import org.geotools.feature.simple.SimpleFeatureTypeBuilder
import org.jooq.Field
import org.jooq.Record
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.MultiPolygon

data class ExistingApplicationModel(
    val boundary: Geometry? = null,
    val countryCode: String? = null,
    val createdTime: Instant,
    val feedback: String? = null,
    val id: ApplicationId,
    val internalComment: String? = null,
    val internalName: String,
    val modifiedTime: Instant?,
    val organizationId: OrganizationId,
    val organizationName: String,
    val projectId: ProjectId,
    val projectName: String,
    val status: ApplicationStatus,
) {
  fun toGeoFeature(): SimpleFeature {
    if (boundary == null) {
      throw IllegalStateException("Application has no boundary")
    }

    val featureTypeBuilder = SimpleFeatureTypeBuilder()
    featureTypeBuilder.setName("Accelerator Application")
    featureTypeBuilder.add("the_geom", MultiPolygon::class.java)
    featureTypeBuilder.add("applicationId", Long::class.java)
    featureTypeBuilder.add("countryCode", String::class.java)
    featureTypeBuilder.add("internalName", String::class.java)
    featureTypeBuilder.add("organizationId", Long::class.java)
    featureTypeBuilder.add("organizationName", String::class.java)
    featureTypeBuilder.add("projectId", Long::class.java)
    featureTypeBuilder.add("projectName", String::class.java)
    featureTypeBuilder.add("status", String::class.java)

    val featureType = featureTypeBuilder.buildFeatureType()

    val featureBuilder = SimpleFeatureBuilder(featureType)
    featureBuilder.set("the_geom", boundary.toMultiPolygon())
    featureBuilder.set("applicationId", id.value)
    countryCode?.let { featureBuilder.set("countryCode", it) }
    featureBuilder.set("internalName", internalName)
    featureBuilder.set("organizationId", organizationId.value)
    featureBuilder.set("organizationName", organizationName)
    featureBuilder.set("projectId", projectId.value)
    featureBuilder.set("projectName", projectName)
    featureBuilder.set("status", status.jsonValue)
    return featureBuilder.buildFeature("Application $id")
  }

  companion object {
    fun of(
        record: Record,
        modifiedTimeField: Field<Instant?>? = null,
    ): ExistingApplicationModel {
      return with(APPLICATIONS) {
        ExistingApplicationModel(
            boundary = record[BOUNDARY],
            countryCode = record[COUNTRY_CODE],
            createdTime = record[CREATED_TIME]!!,
            feedback = record[FEEDBACK],
            id = record[ID]!!,
            internalComment = record[INTERNAL_COMMENT],
            internalName = record[INTERNAL_NAME]!!,
            modifiedTime = modifiedTimeField?.let { record[it] },
            organizationId = record[projects.ORGANIZATION_ID]!!,
            organizationName = record[projects.organizations.NAME]!!,
            projectId = record[PROJECT_ID]!!,
            projectName = record[projects.NAME]!!,
            status = record[APPLICATION_STATUS_ID]!!,
        )
      }
    }
  }
}
