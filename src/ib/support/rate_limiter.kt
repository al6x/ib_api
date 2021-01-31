package ib.support

import bon.*
import kotlin.math.roundToInt

class RateLimiter(val per_second: Int) {
  private var last_ms: Long = -1
          val limit_ms      = (1000 / (per_second * 0.9)).roundToInt() // x0.9 just to be sure

  fun <R> sleep_if_needed(block: () -> R): R {
    if (last_ms > 0) {
      // Could be more efficient if implemented with token-bucket
      val delay = limit_ms - (System.currentTimeMillis() - last_ms)
      if (delay > 0) Thread.sleep(delay)
    }
    val result = block()

    // It seems like TWS calculating limits wrongly, seems like it's better to
    // to measure duration after the request.
    last_ms = System.currentTimeMillis()

    return result
  }
}