package com.terraformation.backend.admin

import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.tracking.db.ObservationResultsStoreV2
import com.terraformation.backend.tracking.db.ObservationStore
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.db.T0Store
import com.terraformation.backend.tracking.model.ObservationResultsDepth
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping

@Controller
@RequestMapping("/admin")
@RequireGlobalRole([GlobalRole.SuperAdmin, GlobalRole.AcceleratorAdmin])
@Validated
class AdminSurvivalRatesController(
    private val observationResultsStore: ObservationResultsStoreV2,
    private val observationStore: ObservationStore,
    private val organizationStore: OrganizationStore,
    private val plantingSiteStore: PlantingSiteStore,
    private val t0Store: T0Store,
) {
  @GetMapping("/plantingSite/{plantingSiteId}/survivalRates")
  fun getSurvivalRates(@PathVariable plantingSiteId: PlantingSiteId, model: Model): String {
    val site = plantingSiteStore.fetchSiteById(plantingSiteId, PlantingSiteDepth.Plot)
    val organization = organizationStore.fetchOneById(site.organizationId)
    val observations = observationStore.fetchObservationsByPlantingSite(plantingSiteId)
    val allT0DataSet = t0Store.fetchAllT0SiteDataSet(plantingSiteId)
    val results =
        observationResultsStore.fetchByPlantingSiteId(
            plantingSiteId = plantingSiteId,
            depth = ObservationResultsDepth.Plot,
            states = setOf(ObservationState.Completed),
        )

    model.addAttribute("allT0DataSet", allT0DataSet)
    model.addAttribute("hasCompletedObservations", results.isNotEmpty())
    model.addAttribute("hasObservations", observations.isNotEmpty())
    model.addAttribute("model", SurvivalRatesPageModel.of(site, organization, results))

    return "/admin/survivalRates"
  }
}
