package com.terraformation.backend.tracking.db.plantingSiteStore

import com.terraformation.backend.tracking.db.PlantingSiteNotFoundException
import com.terraformation.backend.tracking.model.PlantingSiteHistoryEventModel
import com.terraformation.backend.tracking.model.PlantingSiteHistoryEventType
import io.mockk.every
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class PlantingSiteStoreFetchSiteHistoryEventsTest : BasePlantingSiteStoreTest() {
  @Test
  fun `returns creation and edit events newest first`() {
    val creatorId = insertUser(firstName = "Creator", lastName = "User")
    val editorId = insertUser(firstName = "Editor", lastName = "User")

    val plantingSiteId =
        insertPlantingSite(createdBy = creatorId, createdTime = Instant.ofEpochSecond(1000))
    val createdHistoryId = inserted.plantingSiteHistoryId
    val editedHistoryId1 =
        insertPlantingSiteHistory(createdBy = editorId, createdTime = Instant.ofEpochSecond(2000))
    val editedHistoryId2 =
        insertPlantingSiteHistory(createdBy = creatorId, createdTime = Instant.ofEpochSecond(3000))

    // Another site's history shouldn't be included.
    insertPlantingSite(createdBy = editorId)
    insertPlantingSiteHistory()

    assertEquals(
        listOf(
            PlantingSiteHistoryEventModel(
                createdBy = creatorId,
                createdByName = "Creator User",
                createdTime = Instant.ofEpochSecond(3000),
                historyId = editedHistoryId2,
                type = PlantingSiteHistoryEventType.Edited,
            ),
            PlantingSiteHistoryEventModel(
                createdBy = editorId,
                createdByName = "Editor User",
                createdTime = Instant.ofEpochSecond(2000),
                historyId = editedHistoryId1,
                type = PlantingSiteHistoryEventType.Edited,
            ),
            PlantingSiteHistoryEventModel(
                createdBy = creatorId,
                createdByName = "Creator User",
                createdTime = Instant.ofEpochSecond(1000),
                historyId = createdHistoryId,
                type = PlantingSiteHistoryEventType.Created,
            ),
        ),
        store.fetchSiteHistoryEvents(plantingSiteId),
    )
  }

  @Test
  fun `returns empty list for site without a map`() {
    val plantingSiteId = insertPlantingSite(x = null)

    assertEquals(
        emptyList<PlantingSiteHistoryEventModel>(),
        store.fetchSiteHistoryEvents(plantingSiteId),
    )
  }

  @Test
  fun `throws exception if no permission to read planting site`() {
    val plantingSiteId = insertPlantingSite()

    every { user.canReadPlantingSite(any()) } returns false

    assertThrows<PlantingSiteNotFoundException> { store.fetchSiteHistoryEvents(plantingSiteId) }
  }
}
