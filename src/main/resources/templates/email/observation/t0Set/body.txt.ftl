<#-- @ftlvariable name="" type="com.terraformation.backend.email.model.T0DataSet" -->
${strings("notification.observation.t0Set.email.body", plantingSiteName)}

<#list monitoringPlots as plot><#list plot.speciesDensityChanges as change>
    Plot ${plot.monitoringPlotNumber}: ${change.speciesScientificName} - <#if change.previousPlotDensity??><#if change.newPlotDensity??>${change.previousPlotDensity} -> ${change.newPlotDensity}<#else>${change.previousPlotDensity} → Removed</#if><#else><#if change.newPlotDensity??>New → ${change.newPlotDensity}<#else>No change</#if></#if> ${strings("plants.per.hectare.parens")}
</#list></#list>


------------------------------

${strings("notification.observation.t0Set.text.footer", manageT0SettingsUrl)}
