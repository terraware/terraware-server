<#-- @ftlvariable name="" type="com.terraformation.backend.email.model.T0DataSet" -->
${strings("notification.observation.t0Set.email.body", plantingSiteName)}

<#list monitoringPlots as plot><#list plot.speciesDensityChanges as change>
    Plot ${plot.monitoringPlotNumber}: ${change.speciesScientificName} - <#if change.previousDensity??><#if change.newDensity??>${change.previousDensity} -> ${change.newDensity}<#else>${change.previousDensity} → Removed</#if><#else><#if change.newDensity??>New → ${change.newDensity}<#else>No change</#if></#if> ${strings("plants.per.hectare.parens")}
</#list></#list>

<#list plantingZones as zone><#list zone.speciesDensityChanges as change>
    ${zone.zoneName}: ${change.speciesScientificName} - <#if change.previousDensity??><#if change.newDensity??>${change.previousDensity} -> ${change.newDensity}<#else>${change.previousDensity} → Removed</#if><#else><#if change.newDensity??>New → ${change.newDensity}<#else>No change</#if></#if> ${strings("plants.per.hectare.parens")}
</#list></#list>

<#if (previousSiteTempSetting!) != (newSiteTempSetting!)>
    ${strings("survivalRateTempSetting")}:<#if newSiteTempSetting>${strings("disabled")} → ${strings("enabled")}<#else>${strings("enabled")} → ${strings("disabled")}</#if>
</#if>

------------------------------

${strings("notification.observation.t0Set.text.footer", manageT0SettingsUrl)}
