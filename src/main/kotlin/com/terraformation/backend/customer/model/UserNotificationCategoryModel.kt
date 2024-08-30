package com.terraformation.backend.customer.model

import com.fasterxml.jackson.annotation.JsonValue
import com.terraformation.backend.db.accelerator.DeliverableCategory

/**
 * This is a combination of [DeliverableCategory] and an additional "Sourcing" category for
 * accelerator application notifications. Future notification categories will be added here.
 */
enum class UserNotificationCategory(@get:JsonValue val jsonValue: String) {
  // Deliverable categories
  Compliance("Compliance"),
  FinancialViability("Financial Viability"),
  GIS("GIS"),
  CarbonEligibility("Carbon Eligibility"),
  StakeholdersAndCommunityImpact("Stakeholders and Community Impact"),
  ProposedRestorationActivities("Proposed Restoration Activities"),
  VerraNonPermanenceRiskToolNPRT("Verra Non-Permanence Risk Tool (NPRT)"),
  SupplementalFiles("Supplemental Files"),
  // Application categories
  Sourcing("Sourcing");

  fun toDeliverableCategory(): DeliverableCategory? {
    return when (this) {
      Compliance -> DeliverableCategory.Compliance
      FinancialViability -> DeliverableCategory.FinancialViability
      GIS -> DeliverableCategory.GIS
      CarbonEligibility -> DeliverableCategory.CarbonEligibility
      StakeholdersAndCommunityImpact -> DeliverableCategory.StakeholdersAndCommunityImpact
      ProposedRestorationActivities -> DeliverableCategory.ProposedRestorationActivities
      VerraNonPermanenceRiskToolNPRT -> DeliverableCategory.VerraNonPermanenceRiskToolNPRT
      SupplementalFiles -> DeliverableCategory.SupplementalFiles
      else -> null
    }
  }

  companion object {
    fun of(deliverableCategory: DeliverableCategory): UserNotificationCategory {
      return when (deliverableCategory) {
        DeliverableCategory.Compliance -> Compliance
        DeliverableCategory.FinancialViability -> FinancialViability
        DeliverableCategory.GIS -> GIS
        DeliverableCategory.CarbonEligibility -> CarbonEligibility
        DeliverableCategory.StakeholdersAndCommunityImpact -> StakeholdersAndCommunityImpact
        DeliverableCategory.ProposedRestorationActivities -> ProposedRestorationActivities
        DeliverableCategory.VerraNonPermanenceRiskToolNPRT -> VerraNonPermanenceRiskToolNPRT
        DeliverableCategory.SupplementalFiles -> SupplementalFiles
      }
    }
  }
}
