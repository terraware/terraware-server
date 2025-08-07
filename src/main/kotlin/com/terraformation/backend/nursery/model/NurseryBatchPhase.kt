package com.terraformation.backend.nursery.model

enum class NurseryBatchPhase(val value: Int) {
  Germinating(10),
  NotReady(20),
  HardeningOff(30),
  Ready(40)
}
