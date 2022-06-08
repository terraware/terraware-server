package com.terraformation.backend.device.balena

import com.terraformation.backend.db.BalenaDeviceId

abstract class BalenaException(message: String) : RuntimeException(message)

class BalenaNotEnabledException : BalenaException("Balena is not enabled")

class BalenaRequestFailedException(val statusCode: Int) :
    BalenaException("Request failed with HTTP status $statusCode")

class BalenaVariableExistsException(val balenaId: BalenaDeviceId, val name: String) :
    BalenaException("Environment variable $name already exists on device $balenaId")

class BalenaVariableNotFoundException(val balenaId: BalenaDeviceId, val name: String) :
    BalenaException("Environment variable $name not found on device $balenaId")
