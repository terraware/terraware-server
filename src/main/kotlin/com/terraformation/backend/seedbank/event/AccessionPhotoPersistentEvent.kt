package com.terraformation.backend.seedbank.event

import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.eventlog.EntityCreatedPersistentEvent
import com.terraformation.backend.eventlog.PersistentEvent

sealed interface AccessionPhotoPersistentEvent : PersistentEvent {
  val fileId: FileId
  val accessionId: AccessionId
  val facilityId: FacilityId
  val organizationId: OrganizationId
}

/** Published when a photo is added to an accession under a new filename. */
data class AccessionPhotoAddedEventV1(
    val filename: String,
    val contentType: String,
    override val fileId: FileId,
    override val accessionId: AccessionId,
    override val facilityId: FacilityId,
    override val organizationId: OrganizationId,
) : EntityCreatedPersistentEvent, AccessionPhotoPersistentEvent

typealias AccessionPhotoAddedEvent = AccessionPhotoAddedEventV1

/**
 * Published when a photo with an existing filename is re-uploaded. This creates a new file and
 * deletes the file it replaced, so it is neither a plain creation nor a plain deletion.
 */
data class AccessionPhotoReplacedEventV1(
    val filename: String,
    val replacedFileId: FileId,
    override val fileId: FileId,
    override val accessionId: AccessionId,
    override val facilityId: FacilityId,
    override val organizationId: OrganizationId,
) : AccessionPhotoPersistentEvent

typealias AccessionPhotoReplacedEvent = AccessionPhotoReplacedEventV1
