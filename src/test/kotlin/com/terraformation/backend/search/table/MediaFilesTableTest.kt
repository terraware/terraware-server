package com.terraformation.backend.search.table

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.tracking.ObservationMediaType
import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.mockUser
import com.terraformation.backend.search.NoConditionNode
import com.terraformation.backend.search.SearchFieldPrefix
import com.terraformation.backend.search.SearchService
import com.terraformation.backend.search.SearchSortField
import io.mockk.every
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class MediaFilesTableTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val clock = TestClock()
  private val tables = SearchTables(clock)
  private val mediaFilesPrefix = SearchFieldPrefix(tables.mediaFiles)
  private val fileIdField = mediaFilesPrefix.resolve("fileId")

  private lateinit var searchService: SearchService

  @BeforeEach
  fun setUp() {
    searchService = SearchService(dslContext)

    every { user.canReadOrganization(any()) } returns true
    every { user.organizationRoles } returns mapOf(insertOrganization() to Role.Manager)
  }

  @Test
  fun `returns observation media files alongside organization media files`() {
    insertPlantingSite(x = 0, width = 11)
    insertMonitoringPlot()
    insertObservation()
    insertObservationPlot()

    val orgFileId = insertFile(contentType = "video/mp4")
    insertOrganizationMediaFile(fileId = orgFileId)

    val obsFileId = insertFile(contentType = "video/mp4")
    insertObservationMediaFile(fileId = obsFileId)

    val result =
        searchService.search(
            mediaFilesPrefix,
            listOf(fileIdField),
            mapOf(mediaFilesPrefix to NoConditionNode()),
            sortOrder = listOf(SearchSortField(fileIdField)),
        )

    assertEquals(
        listOf(
            mapOf("fileId" to "$orgFileId"),
            mapOf("fileId" to "$obsFileId"),
        ),
        result.results,
    )
  }

  @Test
  fun `exposes observation-specific fields`() {
    insertPlantingSite(x = 0, width = 11)
    insertMonitoringPlot()
    insertObservation()
    insertObservationPlot()

    val obsFileId = insertFile(contentType = "video/mp4")
    insertObservationMediaFile(
        fileId = obsFileId,
        isOriginal = false,
        position = ObservationPlotPosition.NortheastCorner,
        type = ObservationMediaType.Soil,
    )

    val fields =
        listOf(
            mediaFilesPrefix.resolve("fileId"),
            mediaFilesPrefix.resolve("isOriginal"),
            mediaFilesPrefix.resolve("position"),
            mediaFilesPrefix.resolve("type"),
        )

    val result =
        searchService.search(
            mediaFilesPrefix,
            fields,
            mapOf(mediaFilesPrefix to NoConditionNode()),
            sortOrder = listOf(SearchSortField(fileIdField)),
        )

    assertEquals(
        listOf(
            mapOf(
                "fileId" to "$obsFileId",
                "isOriginal" to "false",
                "position" to "NortheastCorner",
                "type" to "Soil",
            ),
        ),
        result.results,
    )
  }

  @Test
  fun `exposes observation and monitoringPlot sublists for observation media`() {
    insertPlantingSite(x = 0, width = 11)
    val monitoringPlotId = insertMonitoringPlot()
    val observationId = insertObservation()
    insertObservationPlot()

    val obsFileId = insertFile(contentType = "video/mp4")
    insertObservationMediaFile(fileId = obsFileId)

    val fields =
        listOf(
            mediaFilesPrefix.resolve("fileId"),
            mediaFilesPrefix.resolve("observation.id"),
            mediaFilesPrefix.resolve("monitoringPlot.id"),
        )

    val result =
        searchService.search(
            mediaFilesPrefix,
            fields,
            mapOf(mediaFilesPrefix to NoConditionNode()),
            sortOrder = listOf(SearchSortField(fileIdField)),
        )

    assertEquals(
        listOf(
            mapOf(
                "fileId" to "$obsFileId",
                "observation" to mapOf("id" to "$observationId"),
                "monitoringPlot" to mapOf("id" to "$monitoringPlotId"),
            ),
        ),
        result.results,
    )
  }

  @Test
  fun `organization-level media has no observation or monitoringPlot sublists`() {
    val orgFileId = insertFile(contentType = "video/mp4")
    insertOrganizationMediaFile(fileId = orgFileId)

    val fields =
        listOf(
            mediaFilesPrefix.resolve("fileId"),
            mediaFilesPrefix.resolve("observation.id"),
            mediaFilesPrefix.resolve("monitoringPlot.id"),
        )

    val result =
        searchService.search(
            mediaFilesPrefix,
            fields,
            mapOf(mediaFilesPrefix to NoConditionNode()),
            sortOrder = listOf(SearchSortField(fileIdField)),
        )

    assertEquals(
        listOf(mapOf("fileId" to "$orgFileId")),
        result.results,
    )
  }

  @Test
  fun `default order returns newest media first`() {
    val oldFileId = insertFile(contentType = "video/mp4", createdTime = Instant.ofEpochSecond(100))
    insertOrganizationMediaFile(fileId = oldFileId)

    val newFileId = insertFile(contentType = "video/mp4", createdTime = Instant.ofEpochSecond(500))
    insertOrganizationMediaFile(fileId = newFileId)

    val result =
        searchService.search(
            mediaFilesPrefix,
            listOf(mediaFilesPrefix.resolve("fileId")),
            mapOf(mediaFilesPrefix to NoConditionNode()),
        )

    assertEquals(
        listOf(
            mapOf("fileId" to "$newFileId"),
            mapOf("fileId" to "$oldFileId"),
        ),
        result.results,
    )
  }

  @Test
  fun `user without org role sees no media files`() {
    insertPlantingSite(x = 0, width = 11)
    insertMonitoringPlot()
    insertObservation()
    insertObservationPlot()

    val orgFileId = insertFile(contentType = "video/mp4")
    insertOrganizationMediaFile(fileId = orgFileId)

    val obsFileId = insertFile(contentType = "video/mp4")
    insertObservationMediaFile(fileId = obsFileId)

    // Drop the user's org roles.
    every { user.organizationRoles } returns emptyMap()

    val result =
        searchService.search(
            mediaFilesPrefix,
            listOf(mediaFilesPrefix.resolve("fileId")),
            mapOf(mediaFilesPrefix to NoConditionNode()),
        )

    assertEquals(emptyList<Map<String, String>>(), result.results)
  }
}
