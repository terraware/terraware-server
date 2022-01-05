package com.terraformation.backend.gis.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.FeatureId
import com.terraformation.backend.db.FeatureNotFoundException
import com.terraformation.backend.db.FuzzySearchOperators
import com.terraformation.backend.db.LayerId
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.PhotoId
import com.terraformation.backend.db.PhotoNotFoundException
import com.terraformation.backend.db.PlantNotFoundException
import com.terraformation.backend.db.PlantObservationId
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.SRID
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.db.SpeciesId
import com.terraformation.backend.db.UsesFuzzySearchOperators
import com.terraformation.backend.db.tables.daos.FeaturePhotosDao
import com.terraformation.backend.db.tables.daos.PhotosDao
import com.terraformation.backend.db.tables.daos.PlantsDao
import com.terraformation.backend.db.tables.daos.ThumbnailsDao
import com.terraformation.backend.db.tables.pojos.FeaturePhotosRow
import com.terraformation.backend.db.tables.pojos.PhotosRow
import com.terraformation.backend.db.tables.pojos.PlantsRow
import com.terraformation.backend.db.tables.references.FEATURES
import com.terraformation.backend.db.tables.references.FEATURE_PHOTOS
import com.terraformation.backend.db.tables.references.LAYERS
import com.terraformation.backend.db.tables.references.PHOTOS
import com.terraformation.backend.db.tables.references.PLANTS
import com.terraformation.backend.db.tables.references.PLANT_OBSERVATIONS
import com.terraformation.backend.db.tables.references.PROJECTS
import com.terraformation.backend.db.tables.references.SITES
import com.terraformation.backend.db.transformSrid
import com.terraformation.backend.file.FileStore
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.ThumbnailStore
import com.terraformation.backend.gis.model.FeatureModel
import com.terraformation.backend.log.perClassLogger
import java.io.InputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.time.Clock
import java.time.Instant
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.SelectOnConditionStep
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.springframework.dao.DuplicateKeyException

