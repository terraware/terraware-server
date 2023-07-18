package com.terraformation.backend.customer

import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.nursery.db.BatchStore
import com.terraformation.backend.seedbank.db.AccessionStore
import com.terraformation.backend.tracking.db.PlantingSiteStore
import jakarta.inject.Named
import org.jooq.DSLContext

@Named
class ProjectService(
    private val accessionStore: AccessionStore,
    private val batchStore: BatchStore,
    private val dslContext: DSLContext,
    private val plantingSiteStore: PlantingSiteStore,
) {
  fun assignProject(
      projectId: ProjectId,
      accessionIds: Collection<AccessionId>,
      batchIds: Collection<BatchId>,
      plantingSiteIds: Collection<PlantingSiteId>
  ) {
    dslContext.transaction { _ ->
      accessionStore.assignProject(projectId, accessionIds)
      batchStore.assignProject(projectId, batchIds)
      plantingSiteStore.assignProject(projectId, plantingSiteIds)
    }
  }
}
