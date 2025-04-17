package com.terraformation.backend.documentproducer.model

import com.terraformation.backend.db.default_schema.LandUseModelType

object StableIds {
  val country = StableId("1")
  val applicationRestorableLand = StableId("2")
  val projectType = StableId("3")
  val landUseModelType = StableId("4")
  val nativeForestLandUseHectare = StableId("5")
  val monocultureLandUseHectare = StableId("7")
  val sustainableTimberLandUseHectare = StableId("9")
  val otherTimberLandUseHectare = StableId("11")
  val mangrovesLandUseModelHectare = StableId("13")
  val agroforestryLandUseModelHectare = StableId("15")
  val silvopastureLandUseModelHectare = StableId("17")
  val otherLandUseModelHectare = StableId("19")
  val numSpecies = StableId("22")
  val totalExpansionPotential = StableId("24")
  val contactName = StableId("25")
  val contactEmail = StableId("26")
  val website = StableId("27")
  val projectArea = StableId("205")
  val totalVCU = StableId("283")
  val standard = StableId("350")
  val methodologyNumber = StableId("351")
  val accumulationRate = StableId("369")
  val tfRestorableLand = StableId("429")
  val perHectareEstimatedBudget = StableId("430")
  val minCarbonAccumulation = StableId("431")
  val maxCarbonAccumulation = StableId("432")
  val carbonCapacity = StableId("433")
  val annualCarbon = StableId("434")
  val totalCarbon = StableId("435")
  val dealDescription = StableId("436")
  val investmentThesis = StableId("437")
  val failureRisk = StableId("438")
  val whatNeedsToBeTrue = StableId("439")
  val verraLink = StableId("471")
  val dealName = StableId("472")
  val projectZoneFigure = StableId("525")
  val minProjectArea = StableId("545")
  val sdgList = StableId("546")
  val gisReportsLink = StableId("547")
  val riskTrackerLink = StableId("548")
  val clickUpLink = StableId("549")
  val slackLink = StableId("550")
  val projectHighlightPhoto = StableId("551")

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
