package com.terraformation.seedbank.services

import io.reactivex.BackpressureStrategy
import io.reactivex.Flowable
import org.reactivestreams.Publisher

/**
 * Returns a Publisher that emits a single value. Use this to remove clutter from methods that
 * need to use the Reactive Streams API but don't do any work that would potentially block a
 * thread for an extended period.
 */
inline fun <T : Any> publishSingleValue(crossinline func: () -> T): Publisher<T> {
  return Flowable.create(
      { emitter ->
        try {
          val result = func()
          emitter.onNext(result)
          emitter.onComplete()
        } catch (t: Throwable) {
          emitter.onError(t)
        }
      },
      BackpressureStrategy.ERROR)
}
