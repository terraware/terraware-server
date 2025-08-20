package com.terraformation.backend.accelerator.api

import com.terraformation.backend.api.ControllerIntegrationTest
import com.terraformation.backend.customer.model.InternalTagIds
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.DeliverableCategory
import com.terraformation.backend.db.accelerator.DeliverableType
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.default_schema.Role
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.post

class ParticipantProjectSpeciesControllerTest : ControllerIntegrationTest() {
  private val path = "/api/v1/accelerator/projects/species"

  @Nested
  inner class CreateParticipantProjectSpecies {
    @Test
    fun `can add species to approved species list`() {
      insertModule(phase = CohortPhase.Phase1FeasibilityStudy)
      val cohortId = insertCohort()
      val participantId = insertParticipant(cohortId = cohortId)
      insertOrganization()
      insertOrganizationInternalTag(tagId = InternalTagIds.Accelerator)
      insertOrganizationUser(role = Role.Owner)
      val projectId = insertProject(participantId = participantId)
      val speciesId = insertSpecies()
      insertCohortModule()
      insertDeliverable(
          deliverableTypeId = DeliverableType.Species,
          deliverableCategoryId = DeliverableCategory.CarbonEligibility,
      )
      insertSubmission(submissionStatus = SubmissionStatus.Approved)

      val payload =
          """
            {
              "projectId": $projectId,
              "speciesId": $speciesId,
              "speciesNativeCategory": "Native"
            }
          """
              .trimIndent()

      mockMvc
          .post(path) { content = payload }
          .andExpect { status { is2xxSuccessful() } }
          .andExpectJson {
            val participantProjectSpeciesId = participantProjectSpeciesDao.findAll().first().id

            """
                {
                  "participantProjectSpecies": {
                    "id": $participantProjectSpeciesId,
                    "projectId": $projectId,
                    "speciesId": $speciesId,
                    "speciesNativeCategory": "Native",
                    "submissionStatus": "Not Submitted"
                  },
                  "status": "ok"
                }
              """
                .trimIndent()
          }
    }
  }
}
