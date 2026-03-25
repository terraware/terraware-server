package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.accelerator.model.ExistingCommonIndicatorModel
import com.terraformation.backend.accelerator.model.ExistingProjectIndicatorModel
import com.terraformation.backend.accelerator.model.NewCommonIndicatorModel
import com.terraformation.backend.accelerator.model.NewProjectIndicatorModel
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.CommonIndicatorNotFoundException
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.ProjectIndicatorNotFoundException
import com.terraformation.backend.db.accelerator.AutoCalculatedIndicator
import com.terraformation.backend.db.accelerator.CommonIndicatorId
import com.terraformation.backend.db.accelerator.IndicatorCategory
import com.terraformation.backend.db.accelerator.IndicatorClass
import com.terraformation.backend.db.accelerator.IndicatorFrequency
import com.terraformation.backend.db.accelerator.IndicatorLevel
import com.terraformation.backend.db.accelerator.ProjectIndicatorId
import com.terraformation.backend.db.accelerator.tables.records.CommonIndicatorsRecord
import com.terraformation.backend.db.accelerator.tables.records.ProjectIndicatorsRecord
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.Role
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class ReportIndicatorStoreTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val store: ReportIndicatorStore by lazy { ReportIndicatorStore(dslContext) }

  @BeforeEach
  fun setup() {
    insertUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
  }

  @Nested
  inner class Fetch {

    @Nested
    inner class FetchOneCommonIndicator {
      @Test
      fun `returns one common indicator`() {
        val indicatorId =
            insertCommonIndicator(
                category = IndicatorCategory.Climate,
                classId = IndicatorClass.Cumulative,
                description = "Climate common indicator description",
                frequency = IndicatorFrequency.Annual,
                isPublishable = true,
                level = IndicatorLevel.Process,
                name = "Climate Common Indicator",
                notes = "Some notes",
                primaryDataSource = "Primary source",
                refId = "3.0",
                unit = "degrees",
            )

        assertEquals(
            ExistingCommonIndicatorModel(
                id = indicatorId,
                active = true,
                category = IndicatorCategory.Climate,
                classId = IndicatorClass.Cumulative,
                description = "Climate common indicator description",
                frequency = IndicatorFrequency.Annual,
                isPublishable = true,
                level = IndicatorLevel.Process,
                name = "Climate Common Indicator",
                notes = "Some notes",
                precision = 0,
                primaryDataSource = "Primary source",
                refId = "3.0",
                tfOwner = "Carbon",
                unit = "degrees",
            ),
            store.fetchOneCommonIndicator(indicatorId),
        )
      }

      @Test
      fun `throws not found exception if no indicator found`() {
        assertThrows<CommonIndicatorNotFoundException> {
          store.fetchOneCommonIndicator(CommonIndicatorId(-1))
        }
      }
    }

    @Nested
    inner class FetchAllCommonIndicators {
      @Test
      fun `returns all common indicators`() {
        val commonIndicatorId1 =
            insertCommonIndicator(
                category = IndicatorCategory.Climate,
                description = "Climate common indicator description",
                level = IndicatorLevel.Process,
                name = "Climate Common Indicator",
                refId = "3.0",
                unit = "%",
            )

        val commonIndicatorId2 =
            insertCommonIndicator(
                category = IndicatorCategory.Community,
                description = "Community indicator description",
                level = IndicatorLevel.Outcome,
                name = "Community Indicator",
                refId = "5.0",
                unit = "meters",
            )

        val commonIndicatorId3 =
            insertCommonIndicator(
                category = IndicatorCategory.ProjectObjectives,
                description = "Project objectives indicator description",
                isPublishable = false,
                level = IndicatorLevel.Goal,
                name = "Project Objectives Indicator",
                precision = 2,
                refId = "3.0",
                unit = "cm",
            )

        assertEquals(
            listOf(
                // Ordered by reference then ID
                ExistingCommonIndicatorModel(
                    id = commonIndicatorId1,
                    category = IndicatorCategory.Climate,
                    classId = IndicatorClass.Level,
                    description = "Climate common indicator description",
                    isPublishable = true,
                    level = IndicatorLevel.Process,
                    name = "Climate Common Indicator",
                    precision = 0,
                    refId = "3.0",
                    tfOwner = "Carbon",
                    unit = "%",
                ),
                ExistingCommonIndicatorModel(
                    id = commonIndicatorId3,
                    category = IndicatorCategory.ProjectObjectives,
                    classId = IndicatorClass.Level,
                    description = "Project objectives indicator description",
                    isPublishable = false,
                    level = IndicatorLevel.Goal,
                    name = "Project Objectives Indicator",
                    precision = 2,
                    refId = "3.0",
                    tfOwner = "Carbon",
                    unit = "cm",
                ),
                ExistingCommonIndicatorModel(
                    id = commonIndicatorId2,
                    category = IndicatorCategory.Community,
                    classId = IndicatorClass.Level,
                    description = "Community indicator description",
                    isPublishable = true,
                    level = IndicatorLevel.Outcome,
                    name = "Community Indicator",
                    precision = 0,
                    refId = "5.0",
                    tfOwner = "Carbon",
                    unit = "meters",
                ),
            ),
            store.fetchAllCommonIndicators(),
        )
      }

      @Test
      fun `sorted by reference correctly`() {
        val indicatorId1 = insertCommonIndicator(refId = "2.0.2")
        val indicatorId2 = insertCommonIndicator(refId = "10.0")
        val indicatorId3 = insertCommonIndicator(refId = "1.0")
        val indicatorId4 = insertCommonIndicator(refId = "2.0")
        val indicatorId5 = insertCommonIndicator(refId = "1.1")
        val indicatorId6 = insertCommonIndicator(refId = "1.1.1")
        val indicatorId7 = insertCommonIndicator(refId = "1.2")

        assertEquals(
            listOf(
                indicatorId3, // 1.0
                indicatorId5, // 1.1
                indicatorId6, // 1.1.1
                indicatorId7, // 1.2
                indicatorId4, // 2.0
                indicatorId1, // 2.0.2
                indicatorId2, // 10.0
            ),
            store.fetchAllCommonIndicators().map { it.id },
        )
      }
    }

    @Nested
    inner class FetchOneProjectIndicator {
      @Test
      fun `returns one project indicator`() {
        insertOrganization()
        val projectId = insertProject()
        val indicatorId =
            insertProjectIndicator(
                category = IndicatorCategory.Climate,
                classId = IndicatorClass.Level,
                description = "Climate project indicator description",
                frequency = IndicatorFrequency.BiAnnual,
                isPublishable = false,
                level = IndicatorLevel.Process,
                name = "Climate Project Indicator",
                notes = "Project notes",
                precision = 2,
                primaryDataSource = "Project source",
                projectId = projectId,
                refId = "3.0",
                unit = "degrees",
            )

        assertEquals(
            ExistingProjectIndicatorModel(
                id = indicatorId,
                active = true,
                projectId = projectId,
                category = IndicatorCategory.Climate,
                classId = IndicatorClass.Level,
                description = "Climate project indicator description",
                frequency = IndicatorFrequency.BiAnnual,
                isPublishable = false,
                level = IndicatorLevel.Process,
                name = "Climate Project Indicator",
                notes = "Project notes",
                precision = 2,
                primaryDataSource = "Project source",
                refId = "3.0",
                tfOwner = "Carbon",
                unit = "degrees",
            ),
            store.fetchOneProjectIndicator(indicatorId),
        )
      }

      @Test
      fun `throws not found exception if no indicator found`() {
        assertThrows<ProjectIndicatorNotFoundException> {
          store.fetchOneProjectIndicator(ProjectIndicatorId(-1))
        }
      }

      @Test
      fun `sorted by reference correctly`() {
        insertOrganization()
        val projectId = insertProject()

        val indicatorId1 = insertProjectIndicator(refId = "2.0.2")
        val indicatorId2 = insertProjectIndicator(refId = "10.0")
        val indicatorId3 = insertProjectIndicator(refId = "1.0")
        val indicatorId4 = insertProjectIndicator(refId = "2.0")
        val indicatorId5 = insertProjectIndicator(refId = "1.1")
        val indicatorId6 = insertProjectIndicator(refId = "1.1.1")
        val indicatorId7 = insertProjectIndicator(refId = "1.2")

        assertEquals(
            listOf(
                indicatorId3, // 1.0
                indicatorId5, // 1.1
                indicatorId6, // 1.1.1
                indicatorId7, // 1.2
                indicatorId4, // 2.0
                indicatorId1, // 2.0.2
                indicatorId2, // 10.0
            ),
            store.fetchProjectIndicatorsForProject(projectId).map { it.id },
        )
      }

      @Test
      fun `throws access denied exception for non-global role users and non-admins`() {
        insertOrganization()
        insertOrganizationUser(role = Role.Contributor)
        val projectId = insertProject()
        val indicatorId =
            insertProjectIndicator(
                category = IndicatorCategory.Climate,
                description = "Climate common indicator description",
                name = "Climate Common Indicator",
                projectId = projectId,
                refId = "3.0",
                level = IndicatorLevel.Process,
            )

        deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
        assertThrows<AccessDeniedException> { store.fetchOneProjectIndicator(indicatorId) }

        deleteOrganizationUser()
        insertOrganizationUser(role = Role.Admin)
        assertDoesNotThrow { store.fetchOneProjectIndicator(indicatorId) }

        deleteOrganizationUser()
        insertUserGlobalRole(role = GlobalRole.ReadOnly)
        assertDoesNotThrow { store.fetchOneProjectIndicator(indicatorId) }
      }
    }

    @Nested
    inner class FetchProjectIndicatorsForProject {
      @Test
      fun `returns all project indicators for one project`() {
        insertOrganization()
        val projectId = insertProject()
        val indicatorId1 =
            insertProjectIndicator(
                category = IndicatorCategory.Climate,
                description = "Climate common indicator description",
                level = IndicatorLevel.Process,
                name = "Climate Common Indicator",
                projectId = projectId,
                refId = "3.0",
                unit = "%",
            )

        val indicatorId2 =
            insertProjectIndicator(
                category = IndicatorCategory.Community,
                description = "Community indicator description",
                isPublishable = false,
                level = IndicatorLevel.Outcome,
                name = "Community Indicator",
                projectId = projectId,
                refId = "5.0",
                unit = "meters",
            )

        val indicatorId3 =
            insertProjectIndicator(
                category = IndicatorCategory.ProjectObjectives,
                description = "Project objectives indicator description",
                level = IndicatorLevel.Goal,
                name = "Project Objectives Indicator",
                precision = 2,
                projectId = projectId,
                refId = "3.0",
                unit = "cm",
            )

        // Other project indicators will not be returned
        val otherProjectId = insertProject()
        insertProjectIndicator(projectId = otherProjectId)

        assertEquals(
            listOf(
                // Ordered by reference then ID
                ExistingProjectIndicatorModel(
                    id = indicatorId1,
                    projectId = projectId,
                    category = IndicatorCategory.Climate,
                    classId = IndicatorClass.Level,
                    description = "Climate common indicator description",
                    isPublishable = true,
                    level = IndicatorLevel.Process,
                    name = "Climate Common Indicator",
                    precision = 0,
                    refId = "3.0",
                    tfOwner = "Carbon",
                    unit = "%",
                ),
                ExistingProjectIndicatorModel(
                    id = indicatorId3,
                    projectId = projectId,
                    category = IndicatorCategory.ProjectObjectives,
                    classId = IndicatorClass.Level,
                    description = "Project objectives indicator description",
                    isPublishable = true,
                    level = IndicatorLevel.Goal,
                    name = "Project Objectives Indicator",
                    precision = 2,
                    refId = "3.0",
                    tfOwner = "Carbon",
                    unit = "cm",
                ),
                ExistingProjectIndicatorModel(
                    id = indicatorId2,
                    projectId = projectId,
                    category = IndicatorCategory.Community,
                    classId = IndicatorClass.Level,
                    description = "Community indicator description",
                    isPublishable = false,
                    level = IndicatorLevel.Outcome,
                    name = "Community Indicator",
                    precision = 0,
                    refId = "5.0",
                    tfOwner = "Carbon",
                    unit = "meters",
                ),
            ),
            store.fetchProjectIndicatorsForProject(projectId),
        )
      }

      @Test
      fun `throws access denied exception for non-global role users and non-admins`() {
        insertOrganization()
        insertOrganizationUser(role = Role.Contributor)
        val projectId = insertProject()

        deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
        assertThrows<AccessDeniedException> { store.fetchProjectIndicatorsForProject(projectId) }

        deleteOrganizationUser()
        insertOrganizationUser(role = Role.Admin)
        assertDoesNotThrow { store.fetchProjectIndicatorsForProject(projectId) }

        insertUserGlobalRole(role = GlobalRole.ReadOnly)
        assertDoesNotThrow { store.fetchProjectIndicatorsForProject(projectId) }
      }
    }

    @Nested
    inner class FetchAutoCalculatedIndicators {
      @Test
      fun `returns all auto calculated indicators, ordered by reference`() {
        val sortedAutoCalculatedIndicators =
            AutoCalculatedIndicator.entries.sortedWith { indicator1, indicator2 ->
              val indicator1Parts = indicator1.refId.split(".").map { it.toInt() }
              val indicator2Parts = indicator2.refId.split(".").map { it.toInt() }

              val size = maxOf(indicator1Parts.size, indicator2Parts.size)
              for (i in 0 until size) {
                val part1 = indicator1Parts.getOrElse(i) { 0 }
                val part2 = indicator2Parts.getOrElse(i) { 0 }
                if (part1 != part2) {
                  return@sortedWith part1.compareTo(part2)
                }
              }
              return@sortedWith 0
            }

        assertEquals(sortedAutoCalculatedIndicators, store.fetchAutoCalculatedIndicators())
      }
    }
  }

  @Nested
  inner class Create {
    @Nested
    inner class CommonIndicators {
      @Test
      fun `inserts new record`() {
        val existingIndicatorId =
            insertCommonIndicator(
                category = IndicatorCategory.Climate,
                description = "Climate common indicator description",
                level = IndicatorLevel.Process,
                name = "Climate Common Indicator",
                precision = 2,
                refId = "3.0",
                unit = "%",
            )

        val model =
            NewCommonIndicatorModel(
                id = null,
                category = IndicatorCategory.ProjectObjectives,
                classId = IndicatorClass.Level,
                description = "Project objectives indicator description",
                frequency = IndicatorFrequency.MRVCycle,
                isPublishable = false,
                level = IndicatorLevel.Goal,
                name = "Project Objectives Indicator",
                notes = "Creation notes",
                precision = 2,
                primaryDataSource = "Creation source",
                refId = "1.0",
                tfOwner = "Biodiversity",
                unit = "meters",
            )

        val newIndicatorId = store.createCommonIndicator(model)

        assertTableEquals(
            listOf(
                CommonIndicatorsRecord(
                    id = existingIndicatorId,
                    active = true,
                    categoryId = IndicatorCategory.Climate,
                    classId = IndicatorClass.Level,
                    description = "Climate common indicator description",
                    isPublishable = true,
                    levelId = IndicatorLevel.Process,
                    name = "Climate Common Indicator",
                    precision = 2,
                    refId = "3.0",
                    tfOwner = "Carbon",
                    unit = "%",
                ),
                CommonIndicatorsRecord(
                    id = newIndicatorId,
                    active = true,
                    categoryId = IndicatorCategory.ProjectObjectives,
                    classId = IndicatorClass.Level,
                    description = "Project objectives indicator description",
                    frequencyId = IndicatorFrequency.MRVCycle,
                    isPublishable = false,
                    levelId = IndicatorLevel.Goal,
                    name = "Project Objectives Indicator",
                    notes = "Creation notes",
                    precision = 2,
                    primaryDataSource = "Creation source",
                    refId = "1.0",
                    tfOwner = "Biodiversity",
                    unit = "meters",
                ),
            )
        )
      }

      @Test
      fun `persists active = false when explicitly set`() {
        val model =
            NewCommonIndicatorModel(
                id = null,
                active = false,
                category = IndicatorCategory.ProjectObjectives,
                classId = IndicatorClass.Level,
                description = "Inactive indicator description",
                isPublishable = false,
                level = IndicatorLevel.Goal,
                name = "Inactive Common Indicator",
                precision = 0,
                refId = "9.9",
                unit = "%",
            )

        val newIndicatorId = store.createCommonIndicator(model)

        assertTableEquals(
            listOf(
                CommonIndicatorsRecord(
                    id = newIndicatorId,
                    active = false,
                    categoryId = IndicatorCategory.ProjectObjectives,
                    classId = IndicatorClass.Level,
                    description = "Inactive indicator description",
                    isPublishable = false,
                    levelId = IndicatorLevel.Goal,
                    name = "Inactive Common Indicator",
                    precision = 0,
                    refId = "9.9",
                    tfOwner = null,
                    unit = "%",
                )
            )
        )
      }

      @Test
      fun `throws access denied exception for non-accelerator admin`() {
        val model =
            NewCommonIndicatorModel(
                id = null,
                category = IndicatorCategory.ProjectObjectives,
                classId = IndicatorClass.Level,
                description = "Project objectives indicator description",
                isPublishable = true,
                name = "Project Objectives Indicator",
                precision = 2,
                refId = "1.0",
                level = IndicatorLevel.Goal,
                unit = "%",
            )

        deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
        insertUserGlobalRole(role = GlobalRole.TFExpert)
        assertThrows<AccessDeniedException> { store.createCommonIndicator(model) }
      }
    }

    @Nested
    inner class ProjectIndicator {
      @Test
      fun `inserts new record`() {
        insertOrganization()
        val projectId = insertProject()
        val existingIndicatorId =
            insertProjectIndicator(
                category = IndicatorCategory.Climate,
                description = "Climate common indicator description",
                level = IndicatorLevel.Process,
                name = "Climate Common Indicator",
                precision = 0,
                projectId = projectId,
                refId = "3.0",
                unit = "meters",
            )

        val model =
            NewProjectIndicatorModel(
                id = null,
                projectId = projectId,
                category = IndicatorCategory.ProjectObjectives,
                classId = IndicatorClass.Cumulative,
                description = "Project objectives indicator description",
                frequency = IndicatorFrequency.Annual,
                isPublishable = false,
                level = IndicatorLevel.Goal,
                name = "Project Objectives Indicator",
                notes = "Project creation notes",
                precision = 2,
                primaryDataSource = "Project creation source",
                refId = "1.0",
                tfOwner = "Biodiversity",
                unit = "%",
            )

        val newIndicatorId = store.createProjectIndicator(model)

        assertTableEquals(
            listOf(
                ProjectIndicatorsRecord(
                    id = existingIndicatorId,
                    active = true,
                    categoryId = IndicatorCategory.Climate,
                    classId = IndicatorClass.Level,
                    description = "Climate common indicator description",
                    isPublishable = true,
                    levelId = IndicatorLevel.Process,
                    name = "Climate Common Indicator",
                    precision = 0,
                    projectId = projectId,
                    refId = "3.0",
                    tfOwner = "Carbon",
                    unit = "meters",
                ),
                ProjectIndicatorsRecord(
                    id = newIndicatorId,
                    active = true,
                    categoryId = IndicatorCategory.ProjectObjectives,
                    classId = IndicatorClass.Cumulative,
                    description = "Project objectives indicator description",
                    frequencyId = IndicatorFrequency.Annual,
                    isPublishable = false,
                    levelId = IndicatorLevel.Goal,
                    name = "Project Objectives Indicator",
                    notes = "Project creation notes",
                    precision = 2,
                    primaryDataSource = "Project creation source",
                    projectId = projectId,
                    refId = "1.0",
                    tfOwner = "Biodiversity",
                    unit = "%",
                ),
            )
        )
      }

      @Test
      fun `persists active = false when explicitly set`() {
        insertOrganization()
        val projectId = insertProject()
        val model =
            NewProjectIndicatorModel(
                id = null,
                active = false,
                projectId = projectId,
                category = IndicatorCategory.ProjectObjectives,
                classId = IndicatorClass.Level,
                description = "Inactive project indicator description",
                isPublishable = false,
                level = IndicatorLevel.Goal,
                name = "Inactive Project Indicator",
                precision = 0,
                refId = "9.9",
                unit = "kg",
            )

        val newIndicatorId = store.createProjectIndicator(model)

        assertTableEquals(
            listOf(
                ProjectIndicatorsRecord(
                    id = newIndicatorId,
                    active = false,
                    categoryId = IndicatorCategory.ProjectObjectives,
                    classId = IndicatorClass.Level,
                    description = "Inactive project indicator description",
                    isPublishable = false,
                    levelId = IndicatorLevel.Goal,
                    name = "Inactive Project Indicator",
                    precision = 0,
                    projectId = projectId,
                    refId = "9.9",
                    tfOwner = null,
                    unit = "kg",
                )
            )
        )
      }

      @Test
      fun `throws access denied exception for non-accelerator admin`() {
        insertOrganization()
        val projectId = insertProject()
        val model =
            NewProjectIndicatorModel(
                id = null,
                category = IndicatorCategory.ProjectObjectives,
                classId = IndicatorClass.Level,
                description = "Project objectives indicator description",
                isPublishable = true,
                name = "Project Objectives Indicator",
                precision = 2,
                projectId = projectId,
                refId = "1.0",
                level = IndicatorLevel.Goal,
            )

        deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
        insertUserGlobalRole(role = GlobalRole.TFExpert)
        assertThrows<AccessDeniedException> { store.createProjectIndicator(model) }
      }
    }
  }

  @Nested
  inner class Update {
    @Nested
    inner class CommonIndicators {
      @Test
      fun `updates existing record`() {
        val existingIndicatorId =
            insertCommonIndicator(
                category = IndicatorCategory.Climate,
                description = "Climate common indicator description",
                level = IndicatorLevel.Process,
                name = "Climate Common Indicator",
                precision = 0,
                refId = "3.0",
                unit = "%",
            )

        val updated =
            ExistingCommonIndicatorModel(
                id = CommonIndicatorId(99), // this field is ignored
                category = IndicatorCategory.ProjectObjectives,
                classId = IndicatorClass.Cumulative,
                description = "Project objectives indicator description",
                frequency = IndicatorFrequency.BiAnnual,
                isPublishable = false,
                level = IndicatorLevel.Goal,
                name = "Project Objectives Indicator",
                notes = "Updated notes",
                precision = 2,
                primaryDataSource = "Updated source",
                refId = "1.0",
                tfOwner = "Biodiversity",
                unit = "meters",
            )

        store.updateCommonIndicator(existingIndicatorId) { updated }

        assertTableEquals(
            listOf(
                CommonIndicatorsRecord(
                    id = existingIndicatorId,
                    active = true,
                    categoryId = IndicatorCategory.ProjectObjectives,
                    classId = IndicatorClass.Cumulative,
                    description = "Project objectives indicator description",
                    frequencyId = IndicatorFrequency.BiAnnual,
                    isPublishable = false,
                    levelId = IndicatorLevel.Goal,
                    name = "Project Objectives Indicator",
                    notes = "Updated notes",
                    precision = 2,
                    primaryDataSource = "Updated source",
                    refId = "1.0",
                    tfOwner = "Biodiversity",
                    unit = "meters",
                )
            )
        )
      }

      @Test
      fun `persists active change from true to false`() {
        val existingIndicatorId =
            insertCommonIndicator(
                active = true,
                category = IndicatorCategory.Climate,
                description = "Climate common indicator description",
                level = IndicatorLevel.Process,
                name = "Climate Common Indicator",
                refId = "3.0",
                unit = "%",
            )

        store.updateCommonIndicator(existingIndicatorId) { it.copy(active = false) }

        assertTableEquals(
            listOf(
                CommonIndicatorsRecord(
                    id = existingIndicatorId,
                    active = false,
                    categoryId = IndicatorCategory.Climate,
                    classId = IndicatorClass.Level,
                    description = "Climate common indicator description",
                    isPublishable = true,
                    levelId = IndicatorLevel.Process,
                    name = "Climate Common Indicator",
                    precision = 0,
                    refId = "3.0",
                    tfOwner = "Carbon",
                    unit = "%",
                )
            )
        )
      }

      @Test
      fun `throws access denied exception for non-accelerator admin`() {
        val existingIndicatorId =
            insertCommonIndicator(
                category = IndicatorCategory.Climate,
                description = "Climate common indicator description",
                name = "Climate Common Indicator",
                refId = "3.0",
                level = IndicatorLevel.Process,
                unit = "%",
            )

        deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
        insertUserGlobalRole(role = GlobalRole.TFExpert)
        assertThrows<AccessDeniedException> {
          store.updateCommonIndicator(existingIndicatorId) { it.copy(refId = "1.0") }
        }
      }
    }
  }

  @Nested
  inner class ProjectIndicators {
    @Test
    fun `updates existing record`() {
      insertOrganization()
      val projectId = insertProject()
      val existingIndicatorId =
          insertProjectIndicator(
              category = IndicatorCategory.Climate,
              description = "Climate common indicator description",
              isPublishable = false,
              level = IndicatorLevel.Process,
              name = "Climate Common Indicator",
              precision = 0,
              projectId = projectId,
              refId = "3.0",
              unit = "feet",
          )

      val updated =
          ExistingProjectIndicatorModel(
              id = ProjectIndicatorId(99), // this field is ignored
              category = IndicatorCategory.ProjectObjectives,
              classId = IndicatorClass.Level,
              description = "Project objectives indicator description",
              frequency = IndicatorFrequency.MRVCycle,
              isPublishable = true,
              level = IndicatorLevel.Goal,
              name = "Project Objectives Indicator",
              notes = "Project updated notes",
              precision = 2,
              primaryDataSource = "Project updated source",
              projectId = ProjectId(99), // this field is ignored
              refId = "1.0",
              tfOwner = "Biodiversity",
              unit = "inches",
          )

      store.updateProjectIndicator(existingIndicatorId) { updated }

      assertTableEquals(
          listOf(
              ProjectIndicatorsRecord(
                  active = true,
                  categoryId = IndicatorCategory.ProjectObjectives,
                  classId = IndicatorClass.Level,
                  description = "Project objectives indicator description",
                  frequencyId = IndicatorFrequency.MRVCycle,
                  id = existingIndicatorId,
                  isPublishable = true,
                  levelId = IndicatorLevel.Goal,
                  name = "Project Objectives Indicator",
                  notes = "Project updated notes",
                  precision = 2,
                  primaryDataSource = "Project updated source",
                  projectId = projectId,
                  refId = "1.0",
                  tfOwner = "Biodiversity",
                  unit = "inches",
              )
          )
      )
    }

    @Test
    fun `persists active change from true to false`() {
      insertOrganization()
      val projectId = insertProject()
      val existingIndicatorId =
          insertProjectIndicator(
              active = true,
              category = IndicatorCategory.Climate,
              description = "Climate project indicator description",
              level = IndicatorLevel.Process,
              name = "Climate Project Indicator",
              projectId = projectId,
              refId = "3.0",
              unit = "feet",
          )

      store.updateProjectIndicator(existingIndicatorId) { it.copy(active = false) }

      assertTableEquals(
          listOf(
              ProjectIndicatorsRecord(
                  id = existingIndicatorId,
                  active = false,
                  categoryId = IndicatorCategory.Climate,
                  classId = IndicatorClass.Level,
                  description = "Climate project indicator description",
                  isPublishable = true,
                  levelId = IndicatorLevel.Process,
                  name = "Climate Project Indicator",
                  precision = 0,
                  projectId = projectId,
                  refId = "3.0",
                  tfOwner = "Carbon",
                  unit = "feet",
              )
          )
      )
    }

    @Test
    fun `throws access denied exception for non-accelerator admin`() {
      insertOrganization()
      val projectId = insertProject()
      val existingIndicatorId =
          insertProjectIndicator(
              category = IndicatorCategory.Climate,
              description = "Climate common indicator description",
              name = "Climate Common Indicator",
              projectId = projectId,
              refId = "3.0",
              level = IndicatorLevel.Process,
          )

      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
      insertUserGlobalRole(role = GlobalRole.TFExpert)
      assertThrows<AccessDeniedException> {
        store.updateProjectIndicator(existingIndicatorId) { it.copy(refId = "1.0") }
      }
    }
  }
}
