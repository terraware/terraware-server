package com.terraformation.backend.db

import java.io.IOException

class AccessionNotFoundException(val accessionNumber: String) :
    Exception("Accession $accessionNumber not found")

class DeviceNotFoundException(override val message: String) : Exception(message)

class FeatureNotFoundException(val featureId: FeatureId) :
    Exception("Feature $featureId not found")

/** A request to the Keycloak authentication server failed. */
open class KeycloakRequestFailedException(
    override val message: String,
    override val cause: Throwable? = null
) : IOException(message, cause)

/** Keycloak couldn't find a user that we expected to be able to find. */
class KeycloakUserNotFoundException(message: String) : Exception(message)

class LayerNotFoundException(val layerId: LayerId) : Exception("Layer $layerId not found")

class PlantNotFoundException(val featureId: FeatureId) :
    Exception("Plant with feature id $featureId not found")

class PlantObservationNotFoundException(val id: PlantObservationId) :
    Exception("Plant observation $id not found")

class SpeciesNotFoundException(val speciesId: SpeciesId) :
    Exception("Species $speciesId not found")

class SpeciesNameNotFoundException(val speciesNameId: SpeciesNameId) :
    RuntimeException("Species name $speciesNameId not found")
