package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.accelerator.model.CohortModuleModel
import com.terraformation.backend.accelerator.model.EventModel
import com.terraformation.backend.accelerator.model.ModuleModel
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.accelerator.CohortId
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.EventType
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
    insertUser()
    insertOrganization()
    cohortId = insertCohort()
    insertParticipant(cohortId = inserted.cohortId)
    projectId = insertProject(participantId = inserted.participantId)

    every { user.canManageModules() } returns true
    every { user.canReadProject(any()) } returns true
    every { user.canReadModulesForProject(any()) } returns true
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
    fun `returns list of modules with null cohorts for module associated with cohort`() {
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

      insertCohortModule(cohortId, moduleId, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31))

      assertEquals(
          listOf(
              ModuleModel(
                  id = moduleId,
                  name = "TestModule",
                  phase = CohortPhase.Phase1FeasibilityStudy,
                  additionalResources = "<b> Additional Resources </b>",
                  cohorts =
                      listOf(
                          CohortModuleModel(
                              cohortId = cohortId,
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
    fun `returns list of modules according to project scopes`() {
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

      val otherModuleId =
          insertModule(name = "OtherTestModule", phase = CohortPhase.Phase0DueDiligence)

      val otherCohortId = insertCohort()
      val otherParticipantId = insertParticipant(cohortId = otherCohortId)
      val otherProjectId = insertProject(participantId = otherParticipantId)

      insertCohortModule(cohortId, moduleId, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31))
      insertCohortModule(
          otherCohortId, otherModuleId, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31))

      assertEquals(
          listOf(
              ModuleModel(
                  id = moduleId,
                  name = "TestModule",
                  phase = CohortPhase.Phase1FeasibilityStudy,
                  additionalResources = "<b> Additional Resources </b>",
                  cohorts =
                      listOf(
                          CohortModuleModel(
                              cohortId = cohortId,
                              startDate = LocalDate.of(2024, 1, 1),
                              endDate = LocalDate.of(2024, 1, 31),
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
          ),
          store.fetchModulesForProject(projectId))

      assertEquals(
          listOf(
              ModuleModel(
                  id = otherModuleId,
                  name = "OtherTestModule",
                  phase = CohortPhase.Phase0DueDiligence,
                  cohorts =
                      listOf(
                          CohortModuleModel(
                              cohortId = otherCohortId,
                              startDate = LocalDate.of(2024, 1, 1),
                              endDate = LocalDate.of(2024, 1, 31),
                          )),
              ),
          ),
          store.fetchModulesForProject(otherProjectId))
    }

    @Test
    fun `throws exception if no permission to read project modules`() {
      every { user.canReadModulesForProject(any()) } returns false
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
    fun `returns list of modules with no cohorts if not associated with cohort`() {
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
    fun `returns list of modules with cohorts if module associated with cohort`() {
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

      insertCohortModule(cohortId, moduleId, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31))

      assertEquals(
          listOf(
              ModuleModel(
                  id = moduleId,
                  name = "TestModule",
                  phase = CohortPhase.Phase1FeasibilityStudy,
                  additionalResources = "<b> Additional Resources </b>",
                  cohorts =
                      listOf(
                          CohortModuleModel(
                              cohortId = cohortId,
                              startDate = LocalDate.of(2024, 1, 1),
                              endDate = LocalDate.of(2024, 1, 31),
                              projects = setOf(projectId),
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
          store.fetchAllModules())
    }

    @Test
    fun `returns list of modules with multiple modules`() {
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

      val otherModuleId =
          insertModule(name = "OtherTestModule", phase = CohortPhase.Phase0DueDiligence)

      insertCohortModule(cohortId, moduleId, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31))

      assertEquals(
          listOf(
              ModuleModel(
                  id = moduleId,
                  name = "TestModule",
                  phase = CohortPhase.Phase1FeasibilityStudy,
                  additionalResources = "<b> Additional Resources </b>",
                  cohorts =
                      listOf(
                          CohortModuleModel(
                              cohortId = cohortId,
                              startDate = LocalDate.of(2024, 1, 1),
                              endDate = LocalDate.of(2024, 1, 31),
                              projects = setOf(projectId),
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
              ModuleModel(
                  id = otherModuleId,
                  name = "OtherTestModule",
                  phase = CohortPhase.Phase0DueDiligence,
              ),
          ),
          store.fetchAllModules())
    }

    @Test
    fun `returns list of modules with multiple modules and multiple cohorts`() {
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

      val otherModuleId =
          insertModule(name = "OtherTestModule", phase = CohortPhase.Phase0DueDiligence)

      val otherCohortId = insertCohort()
      val otherParticipantId = insertParticipant(cohortId = otherCohortId)
      val otherProjectId = insertProject(participantId = otherParticipantId)

      insertCohortModule(cohortId, moduleId, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31))
      insertCohortModule(
          otherCohortId, otherModuleId, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31))

      assertEquals(
          listOf(
              ModuleModel(
                  id = moduleId,
                  name = "TestModule",
                  phase = CohortPhase.Phase1FeasibilityStudy,
                  additionalResources = "<b> Additional Resources </b>",
                  cohorts =
                      listOf(
                          CohortModuleModel(
                              cohortId = cohortId,
                              startDate = LocalDate.of(2024, 1, 1),
                              endDate = LocalDate.of(2024, 1, 31),
                              projects = setOf(projectId),
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
              ModuleModel(
                  id = otherModuleId,
                  name = "OtherTestModule",
                  phase = CohortPhase.Phase0DueDiligence,
                  cohorts =
                      listOf(
                          CohortModuleModel(
                              cohortId = otherCohortId,
                              startDate = LocalDate.of(2024, 1, 1),
                              endDate = LocalDate.of(2024, 1, 31),
                              projects = setOf(otherProjectId),
                          )),
              ),
          ),
          store.fetchAllModules())
    }
  }

  @Nested
  inner class ModuleEvents {
    @Test
    fun `fetch all modules with events return events with details`() {
      val moduleId = insertModule(name = "ModuleName", phase = CohortPhase.Phase0DueDiligence)
      insertCohortModule(
          cohortId,
          moduleId,
          startDate = LocalDate.of(2024, 1, 1),
          endDate = LocalDate.of(2024, 1, 31),
      )
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

      assertEquals(
          listOf(
              ModuleModel(
                  id = moduleId,
                  name = "ModuleName",
                  phase = CohortPhase.Phase0DueDiligence,
                  cohorts =
                      listOf(
                          CohortModuleModel(
                              cohortId = cohortId,
                              startDate = LocalDate.of(2024, 1, 1),
                              endDate = LocalDate.of(2024, 1, 31),
                              projects = setOf(projectId),
                          )),
                  eventSessions =
                      mapOf(
                          EventType.Workshop to
                              listOf(
                                  EventModel(
                                      id = eventId,
                                      endTime = Instant.ofEpochSecond(4000),
                                      meetingURL = URI("https://example.com/meeting"),
                                      projects = setOf(projectId),
                                      recordingURL = URI("https://example.com/recording"),
                                      slidesURL = URI("https://example.com/slides"),
                                      startTime = Instant.ofEpochSecond(1000),
                                  )))),
          ),
          store.fetchAllModules())
    }

    @Test
    fun `fetch all modules with events with multiple projects`() {
      val anotherParticipantId = insertParticipant(cohortId = cohortId)
      val anotherProjectId = insertProject(participantId = anotherParticipantId)
      val moduleId = insertModule(name = "ModuleName", phase = CohortPhase.Phase0DueDiligence)
      insertCohortModule(
          cohortId,
          moduleId,
          startDate = LocalDate.of(2024, 1, 1),
          endDate = LocalDate.of(2024, 1, 31),
      )
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
      insertEventProject(eventId, anotherProjectId)

      assertEquals(
          listOf(
              ModuleModel(
                  id = moduleId,
                  name = "ModuleName",
                  phase = CohortPhase.Phase0DueDiligence,
                  cohorts =
                      listOf(
                          CohortModuleModel(
                              cohortId = cohortId,
                              startDate = LocalDate.of(2024, 1, 1),
                              endDate = LocalDate.of(2024, 1, 31),
                              projects = setOf(projectId, anotherProjectId),
                          )),
                  eventSessions =
                      mapOf(
                          EventType.Workshop to
                              listOf(
                                  EventModel(
                                      id = eventId,
                                      endTime = Instant.ofEpochSecond(4000),
                                      meetingURL = URI("https://example.com/meeting"),
                                      projects = setOf(projectId, anotherProjectId),
                                      recordingURL = URI("https://example.com/recording"),
                                      slidesURL = URI("https://example.com/slides"),
                                      startTime = Instant.ofEpochSecond(1000),
                                  )))),
          ),
          store.fetchAllModules())
    }

    @Test
    fun `fetch all modules with multiple events and multiple projects`() {
      val anotherParticipantId = insertParticipant(cohortId = cohortId)
      val anotherProjectId = insertProject(participantId = anotherParticipantId)
      val moduleId = insertModule(name = "ModuleName", phase = CohortPhase.Phase0DueDiligence)
      insertCohortModule(
          cohortId,
          moduleId,
          startDate = LocalDate.of(2024, 1, 1),
          endDate = LocalDate.of(2024, 1, 31),
      )
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

      val anotherEventId =
          insertEvent(
              moduleId = moduleId,
              eventType = EventType.Workshop,
              meetingUrl = URI("https://example.com/meeting-another"),
              slidesUrl = URI("https://example.com/slides-another"),
              recordingUrl = URI("https://example.com/recording-another"),
              startTime = Instant.ofEpochSecond(1000),
              endTime = Instant.ofEpochSecond(4000),
          )
      insertEventProject(eventId, projectId)
      insertEventProject(anotherEventId, anotherProjectId)

      assertEquals(
          listOf(
              ModuleModel(
                  id = moduleId,
                  name = "ModuleName",
                  phase = CohortPhase.Phase0DueDiligence,
                  cohorts =
                      listOf(
                          CohortModuleModel(
                              cohortId = cohortId,
                              startDate = LocalDate.of(2024, 1, 1),
                              endDate = LocalDate.of(2024, 1, 31),
                              projects = setOf(projectId, anotherProjectId),
                          )),
                  eventSessions =
                      mapOf(
                          EventType.Workshop to
                              listOf(
                                  EventModel(
                                      id = eventId,
                                      endTime = Instant.ofEpochSecond(4000),
                                      meetingURL = URI("https://example.com/meeting"),
                                      projects = setOf(projectId),
                                      recordingURL = URI("https://example.com/recording"),
                                      slidesURL = URI("https://example.com/slides"),
                                      startTime = Instant.ofEpochSecond(1000),
                                  ),
                                  EventModel(
                                      id = anotherEventId,
                                      endTime = Instant.ofEpochSecond(4000),
                                      meetingURL = URI("https://example.com/meeting-another"),
                                      projects = setOf(anotherProjectId),
                                      recordingURL = URI("https://example.com/recording-another"),
                                      slidesURL = URI("https://example.com/slides-another"),
                                      startTime = Instant.ofEpochSecond(1000),
                                  ),
                              ))),
          ),
          store.fetchAllModules())
    }

    @Test
    fun `fetch project modules leaves event projects empty`() {
      val anotherParticipantId = insertParticipant(cohortId = cohortId)
      val anotherProjectId = insertProject(participantId = anotherParticipantId)
      val moduleId = insertModule(name = "ModuleName", phase = CohortPhase.Phase0DueDiligence)
      insertCohortModule(
          cohortId,
          moduleId,
          startDate = LocalDate.of(2024, 1, 1),
          endDate = LocalDate.of(2024, 1, 31),
      )
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
      insertEventProject(eventId, anotherProjectId)

      val expected =
          listOf(
              ModuleModel(
                  id = moduleId,
                  name = "ModuleName",
                  phase = CohortPhase.Phase0DueDiligence,
                  cohorts =
                      listOf(
                          CohortModuleModel(
                              cohortId = cohortId,
                              startDate = LocalDate.of(2024, 1, 1),
                              endDate = LocalDate.of(2024, 1, 31),
                              projects = null,
                          )),
                  eventSessions =
                      mapOf(
                          EventType.Workshop to
                              listOf(
                                  EventModel(
                                      id = eventId,
                                      endTime = Instant.ofEpochSecond(4000),
                                      meetingURL = URI("https://example.com/meeting"),
                                      projects = null,
                                      recordingURL = URI("https://example.com/recording"),
                                      slidesURL = URI("https://example.com/slides"),
                                      startTime = Instant.ofEpochSecond(1000),
                                  ),
                              ))),
          )

      assertEquals(
          expected,
          store.fetchModulesForProject(projectId),
      )

      assertEquals(
          expected,
          store.fetchModulesForProject(anotherProjectId),
      )
    }

    @Test
    fun `fetch project modules returns events that include project`() {
      val anotherParticipantId = insertParticipant(cohortId = cohortId)
      val anotherProjectId = insertProject(participantId = anotherParticipantId)
      val moduleId = insertModule(name = "ModuleName", phase = CohortPhase.Phase0DueDiligence)
      insertCohortModule(
          cohortId,
          moduleId,
          startDate = LocalDate.of(2024, 1, 1),
          endDate = LocalDate.of(2024, 1, 31),
      )
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

      val anotherEventId =
          insertEvent(
              moduleId = moduleId,
              eventType = EventType.LiveSession,
              meetingUrl = URI("https://example.com/meeting-another"),
              slidesUrl = URI("https://example.com/slides-another"),
              recordingUrl = URI("https://example.com/recording-another"),
              startTime = Instant.ofEpochSecond(1000),
              endTime = Instant.ofEpochSecond(4000),
          )
      insertEventProject(eventId, projectId)
      insertEventProject(anotherEventId, anotherProjectId)

      assertEquals(
          listOf(
              ModuleModel(
                  id = moduleId,
                  name = "ModuleName",
                  phase = CohortPhase.Phase0DueDiligence,
                  cohorts =
                      listOf(
                          CohortModuleModel(
                              cohortId = cohortId,
                              startDate = LocalDate.of(2024, 1, 1),
                              endDate = LocalDate.of(2024, 1, 31),
                              projects = null,
                          )),
                  eventSessions =
                      mapOf(
                          EventType.Workshop to
                              listOf(
                                  EventModel(
                                      id = eventId,
                                      endTime = Instant.ofEpochSecond(4000),
                                      meetingURL = URI("https://example.com/meeting"),
                                      projects = null,
                                      recordingURL = URI("https://example.com/recording"),
                                      slidesURL = URI("https://example.com/slides"),
                                      startTime = Instant.ofEpochSecond(1000),
                                  ),
                              ))),
          ),
          store.fetchModulesForProject(projectId),
      )

      assertEquals(
          listOf(
              ModuleModel(
                  id = moduleId,
                  name = "ModuleName",
                  phase = CohortPhase.Phase0DueDiligence,
                  cohorts =
                      listOf(
                          CohortModuleModel(
                              cohortId = cohortId,
                              startDate = LocalDate.of(2024, 1, 1),
                              endDate = LocalDate.of(2024, 1, 31),
                              projects = null,
                          )),
                  eventSessions =
                      mapOf(
                          EventType.LiveSession to
                              listOf(
                                  EventModel(
                                      id = anotherEventId,
                                      endTime = Instant.ofEpochSecond(4000),
                                      meetingURL = URI("https://example.com/meeting-another"),
                                      projects = null,
                                      recordingURL = URI("https://example.com/recording-another"),
                                      slidesURL = URI("https://example.com/slides-another"),
                                      startTime = Instant.ofEpochSecond(1000),
                                  ),
                              ))),
          ),
          store.fetchModulesForProject(anotherProjectId),
      )
    }
  }
}
