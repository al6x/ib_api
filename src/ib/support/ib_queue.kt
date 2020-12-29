package ib.support

import bon.*
import bon.thread.sleep
import com.ib.client.EClientSocket
import ib.IbConfig
import ib.lib.AsyncError
import java.util.*

private val log = Log("IB")

typealias MakeRequest<Input>      = (input: Input, request_id: Int, client: EClientSocket) -> Void
typealias CancelRequest<Input>    = (input: Input, request_id: Int, client: EClientSocket) -> Void
typealias CalculateResult<Input, Result> = (
  input: Input, errors: List<AsyncError>, events: List<Any>, final_event: Boolean, timed_out: Boolean
) -> Result?

class TaskExecutor<Input, Result>(
  private val input:            Input,
  private val make_request:     MakeRequest<Input>,
  private val cancel_request:   CancelRequest<Input>?,
  private val calculate_result: CalculateResult<Input, Result>
) {
  fun make_request(request_id: Int, client: EClientSocket) {
    make_request(input, request_id, client)
  }

  fun cancel_request(request_id: Int, client: EClientSocket) {
    (cancel_request ?: return)(input, request_id, client)
  }

  fun calculate_result(
    errors: List<AsyncError>, events: List<Any>, final_event: Boolean, timed_out: Boolean
  ): Result? {
    return calculate_result(input, errors, events, final_event, timed_out)
  }
}

class Task(
  val type:       String,
  val executor:   TaskExecutor<*, *>,
  val timeout_ms: Int
)

class TaskInitialState(
  val task:  Task,
  val tried: Int
) {
  override fun toString(): String = "task_${task.type}_x_${tried}"
}

class IBQueue {
  private val _outbox: LinkedList<TaskInitialState>     = LinkedList()
  private val _inbox:  MutableDict<Task, Errorneous<*>> = mutable_dict_of()

  fun <R> sync(cb: (
    inbox:  MutableDict<Task, Errorneous<*>>,
    outbox: LinkedList<TaskInitialState>
  ) -> R): R = synchronized(this) { cb(_inbox, _outbox) }

  fun <Input, Result> process(
    name:             String,
    input:            Input,
    make_request:     MakeRequest<Input>,
    cancel_request:   CancelRequest<Input>?,
    calculate_result: CalculateResult<Input, Result>,
    timeout_ms:       Int
  ): Result {
    val results = process_all(
      name, list_of(input), make_request, cancel_request, calculate_result, timeout_ms
    )
    assert(results.size == 1) { "invalid results size ${results.size}" }
    return results.first()
  }

  fun <Input, Result> process_all(
    name:             String,
    inputs:           List<Input>,
    make_request:     MakeRequest<Input>,
    cancel_request:   CancelRequest<Input>?,
    calculate_result: CalculateResult<Input, Result>,
    timeout_ms:       Int
  ): List<Result> {
    val tasks = inputs.map { input ->
      Task(name, TaskExecutor(input, make_request, cancel_request, calculate_result), timeout_ms)
    }

    sync { _, outbox ->
      tasks.each { task -> outbox.add(TaskInitialState(task = task, tried = 0)) }
    }

    while (true) {
      // Checking if all tasks are processed
      val results: MutableList<Errorneous<*>> = mutable_list_of()
      sync { inbox, _ ->
        tasks.each it@ { task ->
          results.add(inbox[task] ?: return@it)
        }
      }

      // Getting the results or throwing error
      if (results.size == tasks.size) {
        return results.map { result ->
          when (result) {
            is Success -> result.result as Result
            // Throwing only the first error for simplicity
            is Fail    -> throw result.error
          }
        }
      }

      // Sleeping
      sleep(IbConfig.thread_sleep_ms)
    }
  }
}