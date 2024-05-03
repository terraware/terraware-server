package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.assertJsonEquals
import com.terraformation.backend.customer.model.InternalTagIds
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.EventStatus
import com.terraformation.backend.db.accelerator.EventType
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.i18n.Locales
import com.terraformation.backend.i18n.use
import com.terraformation.backend.mockUser
import com.terraformation.backend.search.NoConditionNode
import com.terraformation.backend.search.SearchFieldPrefix
import com.terraformation.backend.search.SearchResults
import com.terraformation.backend.search.SearchService
import com.terraformation.backend.search.table.SearchTables
import io.mockk.every
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ModuleEventSearchTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()
  private val searchService: SearchService by lazy { SearchService(dslContext) }
  private val searchTables = SearchTables(clock)

  @BeforeEach
  fun setUp() {
    insertUser()
    insertOrganization()
    insertOrganizationUser(
        userId = inserted.userId, organizationId = inserted.organizationId, role = Role.Admin)
    insertOrganizationInternalTag(tagId = InternalTagIds.Accelerator)
    insertCohort()
    insertParticipant(cohortId = inserted.cohortId)
    insertModule()

    every { user.canReadAllAcceleratorDetails() } returns true
    every { user.organizationRoles } returns mapOf(inserted.organizationId to Role.Admin)
  }

  @Test
  fun `return event details`() {
    val startTime = LocalDate.of(2024, 1, 1).atTime(8, 0, 0).toInstant(ZoneOffset.UTC)
    val endTime = LocalDate.of(2024, 1, 1).atTime(9, 0, 0).toInstant(ZoneOffset.UTC)
    insertEvent(
        moduleId = inserted.moduleId,
        eventStatus = EventStatus.NotStarted,
        eventType = EventType.Workshop,
        meetingUrl = "https://meet.google.com",
        slidesUrl = "https://slides.google.com",
        recordingUrl = "https://recording.google.com",
        startTime = startTime,
        endTime = endTime)

    val prefix = SearchFieldPrefix(searchTables.events)
    val fields =
        listOf(
                "id",
                "status",
                "type",
                "startTime",
                "endTime",
                "meetingUrl",
                "slidesUrl",
                "recordingUrl",
            )
            .map { prefix.resolve(it) }

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "${inserted.eventId}",
                    "status" to EventStatus.NotStarted.getDisplayName(Locales.GIBBERISH),
                    "type" to EventType.Workshop.getDisplayName(Locales.GIBBERISH),
                    "startTime" to startTime.toString(),
                    "endTime" to endTime.toString(),
                    "meetingUrl" to "https://meet.google.com",
                    "slidesUrl" to "https://slides.google.com",
                    "recordingUrl" to "https://recording.google.com",
                )),
            null)

    val actual = Locales.GIBBERISH.use { searchService.search(prefix, fields, NoConditionNode()) }

    assertJsonEquals(expected, actual)
  }

  @Test
  fun `can search for associated projects`() {
    val project1 = insertProject(participantId = inserted.participantId)
    val project2 = insertProject(participantId = inserted.participantId)
    val project3 = insertProject(participantId = inserted.participantId)
    val event1 = insertEvent()
    insertEventProject(event1, project1)
    insertEventProject(event1, project2)
    insertEventProject(event1, project3)

    val project4 = insertProject(participantId = inserted.participantId)
    val project5 = insertProject(participantId = inserted.participantId)
    val project6 = insertProject(participantId = inserted.participantId)
    val event2 = insertEvent()
    insertEventProject(event2, project4)
    insertEventProject(event2, project5)
    insertEventProject(event2, project6)

    val prefix = SearchFieldPrefix(searchTables.events)
    val fields = listOf("id", "projects.id").map { prefix.resolve(it) }

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to event1.toString(),
                    "projects" to
                        listOf(
                            mapOf("id" to project1.toString()),
                            mapOf("id" to project2.toString()),
                            mapOf("id" to project3.toString())),
                ),
                mapOf(
                    "id" to event2.toString(),
                    "projects" to
                        listOf(
                            mapOf("id" to project4.toString()),
                            mapOf("id" to project5.toString()),
                            mapOf("id" to project6.toString())),
                ),
            ),
            null)

    val actual = searchService.search(prefix, fields, NoConditionNode())

    assertJsonEquals(expected, actual)
  }

  @Test
  fun `can search for events by projects`() {
    val project1 = insertProject(participantId = inserted.participantId)
    val project2 = insertProject(participantId = inserted.participantId)
    val event1 = insertEvent()
    val event2 = insertEvent()
    val event3 = insertEvent()
    val event4 = insertEvent()
    insertEventProject(event1, project1)
    insertEventProject(event2, project1)
    insertEventProject(event3, project1)
    insertEventProject(event3, project2)
    insertEventProject(event4, project2)

    val prefix = SearchFieldPrefix(searchTables.projects)
    val fields = listOf("id", "events.id").map { prefix.resolve(it) }

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to project1.toString(),
                    "events" to
                        listOf(
                            mapOf("id" to event1.toString()),
                            mapOf("id" to event2.toString()),
                            mapOf("id" to event3.toString())),
                ),
                mapOf(
                    "id" to project2.toString(),
                    "events" to
                        listOf(mapOf("id" to event3.toString()), mapOf("id" to event4.toString())),
                ),
            ),
            null)

    val actual = searchService.search(prefix, fields, NoConditionNode())

    assertJsonEquals(expected, actual)
  }

  @Test
  fun `can only search for events for organization projects for non accelerator admins`() {
    val userProject =
        insertProject(
            organizationId = inserted.organizationId, participantId = inserted.participantId)

    val otherUser = insertUser(100)
    val otherOrganization = insertOrganization(id = 100, createdBy = otherUser)
    insertOrganizationUser(
        userId = otherUser, organizationId = otherOrganization, role = Role.Admin)
    val otherParticipant = insertParticipant(cohortId = inserted.cohortId)
    val otherProject =
        insertProject(
            organizationId = otherOrganization,
            participantId = otherParticipant,
            createdBy = otherUser)

    val event1 = insertEvent()
    val event2 = insertEvent()
    val event3 = insertEvent()
    val event4 = insertEvent()
    insertEventProject(event1, userProject)
    insertEventProject(event2, userProject)
    insertEventProject(event3, userProject)
    insertEventProject(event3, otherProject)
    insertEventProject(event4, otherProject)

    every { user.canReadAllAcceleratorDetails() } returns false

    val projectPrefix = SearchFieldPrefix(searchTables.projects)
    val projectFields = listOf("id", "events.id").map { projectPrefix.resolve(it) }
    val projectExpected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to userProject.toString(),
                    "events" to
                        listOf(
                            mapOf("id" to event1.toString()),
                            mapOf("id" to event2.toString()),
                            mapOf("id" to event3.toString())),
                ),
            ),
            null)
    val projectActual = searchService.search(projectPrefix, projectFields, NoConditionNode())

    val eventPrefix = SearchFieldPrefix(searchTables.events)
    val eventFields = listOf("id").map { eventPrefix.resolve(it) }
    val eventExpected =
        SearchResults(
            listOf(
                mapOf("id" to event1.toString()),
                mapOf("id" to event2.toString()),
                mapOf("id" to event3.toString())),
            null)
    val eventActual = searchService.search(eventPrefix, eventFields, NoConditionNode())

    assertJsonEquals(projectExpected, projectActual)
    assertJsonEquals(eventExpected, eventActual)
  }
}
