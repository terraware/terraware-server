package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.tables.references.BIRDNET_RESULTS
import com.terraformation.backend.db.default_schema.tables.references.FILES
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATION_MEDIA_FILES
import com.terraformation.backend.db.default_schema.tables.references.SPLATS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.CoordinateField.Companion.LATITUDE
import com.terraformation.backend.search.field.CoordinateField.Companion.LONGITUDE
import com.terraformation.backend.search.field.CoordinateField.Companion.POINT
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.TableField
import org.jooq.impl.DSL

class OrganizationMediaFilesTable(tables: SearchTables) : SearchTable() {
  private val filesAlias = FILES.`as`("organization_media_files_files")

  override val primaryKey: TableField<out Record, out Any?>
    get() = ORGANIZATION_MEDIA_FILES.FILE_ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          organizations.asSingleValueSublist(
              "organization",
              ORGANIZATION_MEDIA_FILES.ORGANIZATION_ID.eq(ORGANIZATIONS.ID),
              isRequired = true,
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          nonLocalizableEnumField(
              "birdnetStatus",
              DSL.field(
                  DSL.select(BIRDNET_RESULTS.ASSET_STATUS_ID)
                      .from(BIRDNET_RESULTS)
                      .where(BIRDNET_RESULTS.FILE_ID.eq(ORGANIZATION_MEDIA_FILES.FILE_ID))
              ),
          ),
          textField("caption", ORGANIZATION_MEDIA_FILES.CAPTION),
          textField("contentType", filesAlias.CONTENT_TYPE),
          timestampField("createdTime", filesAlias.CREATED_TIME),
          idWrapperField("fileId", ORGANIZATION_MEDIA_FILES.FILE_ID) { FileId(it) },
          geometryField("gpsCoordinates", filesAlias.GEOLOCATION),
          coordinateField("latitude", filesAlias.GEOLOCATION, POINT, LATITUDE),
          coordinateField("longitude", filesAlias.GEOLOCATION, POINT, LONGITUDE),
          booleanField("needsAttention", SPLATS.NEEDS_ATTENTION),
          nonLocalizableEnumField("splatStatus", SPLATS.ASSET_STATUS_ID),
      )

  override val defaultOrderFields: List<OrderField<*>> = listOf(ORGANIZATION_MEDIA_FILES.FILE_ID)

  override val fromTable
    get() =
        ORGANIZATION_MEDIA_FILES.join(filesAlias)
            .on(ORGANIZATION_MEDIA_FILES.FILE_ID.eq(filesAlias.ID))
            .leftJoin(SPLATS)
            .on(ORGANIZATION_MEDIA_FILES.FILE_ID.eq(SPLATS.FILE_ID))

  override fun conditionForVisibility(): Condition {
    return ORGANIZATION_MEDIA_FILES.ORGANIZATION_ID.`in`(currentUser().organizationRoles.keys)
  }
}
