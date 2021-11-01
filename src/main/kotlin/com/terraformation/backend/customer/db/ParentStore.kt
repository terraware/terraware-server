package com.terraformation.backend.customer.db

import com.terraformation.backend.customer.model.UserModel
import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.AutomationId
import com.terraformation.backend.db.DeviceId
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.FeatureId
import com.terraformation.backend.db.LayerId
import com.terraformation.backend.db.PhotoId
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.db.tables.references.ACCESSIONS
import com.terraformation.backend.db.tables.references.AUTOMATIONS
import com.terraformation.backend.db.tables.references.DEVICES
import com.terraformation.backend.db.tables.references.FEATURES
import com.terraformation.backend.db.tables.references.FEATURE_PHOTOS
import com.terraformation.backend.db.tables.references.LAYERS
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.TableField

/**
 * Lookup methods to get the IDs of the parents of various objects.
 *
 * This is mostly called by [UserModel] to evaluate permissions on child objects in cases where the
 * children inherit permissions from parents. Putting all these lookups in one place reduces the
 * number of dependencies in [UserModel], and also gives us a clean place to introduce caching if
 * parent ID lookups in permission checks become a performance bottleneck.
 */
@ManagedBean
class ParentStore(private val dslContext: DSLContext) {
  fun getFacilityId(accessionId: AccessionId): FacilityId? =
      fetchFieldById(accessionId, ACCESSIONS.ID, ACCESSIONS.FACILITY_ID)

  fun getFacilityId(automationId: AutomationId): FacilityId? =
      fetchFieldById(automationId, AUTOMATIONS.ID, AUTOMATIONS.FACILITY_ID)

  fun getFacilityId(deviceId: DeviceId): FacilityId? =
      fetchFieldById(deviceId, DEVICES.ID, DEVICES.FACILITY_ID)

  fun getFeatureId(photoId: PhotoId): FeatureId? =
      fetchFieldById(photoId, FEATURE_PHOTOS.PHOTO_ID, FEATURE_PHOTOS.FEATURE_ID)

  fun getLayerId(featureId: FeatureId): LayerId? =
      fetchFieldById(featureId, FEATURES.ID, FEATURES.LAYER_ID)

  fun getSiteId(layerId: LayerId): SiteId? = fetchFieldById(layerId, LAYERS.ID, LAYERS.SITE_ID)

  /**
   * Looks up a database row by an ID and returns the value of one of the columns, or null if no row
   * had the given ID.
   */
  private fun <C, P, R : Record> fetchFieldById(
      id: C,
      idField: TableField<R, C>,
      fieldToFetch: TableField<R, P>
  ): P? {
    return dslContext
        .select(fieldToFetch)
        .from(idField.table)
        .where(idField.eq(id))
        .fetchOne(fieldToFetch)
  }
}
