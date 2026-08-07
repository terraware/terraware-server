<#-- @ftlvariable name="" type="com.terraformation.backend.email.model.StratumDensityUpdated" -->
${strings("notification.plantingSite.stratumDensityUpdated.email.body", plantingSiteName, stratumName)}

<#if initialPlantingDensityChange??>
    ${strings("stratum.initialPlantingDensity")}: <#if initialPlantingDensityChange.previousDensity??>${initialPlantingDensityChange.previousDensity}<#else>${strings("notSet")}</#if> -> <#if initialPlantingDensityChange.newDensity??>${initialPlantingDensityChange.newDensity}<#else>${strings("notSet")}</#if>
</#if>
<#if targetPlantDensityChange??>
    ${strings("stratum.targetPlantDensity")}: <#if targetPlantDensityChange.previousDensity??>${targetPlantDensityChange.previousDensity}<#else>${strings("notSet")}</#if> -> <#if targetPlantDensityChange.newDensity??>${targetPlantDensityChange.newDensity}<#else>${strings("notSet")}</#if>
</#if>

------------------------------

${strings("notification.email.text.footer", manageSettingsUrl)}
