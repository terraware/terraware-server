package com.terraformation.backend.seedbank.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.ParentFetcher
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.seedbank.model.AccessionModel
import jakarta.inject.Named
import java.time.LocalDate
import java.time.ZoneId

@Named
class AccessionHelper(
    private val parentFetcher: ParentFetcher,
) {

  fun collectionTimeZone(facilityId: FacilityId): ZoneId =
      currentUser().timeZone ?: parentFetcher.getEffectiveTimeZone(facilityId)

  fun effectiveCollectedDate(
      accession: AccessionModel,
      facilityId: FacilityId,
  ): LocalDate? =
      if (accession.collectedTime != null) {
        LocalDate.ofInstant(accession.collectedTime, collectionTimeZone(facilityId))
      } else {
        accession.collectedDate
      }
}
