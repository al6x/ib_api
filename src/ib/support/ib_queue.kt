package ib.support

import bon.*
import bon.thread.sleep
import com.ib.client.EClientSocket
import ib.IbConfig
import ib.lib.AsyncError
import java.lang.Exception
import java.util.*

private val log = Log("IB")

abstract class Executor<Task, Result>(
  val task: Task
) {
  abstract fun step(
    request_id: Int, client: () -> EClientSocket,
    errors: List<AsyncError>, events: List<Any>, final_event: Boolean,
    waited_recommended_time: Boolean, timed_out: Boolean
  ): Result?

  abstract fun cancel(request_id: Int, client: () -> EClientSocket): Void
}

data class Request<Task, Result>(
  val name:                        String,
  val task:                        Task,
  val build_executor:              (Task) -> Executor<Task, Result>,
  val recommended_waiting_time_ms: Int,
  val timeout_ms:                  Int,
  var try_n:                       Int
) {
  override fun toString(): String = "request $name retry $try_n"
}

typealias MakeRequest<Task>      = (task: Task, request_id: Int, client: EClientSocket) -> Void
typealias CancelRequest<Task>    = (task: Task, request_id: Int, client: EClientSocket) -> Void
typealias CalculateResult<Task, Result> = (
  task: Task, errors: List<AsyncError>, events: List<Any>, final_event: Boolean,
  waited_recommended_time: Boolean, timed_out: Boolean
) -> Result?

class IBQueue {
  private val _requests: LinkedList<Request<Any, Any>> = LinkedList()
  private val _results:  MutableDict<Pair<String, Any>, Errorneous<*>> = mutable_dict_of()

  fun <R> sync(cb: (
    requests: LinkedList<Request<Any, Any>>,
    results:  MutableDict<Pair<String, Any>, Errorneous<*>>
  ) -> R): R = synchronized(this) { cb(_requests, _results) }

  fun <Task, Result> process(
    name:                        String,
    input:                       Task,
    make_request:                MakeRequest<Task>,
    cancel_request:              CancelRequest<Task>?,
    calculate_result:            CalculateResult<Task, Result>,
    recommended_waiting_time_ms: Int,
    timeout_ms:                  Int
  ): Result {
    val results = process_all(
      name, list_of(input), make_request, cancel_request, calculate_result,
      recommended_waiting_time_ms, timeout_ms
    )
    assert(results.size == 1) { "invalid results size ${results.size}" }
    return results.first()
  }

  fun <Task, Result> process_all(
    name:                        String,
    tasks:                       List<Task>,
    make_request:                MakeRequest<Task>,
    cancel_request:              CancelRequest<Task>?,
    calculate_result:            CalculateResult<Task, Result>,
    recommended_waiting_time_ms: Int,
    timeout_ms:                  Int
  ): List<Result> {
    class RequestResponseExecutor(task: Task) : Executor<Task, Result>(task) {
      var state = "make_request"

      override fun step(
        request_id: Int, client: () -> EClientSocket,
        errors: List<AsyncError>, events: List<Any>, final_event: Boolean,
        waited_recommended_time: Boolean, timed_out: Boolean
      ): Result? = when (state) {
        "make_request" -> {
          make_request(task, request_id, client())
          state = "wait_for_result"
          null
        }
        "wait_for_result" -> {
          calculate_result(task, errors, events, final_event, waited_recommended_time, timed_out)
        }
        else -> {
          throw Exception("invalid state $state")
        }
      }

      override fun cancel(request_id: Int, client: () -> EClientSocket): Void {
        if (cancel_request != null) cancel_request(task, request_id, client())
      }
    }

    return process_all(
      name,
      tasks,
      { task -> RequestResponseExecutor(task) },
      recommended_waiting_time_ms,
      timeout_ms
    )
  }

  fun <Task, Result> process(
    name:                        String,
    task:                        Task,
    build_executor:              (Task) -> Executor<Task, Result>,
    recommended_waiting_time_ms: Int,
    timeout_ms:                  Int
  ): Result {
    val results = process_all(
      name, list_of(task), build_executor, recommended_waiting_time_ms, timeout_ms
    )
    assert(results.size == 1) { "invalid results size ${results.size}" }
    return results.first()
  }

  fun <Task, Result> process_all(
    name:                        String,
    tasks:                       List<Task>,
    build_executor:              (Task) -> Executor<Task, Result>,
    recommended_waiting_time_ms: Int,
    timeout_ms:                  Int
  ): List<Result> {
    // Adding new requests
    sync { requests, _ ->
      requests.add_all(tasks.map { task ->
        Request(
          name                        = name,
          task                        = task as Any,
          build_executor              = build_executor as (Any) -> Executor<Any, Any>,
          recommended_waiting_time_ms = recommended_waiting_time_ms,
          timeout_ms                  = timeout_ms,
          try_n                       = 1
        )
      })
    }

    while (true) {
      // Checking if all requests are processed
      val results = mutable_list_of<Errorneous<*>>()
      sync { _, results_queue ->
        // Exiting as soon as one is missing
        for (task in tasks) results.add(results_queue[Pair(name, task  as Any)] ?: break)
      }

      // Getting the results or throwing error
      if (results.size == tasks.size) {
        // Deleting processed results from the queue
        sync { _, results_queue ->
          for (task in tasks) results_queue.remove(Pair(name, task  as Any))
        }

        // Getting results
        return results.map { result ->
          when (result) {
            is Success -> result.result as Result
            is Fail    -> throw result.error // Throwing only the first error for simplicity
          }
        }
      }

      // Sleeping
      sleep(IbConfig.thread_sleep_ms)
    }
  }
}