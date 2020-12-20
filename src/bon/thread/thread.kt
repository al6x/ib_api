package bon.thread

import bon.Errorneous
import bon.Fail
import bon.Success
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

fun sleep(ms: Number) = Thread.sleep(ms.toLong())

fun <R> execute_in_parallel(tasks: List<() -> R>, threads: Int): List<Errorneous<R>> {
  var executor: ExecutorService? = null
  try {
    executor = Executors.newFixedThreadPool(threads)
    val fresults = executor.invokeAll(tasks.map { task ->
      Callable<Errorneous<R>> {
        try { Success(task()) } catch (e: Exception) { Fail(e) }
      }
    })
    return fresults.map { future -> future.get() }
  } finally {
    executor?.shutdown()
  }
}