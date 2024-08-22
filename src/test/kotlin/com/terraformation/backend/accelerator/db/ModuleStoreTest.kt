package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.accelerator.model.CohortModuleModel
import com.terraformation.backend.accelerator.model.EventModel
import com.terraformation.backend.accelerator.model.ModuleDeliverableModel
import com.terraformation.backend.accelerator.model.ModuleModel
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.accelerator.CohortId
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.DeliverableCategory
import com.terraformation.backend.db.accelerator.DeliverableType
import com.terraformation.backend.db.accelerator.EventStatus
import com.terraformation.backend.db.accelerator.EventType
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.mockUser
import io.mockk.every
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.access.AccessDeniedException

class ModuleStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val store: ModuleStore by lazy { ModuleStore(dslContext) }
  private lateinit var cohortId: CohortId
  private lateinit var projectId: ProjectId

  @BeforeEach
  fun setUp() {
    insertOrganization()
    cohortId = insertCohort()
    insertParticipant(cohortId = inserted.cohortId)
    projectId = insertProject(participantId = inserted.participantId)

    every { user.canManageModules() } returns true
    every { user.canReadModule(any()) } returns true
    every { user.canReadModuleDetails(any()) } returns true
    every { user.canReadModuleEvent(any()) } returns true
    every { user.canReadModuleEventParticipants() } returns true
    every { user.canReadProject(any()) } returns true
    every { user.canReadProjectModules(any()) } returns true
  }

  @Nested
  inner class ModulesTableConstraints {
    @Test
    fun `throws exception if cohort module end date is before start date`() {
      val moduleId = insertModule()
      assertThrows<DataIntegrityViolationException> {
        insertCohortModule(
            cohortId,
            moduleId,
            startDate = LocalDate.of(2024, 1, 31),
            endDate = LocalDate.of(2024, 1, 30),
        )
      }
    }

    @Test
    fun `throws exception if cohort modules dates overlap`() {
      val moduleId = insertModule()
      val otherModuleId = insertModule()

      insertCohortModule(
          cohortId,
          moduleId,
          startDate = LocalDate.of(2024, 1, 1),
          endDate = LocalDate.of(2024, 1, 15),
      )

      assertThrows<DataIntegrityViolationException> {
        insertCohortModule(
            cohortId,
            otherModuleId,
            startDate = LocalDate.of(2024, 1, 15),
            endDate = LocalDate.of(2024, 1, 30),
        )
      }
    }

    @Test
    fun `throws exception if event end time is before start time`() {
      insertModule()

      assertThrows<DataIntegrityViolationException> {
        insertEvent(startTime = Instant.ofEpochSecond(500), endTime = Instant.ofEpochSecond(400))
      }
    }
  }

  @Nested
  inner class FetchOneById {
    @Test
    fun `returns one module with all cohorts`() {
      val otherCohortId = insertCohort()
      val otherParticipantId = insertParticipant(cohortId = otherCohortId)
      val otherProjectId = insertProject(participantId = otherParticipantId)

      val moduleId =
          insertModule(
              additionalResources = "<b> Additional Resources </b>",
              liveSessionDescription = "Live session lectures",
              name = "TestModule",
              oneOnOneSessionDescription = "1:1 meetings",
              overview = "<h> Overview </h>",
              preparationMaterials = "<i> Preps </i>",
              phase = CohortPhase.Phase1FeasibilityStudy,
              workshopDescription = "Workshop ideas",
          )

      insertCohortModule(
          cohortId, moduleId, "1", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31))
      insertCohortModule(
          otherCohortId, moduleId, "2", LocalDate.of(2024, 2, 1), LocalDate.of(2024, 2, 28))

      assertEquals(
          ModuleModel(
              id = moduleId,
              name = "TestModule",
              phase = CohortPhase.Phase1FeasibilityStudy,
              position = 1,
              additionalResources = "<b> Additional Resources </b>",
              cohorts =
                  listOf(
                      CohortModuleModel(
                          cohortId = cohortId,
                          moduleId = moduleId,
                          title = "1",
                          startDate = LocalDate.of(2024, 1, 1),
                          endDate = LocalDate.of(2024, 1, 31),
                          projects = setOf(projectId),
                      ),
                      CohortModuleModel(
                          cohortId = otherCohortId,
                          moduleId = moduleId,
                          title = "2",
                          startDate = LocalDate.of(2024, 2, 1),
                          endDate = LocalDate.of(2024, 2, 28),
                          projects = setOf(otherProjectId),
                      )),
              eventDescriptions =
                  mapOf(
                      EventType.LiveSession to "Live session lectures",
                      EventType.OneOnOneSession to "1:1 meetings",
                      EventType.Workshop to "Workshop ideas",
                  ),
              eventSessions = emptyMap(),
              overview = "<h> Overview </h>",
              preparationMaterials = "<i> Preps </i>",
          ),
          store.fetchOneById(moduleId))
    }

    @Test
    fun `throws exception if no permission to read module details`() {
      val moduleId = insertModule()
      every { user.canReadModule(any()) } returns false
      every { user.canReadModuleDetails(any()) } returns false
      assertThrows<ModuleNotFoundException> { store.fetchOneById(moduleId) }

      every { user.canReadModule(any()) } returns true
      assertThrows<AccessDeniedException> { store.fetchOneById(moduleId) }
    }

    @Test
    fun `throws exception if no module found`() {
      assertThrows<ModuleNotFoundException> { store.fetchOneById(ModuleId(-1)) }
    }
  }

  @Nested
  inner class FetchOneByIdForProject {
    @Test
    fun `returns one module with project cohort`() {
      val moduleId =
          insertModule(
              additionalResources = "<b> Additional Resources </b>",
              liveSessionDescription = "Live session lectures",
              name = "TestModule",
              oneOnOneSessionDescription = "1:1 meetings",
              overview = "<h> Overview </h>",
              preparationMaterials = "<i> Preps </i>",
              phase = CohortPhase.Phase1FeasibilityStudy,
              workshopDescription = "Workshop ideas",
          )

      insertCohortModule(
          cohortId, moduleId, "1", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31))

      assertEquals(
          ModuleModel(
              id = moduleId,
              name = "TestModule",
              phase = CohortPhase.Phase1FeasibilityStudy,
              position = 1,
              additionalResources = "<b> Additional Resources </b>",
              cohorts =
                  listOf(
                      CohortModuleModel(
                          cohortId = cohortId,
                          moduleId = moduleId,
                          title = "1",
                          startDate = LocalDate.of(2024, 1, 1),
                          endDate = LocalDate.of(2024, 1, 31),
                          projects = null,
                      )),
              eventDescriptions =
                  mapOf(
                      EventType.LiveSession to "Live session lectures",
                      EventType.OneOnOneSession to "1:1 meetings",
                      EventType.Workshop to "Workshop ideas",
                  ),
              eventSessions = emptyMap(),
              overview = "<h> Overview </h>",
              preparationMaterials = "<i> Preps </i>",
          ),
          store.fetchOneByIdForProject(moduleId, projectId))
    }

    @Test
    fun `throws exception if no permission to read module or project`() {
      val moduleId = insertModule()
      insertCohortModule(cohortId, moduleId)

      every { user.canReadProject(any()) } returns false
      every { user.canReadModule(any()) } returns false
      assertThrows<ModuleNotFoundException> { store.fetchOneByIdForProject(moduleId, projectId) }

      every { user.canReadModule(any()) } returns true
      assertThrows<ProjectNotFoundException> { store.fetchOneByIdForProject(moduleId, projectId) }
    }

    @Test
    fun `throws exception if no module found`() {
      assertThrows<ModuleNotFoundException> {
        store.fetchOneByIdForProject(ModuleId(-1), projectId)
      }
    }

    @Test
    fun `throws exception if project not associated with module found`() {
      val moduleId = insertModule()
      assertThrows<ModuleNotFoundException> { store.fetchOneByIdForProject(moduleId, projectId) }
    }
  }

  @Nested
  inner class FetchModulesForProject {
    @Test
    fun `returns empty list of modules if no module added`() {
      assertEquals(emptyList<ModuleModel>(), store.fetchModulesForProject(projectId))
    }

    @Test
    fun `returns empty list of modules if module not associated with cohort`() {
      insertModule()
      assertEquals(emptyList<ModuleModel>(), store.fetchModulesForProject(projectId))
    }

    @Test
    fun `returns module with details`() {
      val moduleId =
          insertModule(
              additionalResources = "<b> Additional Resources </b>",
              liveSessionDescription = "Live session lectures",
              name = "TestModule",
              oneOnOneSessionDescription = "1:1 meetings",
              overview = "<h> Overview </h>",
              preparationMaterials = "<i> Preps </i>",
              phase = CohortPhase.Phase1FeasibilityStudy,
              workshopDescription = "Workshop ideas",
          )

      insertCohortModule(
          cohortId, moduleId, "1", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31))

      assertEquals(
          listOf(
              ModuleModel(
                  id = moduleId,
                  name = "TestModule",
                  phase = CohortPhase.Phase1FeasibilityStudy,
                  position = 1,
                  additionalResources = "<b> Additional Resources </b>",
                  cohorts =
                      listOf(
                          CohortModuleModel(
                              cohortId = cohortId,
                              moduleId = moduleId,
                              title = "1",
                              startDate = LocalDate.of(2024, 1, 1),
                              endDate = LocalDate.of(2024, 1, 31),
                              projects = null, // other projects of cohorts not visible
                          )),
                  eventDescriptions =
                      mapOf(
                          EventType.LiveSession to "Live session lectures",
                          EventType.OneOnOneSession to "1:1 meetings",
                          EventType.Workshop to "Workshop ideas",
                      ),
                  eventSessions = emptyMap(),
                  overview = "<h> Overview </h>",
                  preparationMaterials = "<i> Preps </i>",
              )),
          store.fetchModulesForProject(projectId))
    }

    @Test
    fun `returns list of modules associated with project cohorts only`() {
      val moduleId = insertModule()
      val otherModuleId = insertModule()

      val otherCohortId = insertCohort()
      val otherParticipantId = insertParticipant(cohortId = otherCohortId)
      val otherProjectId = insertProject(participantId = otherParticipantId)

      insertCohortModule(cohortId, moduleId)
      insertCohortModule(otherCohortId, otherModuleId)

      val fetchModules = store.fetchModulesForProject(projectId)
      val otherFetchModules = store.fetchModulesForProject(otherProjectId)

      assertEquals(listOf(moduleId), fetchModules.map { it.id })
      assertEquals(listOf(otherModuleId), otherFetchModules.map { it.id })
    }

    @Test
    fun `throws exception if no permission to read project modules`() {
      every { user.canReadProjectModules(any()) } returns false
      assertThrows<AccessDeniedException> { store.fetchModulesForProject(inserted.projectId) }

      every { user.canReadProject(any()) } returns false
      assertThrows<ProjectNotFoundException> { store.fetchModulesForProject(inserted.projectId) }
    }
  }

  @Nested
  inner class FetchAllModules {
    @Test
    fun `returns empty list of module if no module added`() {
      assertEquals(emptyList<ModuleModel>(), store.fetchAllModules())
    }

    @Test
    fun `throws exception if no permission to manage modules`() {
      every { user.canManageModules() } returns false

      assertThrows<AccessDeniedException> { store.fetchAllModules() }
    }

    @Test
    fun `returns list of modules with details`() {
      val moduleId =
          insertModule(
              additionalResources = "<b> Additional Resources </b>",
              liveSessionDescription = "Live session lectures",
              name = "TestModule",
              oneOnOneSessionDescription = "1:1 meetings",
              overview = "<h> Overview </h>",
              preparationMaterials = "<i> Preps </i>",
              phase = CohortPhase.Phase1FeasibilityStudy,
              workshopDescription = "Workshop ideas",
          )

      assertEquals(
          listOf(
              ModuleModel(
                  id = moduleId,
                  name = "TestModule",
                  phase = CohortPhase.Phase1FeasibilityStudy,
                  position = 1,
                  additionalResources = "<b> Additional Resources </b>",
                  cohorts = emptyList(),
                  eventDescriptions =
                      mapOf(
                          EventType.LiveSession to "Live session lectures",
                          EventType.OneOnOneSession to "1:1 meetings",
                          EventType.Workshop to "Workshop ideas",
                      ),
                  eventSessions = emptyMap(),
                  overview = "<h> Overview </h>",
                  preparationMaterials = "<i> Preps </i>",
              )),
          store.fetchAllModules())
    }

    @Test
    fun `returns all modules`() {
      val moduleIds = (1..10).map { insertModule() }

      val fetchAllResult = store.fetchAllModules()
      assertEquals(moduleIds, fetchAllResult.map { it.id })
    }

    @Test
    fun `returns list of modules with with associated cohorts and projects`() {
      val moduleId = insertModule()
      val otherModuleId = insertModule()

      val otherCohortId = insertCohort()
      val otherParticipantId = insertParticipant(cohortId = otherCohortId)
      val otherProjectIds = (1..4).map { insertProject(participantId = otherParticipantId) }

      insertCohortModule(cohortId, moduleId)
      insertCohortModule(otherCohortId, moduleId)
      insertCohortModule(otherCohortId, otherModuleId)

      val fetchAllResult = store.fetchAllModules()
      assertEquals(listOf(moduleId, otherModuleId), fetchAllResult.map { it.id })

      val moduleCohorts = fetchAllResult.find { it.id == moduleId }!!.cohorts
      val otherModuleCohorts = fetchAllResult.find { it.id == otherModuleId }!!.cohorts

      assertEquals(listOf(cohortId, otherCohortId), moduleCohorts.map { it.cohortId })
      assertEquals(listOf(otherCohortId), otherModuleCohorts.map { it.cohortId })

      val cohortProjects = moduleCohorts.find { it.cohortId == cohortId }!!.projects
      val otherCohortProjects = moduleCohorts.find { it.cohortId == otherCohortId }!!.projects

      assertEquals(setOf(projectId), cohortProjects)
      assertEquals(otherProjectIds.toSet(), otherCohortProjects)
    }
  }

  @Nested
  inner class FetchAllModulesWithEvents {
    @Test
    fun `returns events with details`() {
      val moduleId = insertModule()
      insertCohortModule(cohortId, moduleId)

      val eventId =
          insertEvent(
              moduleId = moduleId,
              eventType = EventType.Workshop,
              meetingUrl = URI("https://example.com/meeting"),
              slidesUrl = URI("https://example.com/slides"),
              recordingUrl = URI("https://example.com/recording"),
              startTime = Instant.ofEpochSecond(1000),
              endTime = Instant.ofEpochSecond(4000),
          )
      insertEventProject(eventId, projectId)

      val fetchAllResult = store.fetchAllModules()

      assertEquals(
          mapOf(
              EventType.Workshop to
                  listOf(
                      EventModel(
                          id = eventId,
                          endTime = Instant.ofEpochSecond(4000),
                          eventStatus = EventStatus.NotStarted,
                          eventType = EventType.Workshop,
                          meetingUrl = URI("https://example.com/meeting"),
                          moduleId = moduleId,
                          projects = setOf(projectId),
                          recordingUrl = URI("https://example.com/recording"),
                          revision = 1,
                          slidesUrl = URI("https://example.com/slides"),
                          startTime = Instant.ofEpochSecond(1000),
                      ))),
          fetchAllResult.find { it.id == moduleId }!!.eventSessions)
    }

    @Test
    fun `returns events with multiple projects`() {
      val anotherParticipantId = insertParticipant(cohortId = cohortId)
      val anotherProjectId = insertProject(participantId = anotherParticipantId)
      val moduleId = insertModule(name = "ModuleName", phase = CohortPhase.Phase0DueDiligence)

      insertCohortModule(cohortId, moduleId)

      val eventId = insertEvent(moduleId = moduleId, eventType = EventType.Workshop)

      insertEventProject(eventId, projectId)
      insertEventProject(eventId, anotherProjectId)

      val fetchAllResult = store.fetchAllModules()
      val workshopEvent =
          fetchAllResult
              .find { it.id == moduleId }!!
              .eventSessions[EventType.Workshop]!!
              .firstOrNull()!!

      assertEquals(setOf(projectId, anotherProjectId), workshopEvent.projects)
    }

    @Test
    fun `returns modules with multiple events with or without projects`() {
      val moduleId = insertModule()
      insertCohortModule(cohortId, moduleId)

      val workshop1 = insertEvent(moduleId = moduleId, eventType = EventType.Workshop)
      val workshop2 = insertEvent(moduleId = moduleId, eventType = EventType.Workshop)
      val workshop3 = insertEvent(moduleId = moduleId, eventType = EventType.Workshop)
      val liveSession1 = insertEvent(moduleId = moduleId, eventType = EventType.LiveSession)
      val liveSession2 = insertEvent(moduleId = moduleId, eventType = EventType.LiveSession)
      val liveSession3 = insertEvent(moduleId = moduleId, eventType = EventType.LiveSession)
      val oneOnOneSession1 = insertEvent(moduleId = moduleId, eventType = EventType.OneOnOneSession)
      val oneOnOneSession2 = insertEvent(moduleId = moduleId, eventType = EventType.OneOnOneSession)
      val oneOnOneSession3 = insertEvent(moduleId = moduleId, eventType = EventType.OneOnOneSession)

      insertEventProject(workshop1, projectId)
      insertEventProject(liveSession1, projectId)
      insertEventProject(oneOnOneSession1, projectId)

      val fetchAllResult = store.fetchAllModules()
      val eventIds =
          fetchAllResult
              .find { it.id == moduleId }!!
              .eventSessions
              .mapValues { it.value.map { event -> event.id }.toSet() }

      assertEquals(
          mapOf(
              EventType.Workshop to setOf(workshop1, workshop2, workshop3),
              EventType.LiveSession to setOf(liveSession1, liveSession2, liveSession3),
              EventType.OneOnOneSession to
                  setOf(oneOnOneSession1, oneOnOneSession2, oneOnOneSession3),
          ),
          eventIds)
    }
  }

  @Nested
  inner class FetchModulesByProjectWithEvents {
    @Test
    fun `returns events with details`() {
      val moduleId = insertModule()
      insertCohortModule(cohortId, moduleId)

      val eventId =
          insertEvent(
              moduleId = moduleId,
              eventType = EventType.Workshop,
              meetingUrl = URI("https://example.com/meeting"),
              slidesUrl = URI("https://example.com/slides"),
              recordingUrl = URI("https://example.com/recording"),
              startTime = Instant.ofEpochSecond(1000),
              endTime = Instant.ofEpochSecond(4000),
          )
      insertEventProject(eventId, projectId)
      val fetchByProjectResult = store.fetchModulesForProject(projectId)

      assertEquals(
          mapOf(
              EventType.Workshop to
                  listOf(
                      EventModel(
                          id = eventId,
                          endTime = Instant.ofEpochSecond(4000),
                          eventStatus = EventStatus.NotStarted,
                          eventType = EventType.Workshop,
                          meetingUrl = URI("https://example.com/meeting"),
                          moduleId = moduleId,
                          projects = null,
                          recordingUrl = URI("https://example.com/recording"),
                          revision = 1,
                          slidesUrl = URI("https://example.com/slides"),
                          startTime = Instant.ofEpochSecond(1000),
                      ))),
          fetchByProjectResult.find { it.id == moduleId }!!.eventSessions)
    }

    @Test
    fun `returns only events that include project`() {
      val otherParticipantId = insertParticipant(cohortId = cohortId)
      val otherProjectId = insertProject(participantId = otherParticipantId)
      val moduleId = insertModule(name = "ModuleName", phase = CohortPhase.Phase0DueDiligence)

      insertCohortModule(
          cohortId,
          moduleId,
      )
      val eventId = insertEvent(moduleId = moduleId, eventType = EventType.OneOnOneSession)
      val anotherEventId = insertEvent(moduleId = moduleId, eventType = EventType.OneOnOneSession)

      insertEventProject(eventId, projectId)
      insertEventProject(anotherEventId, otherProjectId)

      val result = store.fetchModulesForProject(projectId)
      val otherResult = store.fetchModulesForProject(otherProjectId)

      assertEquals(
          listOf(eventId),
          result
              .find { it.id == moduleId }!!
              .eventSessions[EventType.OneOnOneSession]!!
              .map { it.id })

      assertEquals(
          listOf(anotherEventId),
          otherResult
              .find { it.id == moduleId }!!
              .eventSessions[EventType.OneOnOneSession]!!
              .map { it.id })
    }
  }

  @Nested
  inner class FetchModulesWithDeliverables {
    @Test
    fun `returns associated deliverables with details ordered by position`() {
      val moduleId = insertModule()
      val hiddenModuleId = insertModule(name = "Hidden Module")

      val deliverable1 =
          insertDeliverable(
              deliverableCategoryId = DeliverableCategory.Compliance,
              deliverableTypeId = DeliverableType.Document,
              descriptionHtml = "Description",
              isSensitive = true,
              isRequired = true,
              moduleId = moduleId,
              name = "Deliverable name",
              position = 2)

      val deliverable2 =
          insertDeliverable(
              deliverableCategoryId = DeliverableCategory.CarbonEligibility,
              deliverableTypeId = DeliverableType.Species,
              descriptionHtml = "Species description",
              isSensitive = false,
              isRequired = false,
              moduleId = moduleId,
              name = "Species name",
              position = 1)

      val hiddenDeliverable =
          insertDeliverable(
              deliverableCategoryId = DeliverableCategory.GIS,
              deliverableTypeId = DeliverableType.Document,
              descriptionHtml = "Hidden description",
              isSensitive = false,
              isRequired = false,
              moduleId = hiddenModuleId,
              name = "Hidden name",
              position = 3)

      val model1 =
          ModuleDeliverableModel(
              id = deliverable1,
              category = DeliverableCategory.Compliance,
              descriptionHtml = "Description",
              moduleId = moduleId,
              name = "Deliverable name",
              position = 2,
              required = true,
              sensitive = true,
              type = DeliverableType.Document,
          )

      val model2 =
          ModuleDeliverableModel(
              id = deliverable2,
              category = DeliverableCategory.CarbonEligibility,
              descriptionHtml = "Species description",
              moduleId = moduleId,
              name = "Species name",
              position = 1,
              required = false,
              sensitive = false,
              type = DeliverableType.Species,
          )

      val hiddenModel =
          ModuleDeliverableModel(
              id = hiddenDeliverable,
              category = DeliverableCategory.GIS,
              descriptionHtml = "Hidden description",
              moduleId = hiddenModuleId,
              name = "Hidden name",
              position = 3,
              required = false,
              sensitive = false,
              type = DeliverableType.Document,
          )

      assertEquals(
          listOf(model2, model1, hiddenModel),
          store.fetchAllModules().flatMap { it.deliverables },
          "Fetch all with deliverables")

      insertCohortModule(cohortId, moduleId)

      assertEquals(
          listOf(model2, model1),
          store.fetchModulesForProject(projectId).flatMap { it.deliverables },
          "Fetch by project with deliverables")
    }
  }
}
