package com.terraformation.backend.accelerator.variables

import com.terraformation.backend.db.default_schema.LandUseModelType
import com.terraformation.backend.db.docprod.VariableType

object StableIds {
  val country = StableId("1", VariableType.Select)
  val applicationRestorableLand = StableId("2", VariableType.Number)
  val projectType = StableId("3", VariableType.Select)
  val landUseModelType = StableId("4", VariableType.Select)
  val nativeForestLandUseHectare = StableId("5", VariableType.Number)
  val monocultureLandUseHectare = StableId("7", VariableType.Number)
  val sustainableTimberLandUseHectare = StableId("9", VariableType.Number)
  val otherTimberLandUseHectare = StableId("11", VariableType.Number)
  val mangrovesLandUseModelHectare = StableId("13", VariableType.Number)
  val agroforestryLandUseModelHectare = StableId("15", VariableType.Number)
  val silvopastureLandUseModelHectare = StableId("17", VariableType.Number)
  val otherLandUseModelHectare = StableId("19", VariableType.Number)
  val numSpecies = StableId("22", VariableType.Number)
  val totalExpansionPotential = StableId("24", VariableType.Number)
  val contactName = StableId("25", VariableType.Text)
  val contactEmail = StableId("26", VariableType.Text)
  val website = StableId("27", VariableType.Text)
  val tfRestorableLand = StableId("429", VariableType.Number)
  val perHectareEstimatedBudget = StableId("430", VariableType.Number)
  val minCarbonAccumulation = StableId("431", VariableType.Number)
  val maxCarbonAccumulation = StableId("432", VariableType.Number)
  val carbonCapacity = StableId("433", VariableType.Number)
  val annualCarbon = StableId("434", VariableType.Number)
  val totalCarbon = StableId("435", VariableType.Number)
  val dealDescription = StableId("436", VariableType.Text)
  val investmentThesis = StableId("437", VariableType.Text)
  val failureRisk = StableId("438", VariableType.Text)
  val whatNeedsToBeTrue = StableId("439", VariableType.Text)
  val dealName = StableId("472", VariableType.Text)

  val landUseHectaresByLandUseModel =
      mapOf(
          LandUseModelType.Agroforestry to agroforestryLandUseModelHectare,
          LandUseModelType.Mangroves to mangrovesLandUseModelHectare,
          LandUseModelType.Monoculture to monocultureLandUseHectare,
          LandUseModelType.NativeForest to nativeForestLandUseHectare,
          LandUseModelType.OtherLandUseModel to otherLandUseModelHectare,
          LandUseModelType.OtherTimber to otherTimberLandUseHectare,
          LandUseModelType.Silvopasture to silvopastureLandUseModelHectare,
          LandUseModelType.SustainableTimber to sustainableTimberLandUseHectare,
      )
}