@ManagedBean
class FeatureStore(
    private val clock: Clock,
    private val dslContext: DSLContext,
    private val featurePhotosDao: FeaturePhotosDao,
    private val fileStore: FileStore,
    override val fuzzySearchOperators: FuzzySearchOperators,
    private val photosDao: PhotosDao,
    private val plantsDao: PlantsDao,
    private val thumbnailStore: ThumbnailStore,
    private val thumbnailsDao: ThumbnailsDao,
) : UsesFuzzySearchOperators {
  private val log = perClassLogger()

  fun createFeature(model: FeatureModel): FeatureModel {
    val layerId = model.layerId ?: throw IllegalArgumentException("Layer ID must not be null")

    requirePermissions { createFeature(layerId) }

    return dslContext.transactionResult { _ ->
      val currTime = clock.instant()
      val insertedRecord =
          with(FEATURES) {
            dslContext
                .insertInto(FEATURES)
                .set(LAYER_ID, layerId)
                .set(GEOM, model.geom)
                .set(GPS_HORIZ_ACCURACY, model.gpsHorizAccuracy)
                .set(GPS_VERT_ACCURACY, model.gpsVertAccuracy)
                .set(ATTRIB, model.attrib)
                .set(NOTES, model.notes)
                .set(ENTERED_TIME, model.enteredTime)
                .set(CREATED_TIME, currTime)
                .set(MODIFIED_TIME, currTime)
                .returning(ID, GEOM.transformSrid(SRID.LONG_LAT).`as`(GEOM))
                .fetchOne()
                ?: throw DataAccessException("Database did not return ID")
          }

      val featureId = insertedRecord[FEATURES.ID]

      val plant = model.plant?.copy(featureId = featureId)?.also { plantsDao.insert(it) }

      model.copy(
          id = featureId,
          geom = insertedRecord[FEATURES.GEOM],
          createdTime = currTime,
          modifiedTime = currTime,
          plant = plant,
      )
    }
  }

  fun fetchFeature(id: FeatureId): FeatureModel? {
    if (!currentUser().canReadFeature(id)) {
      return null
    }
    return noPermissionsCheckFetch(id)
  }

  fun countFeatures(id: LayerId): Int {
    if (!currentUser().canReadLayer(id)) {
      return 0
    }

    return dslContext
        .selectCount()
        .from(FEATURES)
        .where(FEATURES.LAYER_ID.eq(id))
        .fetchOne()
        ?.value1()
        ?: 0
  }

  fun listFeatures(
      layerId: LayerId,
      skip: Int? = null,
      limit: Int? = null,
      speciesId: SpeciesId? = null,
      speciesName: String? = null,
      minEnteredTime: Instant? = null,
      maxEnteredTime: Instant? = null,
      notes: String? = null,
      plantsOnly: Boolean = false,
  ): List<FeatureModel> {
    if (!currentUser().canReadLayer(layerId)) {
      return emptyList()
    }

    return selectFromFeatures()
        .where(
            listOfNotNull(
                FEATURES.LAYER_ID.eq(layerId),
                speciesId?.let { PLANTS.SPECIES_ID.eq(it) },
                speciesName?.let { PLANTS.species().NAME.eq(it) },
                minEnteredTime?.let { FEATURES.ENTERED_TIME.greaterOrEqual(it) },
                maxEnteredTime?.let { FEATURES.ENTERED_TIME.lessOrEqual(it) },
                notes?.let { FEATURES.NOTES.likeFuzzy(it) },
                if (plantsOnly) PLANTS.FEATURE_ID.isNotNull else null,
            ))
        .orderBy(FEATURES.ID)
        .limit(limit)
        .offset(skip)
        .fetch { recordToModel(it) }
  }

  fun updateFeature(newModel: FeatureModel): FeatureModel {
    val featureId = newModel.id ?: throw IllegalArgumentException("No feature ID specified")

    requirePermissions { updateFeature(featureId) }

    val oldModel = noPermissionsCheckFetch(featureId) ?: throw FeatureNotFoundException(featureId)

    if (newModel.writableFieldsEqual(oldModel)) {
      return newModel
    }

    return dslContext.transactionResult { _ ->
      val currTime = clock.instant()

      val longLatGeom =
          with(FEATURES) {
            dslContext
                .update(FEATURES)
                .set(GEOM, newModel.geom)
                .set(GPS_HORIZ_ACCURACY, newModel.gpsHorizAccuracy)
                .set(GPS_VERT_ACCURACY, newModel.gpsVertAccuracy)
                .set(ATTRIB, newModel.attrib)
                .set(NOTES, newModel.notes)
                .set(ENTERED_TIME, newModel.enteredTime)
                .set(MODIFIED_TIME, currTime)
                .where(ID.eq(featureId))
                .returningResult(GEOM.transformSrid(SRID.LONG_LAT).`as`(GEOM))
                .fetchOne()
                ?.getValue(GEOM)
          }

      // Allow adding or updating plant data, but if the new model doesn't have plant data, treat
      // it as "leave the existing plant data alone" because we don't want to allow changing a
      // plant to some other kind of feature.
      if (newModel.plant != null) {
        val plantWithFeatureId = newModel.plant.copy(featureId = featureId)
        if (oldModel.plant != null) {
          plantsDao.update(plantWithFeatureId)
        } else {
          plantsDao.insert(plantWithFeatureId)
        }
      }

      newModel.copy(
          geom = longLatGeom,
          layerId = oldModel.layerId,
          modifiedTime = currTime,
      )
    }
  }

  fun deleteFeature(id: FeatureId): FeatureId {
    requirePermissions { deleteFeature(id) }

    val featurePhotos = featurePhotosDao.fetchByFeatureId(id)
    val photos = photosDao.fetchById(*featurePhotos.mapNotNull { it.photoId }.toTypedArray())

    photos.forEach { photo -> deletePhoto(id, photo.id!!) }

    dslContext.transaction { _ ->
      dslContext.delete(PLANT_OBSERVATIONS).where(PLANT_OBSERVATIONS.FEATURE_ID.eq(id)).execute()
      dslContext.delete(PLANTS).where(PLANTS.FEATURE_ID.eq(id)).execute()
      dslContext.delete(FEATURES).where(FEATURES.ID.eq(id)).execute()
    }

    return id
  }

  fun createPhoto(
      featureId: FeatureId,
      photosRow: PhotosRow,
      data: InputStream,
      plantObservationId: PlantObservationId? = null
  ): PhotoId {
    val contentType =
        photosRow.contentType ?: throw IllegalArgumentException("No content type specified")
    val size = photosRow.size ?: throw IllegalArgumentException("No file size specified")

    if (!currentUser().canUpdateFeature(featureId)) {
      throw FeatureNotFoundException(featureId)
    }

    val createdTime = clock.instant()
    val photoUrl = fileStore.newUrl(createdTime, "feature", contentType)

    try {
      fileStore.write(photoUrl, data, size)

      return dslContext.transactionResult { _ ->
        val sanitizedRow =
            photosRow.copy(
                createdTime = createdTime,
                id = null,
                modifiedTime = createdTime,
                storageUrl = photoUrl)

        photosDao.insert(sanitizedRow)

        featurePhotosDao.insert(
            FeaturePhotosRow(
                featureId = featureId,
                photoId = sanitizedRow.id,
                plantObservationId = plantObservationId))

        log.info("Stored $photoUrl for feature $featureId")

        sanitizedRow.id!!
      }
    } catch (e: FileAlreadyExistsException) {
      log.error("File $photoUrl already exists but should be unique")
      throw e
    } catch (e: Exception) {
      try {
        fileStore.delete(photoUrl)
      } catch (ignore: NoSuchFileException) {
        // Swallow this; file is already deleted
      }
      throw e
    }
  }

  fun listPhotos(featureId: FeatureId): List<PhotosRow> {
    requirePermissions { readFeature(featureId) }

    return dslContext
        .select(PHOTOS.asterisk())
        .from(PHOTOS)
        .join(FEATURE_PHOTOS)
        .on(PHOTOS.ID.eq(FEATURE_PHOTOS.PHOTO_ID))
        .where(FEATURE_PHOTOS.FEATURE_ID.eq(featureId))
        .and(FEATURE_PHOTOS.PLANT_OBSERVATION_ID.isNull)
        .fetchInto(PhotosRow::class.java)
  }

  fun getPhotoMetadata(featureId: FeatureId, photoId: PhotoId): PhotosRow {
    requirePermissions { readFeaturePhoto(photoId) }

    val featurePhoto = featurePhotosDao.fetchOneByPhotoId(photoId)
    if (featurePhoto?.featureId != featureId) {
      throw PhotoNotFoundException(photoId)
    }

    return photosDao.fetchOneById(photoId) ?: throw PhotoNotFoundException(photoId)
  }

  fun getPhotoData(
      featureId: FeatureId,
      photoId: PhotoId,
      maxWidth: Int? = null,
      maxHeight: Int? = null
  ): SizedInputStream {
    val photosRow = getPhotoMetadata(featureId, photoId)

    return try {
      if (maxWidth != null || maxHeight != null) {
        thumbnailStore.getThumbnailData(photoId, maxWidth, maxHeight)
      } else {
        fileStore.read(photosRow.storageUrl!!)
      }
    } catch (e: NoSuchFileException) {
      log.error("Feature $featureId photo $photoId file ${photosRow.storageUrl} not found")
      throw PhotoNotFoundException(photoId)
    }
  }

  fun deletePhoto(featureId: FeatureId, photoId: PhotoId) {
    requirePermissions { deleteFeaturePhoto(photoId) }

    val photosRow = getPhotoMetadata(featureId, photoId)
    val thumbnails = thumbnailsDao.fetchByPhotoId(photoId)
    val url = photosRow.storageUrl!!

    dslContext.transaction { _ ->
      if (thumbnails.isNotEmpty()) {
        thumbnailsDao.delete(thumbnails)
      }

      featurePhotosDao.deleteById(photoId)
      photosDao.deleteById(photoId)
    }

    try {
      fileStore.delete(url)
    } catch (e: Exception) {
      log.error("Unable to delete photo $url from file storage", e)
    }

    // TODO: Delete thumbnails from file storage
  }

  fun createPlant(plant: PlantsRow): PlantsRow {
    val featureId = plant.featureId ?: throw IllegalArgumentException("featureId cannot be null")
    val feature = fetchFeature(featureId) ?: throw FeatureNotFoundException(featureId)

    if (feature.plant != null) {
      throw DuplicateKeyException("Plant already exists")
    }

    updateFeature(feature.copy(plant = plant))

    return plant.copy()
  }

  fun updatePlant(plant: PlantsRow): PlantsRow {
    val featureId = plant.featureId ?: throw IllegalArgumentException("featureId cannot be null")
    val feature = fetchFeature(featureId) ?: throw FeatureNotFoundException(featureId)

    if (feature.plant == null) {
      throw PlantNotFoundException(featureId)
    }

    updateFeature(feature.copy(plant = plant))

    return plant.copy()
  }

  private fun fetchPlantSummary(
      layerIds: List<LayerId>,
      minEnteredTime: Instant? = null,
      maxEnteredTime: Instant? = null,
  ): Map<SpeciesId, Int> {
    val readableLayerIds = layerIds.filter { currentUser().canReadLayer(it) }
    if (readableLayerIds.isEmpty()) {
      return emptyMap()
    }

    return dslContext
        .select(
            DSL.coalesce<SpeciesId>(PLANTS.SPECIES_ID, SpeciesId(-1)), DSL.count(PLANTS.FEATURE_ID))
        .from(PLANTS)
        .join(FEATURES)
        .on(PLANTS.FEATURE_ID.eq(FEATURES.ID))
        .where(
            listOfNotNull(
                FEATURES.LAYER_ID.`in`(readableLayerIds),
                minEnteredTime?.let { FEATURES.ENTERED_TIME.greaterOrEqual(it) },
                maxEnteredTime?.let { FEATURES.ENTERED_TIME.lessOrEqual(it) }))
        .groupBy(PLANTS.SPECIES_ID)
        .fetchMap({ it.value1() }, { it.value2() })
  }

  fun fetchPlantSummary(
      layerId: LayerId,
      minEnteredTime: Instant? = null,
      maxEnteredTime: Instant? = null,
  ): Map<SpeciesId, Int> {
    return fetchPlantSummary(listOf(layerId), minEnteredTime, maxEnteredTime)
  }

  fun fetchPlantSummary(
      siteId: SiteId,
      minEnteredTime: Instant? = null,
      maxEnteredTime: Instant? = null,
  ): Map<SpeciesId, Int> {
    if (!currentUser().canReadSite(siteId)) {
      return emptyMap()
    }

    val layerIds =
        dslContext
            .select(LAYERS.ID)
            .from(LAYERS)
            .where(LAYERS.SITE_ID.eq(siteId))
            .fetch(LAYERS.ID)
            .filterNotNull()

    return fetchPlantSummary(layerIds, minEnteredTime, maxEnteredTime)
  }

  fun fetchPlantSummary(
      projectId: ProjectId,
      minEnteredTime: Instant? = null,
      maxEnteredTime: Instant? = null,
  ): Map<SpeciesId, Int> {
    if (!currentUser().canReadProject(projectId)) {
      return emptyMap()
    }

    val layerIds =
        dslContext
            .select(LAYERS.ID)
            .from(LAYERS)
            .join(SITES)
            .on(LAYERS.SITE_ID.eq(SITES.ID))
            .where(SITES.PROJECT_ID.eq(projectId))
            .fetch(LAYERS.ID)
            .filterNotNull()

    return fetchPlantSummary(layerIds, minEnteredTime, maxEnteredTime)
  }

  fun fetchPlantSummary(
      organizationId: OrganizationId,
      minEnteredTime: Instant? = null,
      maxEnteredTime: Instant? = null,
  ): Map<SpeciesId, Int> {
    if (!currentUser().canReadOrganization(organizationId)) {
      return emptyMap()
    }

    val layerIds =
        dslContext
            .select(LAYERS.ID)
            .from(LAYERS)
            .join(SITES)
            .on(LAYERS.SITE_ID.eq(SITES.ID))
            .join(PROJECTS)
            .on(SITES.PROJECT_ID.eq(PROJECTS.ID))
            .where(PROJECTS.ORGANIZATION_ID.eq(organizationId))
            .fetch(LAYERS.ID)
            .filterNotNull()

    return fetchPlantSummary(layerIds, minEnteredTime, maxEnteredTime)
  }

  private fun noPermissionsCheckFetch(id: FeatureId): FeatureModel? {
    return selectFromFeatures().where(FEATURES.ID.eq(id)).fetchOne { recordToModel(it) }
  }

  private fun selectFromFeatures(): SelectOnConditionStep<Record> {
    return dslContext
        .select(
            FEATURES.ID,
            FEATURES.LAYER_ID,
            FEATURES.GEOM.transformSrid(SRID.LONG_LAT).`as`(FEATURES.GEOM),
            FEATURES.GPS_HORIZ_ACCURACY,
            FEATURES.GPS_VERT_ACCURACY,
            FEATURES.ATTRIB,
            FEATURES.NOTES,
            FEATURES.ENTERED_TIME,
            FEATURES.CREATED_TIME,
            FEATURES.MODIFIED_TIME,
            PLANTS.asterisk(),
        )
        .from(FEATURES)
        .leftJoin(PLANTS)
        .on(FEATURES.ID.eq(PLANTS.FEATURE_ID))
  }

  private fun recordToModel(record: Record): FeatureModel {
    val plant =
        if (record[PLANTS.FEATURE_ID] != null) {
          record.into(PlantsRow::class.java)
        } else {
          null
        }

    return FeatureModel(
        id = record[FEATURES.ID],
        layerId = record[FEATURES.LAYER_ID]!!,
        geom = record[FEATURES.GEOM],
        gpsHorizAccuracy = record[FEATURES.GPS_HORIZ_ACCURACY],
        gpsVertAccuracy = record[FEATURES.GPS_VERT_ACCURACY],
        attrib = record[FEATURES.ATTRIB],
        notes = record[FEATURES.NOTES],
        enteredTime = record[FEATURES.ENTERED_TIME],
        createdTime = record[FEATURES.CREATED_TIME],
        modifiedTime = record[FEATURES.MODIFIED_TIME],
        plant = plant,
    )
  }
}
