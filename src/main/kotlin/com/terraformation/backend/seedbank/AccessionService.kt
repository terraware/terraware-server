package com.terraformation.backend.seedbank

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.seedbank.db.AccessionStore
import com.terraformation.backend.seedbank.db.PhotoRepository
import javax.annotation.ManagedBean

@ManagedBean
class AccessionService(
    private val accessionStore: AccessionStore,
    private val photoRepository: PhotoRepository,
) {
  /** Deletes an accession and all its associated data. */
  fun deleteAccession(accessionId: AccessionId) {
    requirePermissions { deleteAccession(accessionId) }

    // Note that this is not wrapped in a transaction; if this bombs out after having deleted some
    // but not all photos from the file store, we don't want to roll back the removal of the photos
    // that were successfully deleted or else we'll end up with dangling storage URLs.
    photoRepository.deleteAllPhotos(accessionId)
    accessionStore.delete(accessionId)
  }
}
