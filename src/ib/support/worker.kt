package ib.support

import bon.*
import bon.thread.sleep
import ib.IbConfig
import ib.lib.*
import java.util.*
import kotlin.concurrent.thread


private val log = Log("Worker")

class WorkerQueue {
  private val _events: MutableList<IbWrapperEvent> = mutable_list_of()
  fun take_all_sync(): List<IbWrapperEvent> = synchronized(this) {
    val tmp = list_of(_events)
    _events.clear()
    return tmp
  }
  fun add_sync(event: IbWrapperEvent): Void = synchronized(this) { _events.add(event) }
}

class TaskInProcessState(
  val task:       Task,
  val initial:    TaskInitialState,
  val request_id: Int
) {
  val timer          = timer_ms()
  val special_errors = LinkedList<Exception>()
  val errors         = LinkedList<AsyncError>()
  val events         = LinkedList<Any>()
  var final_event    = false

  override fun toString(): String = "task_${task.type}_${request_id}_${initial.tried}"
}

class TaskProcessedState(
  val task:       Task,
  val in_process: TaskInProcessState,
  val result:     Errorneous<*>
) {
  override fun toString(): String {
    return "task_${task.type}_${in_process.request_id}_${in_process.initial.tried}"
  }
}

// Worker has a separate connection to TWS and executes tasks (requests).
class Worker(
  private val ib_queue:   IBQueue,
          val port:       Int,
          val worker_id:  Int
) {
  private val wrapper_queue = WorkerQueue()
  private val tasks_by_rid  = mutable_dict_of<Int, TaskInProcessState>()
  private val rate_limiter  = RateLimiter(IbConfig.requests_per_second_limit)

  private var is_first_connection            = true
  private var _wrapper:           IBWrapper? = null

  private var _worker_connection_counter = 0
  private fun next_worker_connection_id(): Int = this._worker_connection_counter++

  companion object {
    private var _request_id_counter = 0
    fun next_request_id_sync(): Int = synchronized(this) { this._request_id_counter++ }
  }

  init {
    thread(isDaemon = true, name = "worker_$worker_id", block = ::run)
  }
  private fun run() {
    while (true) {
      // Waiting untill there is something to process
      if ((ib_queue.sync { _, outbox -> outbox.size == 0 }) && (tasks_by_rid.size == 0)) {
        sleep(IbConfig.delay_ms)
        continue
      }

      // Reconnect every min, just to clear the state
      val wrapper = get_wrapper_without_creating()
      if (
        (wrapper != null) &&
        (tasks_by_rid.size == 0) && // There's no current tasks
        // (finished_tasks.any { processed -> processed.result is Fail }) &&
        ((System.currentTimeMillis() - wrapper.created_at_ms) > 10 * min_ms)
      ) {
        // destroy_wrapper("there were errors during task processing")
        destroy_wrapper("planned reconnection")
      }

      val tasks_to_process = get_tasks_to_process()

      make_requests(tasks_to_process)

      collect_and_process_events_from_wrapper()

      val processed_tasks = calculate_results_for_worker_tasks()

      val finished_tasks  = cancel_processed_tasks(processed_tasks)

      send_results_to_ib(finished_tasks)

      sleep(IbConfig.thread_sleep_ms)
    }
  }


  private fun get_tasks_to_process(): List<TaskInitialState> {
    // Getting tasks to process
    val to_process = mutable_list_of<TaskInitialState>()
    val batch_size = 10; var i = 0
    while (
      (i < batch_size) &&
      ((to_process.size + tasks_by_rid.size) < IbConfig.parallel_requests_limit_per_worker)
    ) {
      val initial_task = ib_queue.sync({ _, outbox ->
        if (outbox.size > 0) outbox.removeFirst() else null
      }) ?: break
      to_process.add(initial_task)
      i++
    }
    return to_process
  }


  private fun make_requests(to_process: List<TaskInitialState>) {
    to_process.each { initial_state ->
      val in_process = TaskInProcessState(initial_state.task, initial_state, next_request_id_sync())
      tasks_by_rid[in_process.request_id] = in_process
      rate_limiter.sleep_if_needed {
        when (val wrapper = get_wrapper()) {
          is Fail    -> {
            log.error("$worker_id $in_process can't start as there's no TWS connection")
            in_process.special_errors.add(wrapper.error)
          }
          is Success -> {
            try {
              log.info("$worker_id $in_process making request on ${wrapper.result.id}")
              assert(tasks_by_rid.size <= IbConfig.parallel_requests_limit_per_worker)
              in_process.task.executor.make_request(in_process.request_id, wrapper.result.get_client())
            } catch (e: Exception) {
              log.error("$worker_id $in_process starting request on ${wrapper.result.id}", e);
              in_process.special_errors.add(e)
            }
          }
        }
      }
    }
  }


  private fun collect_and_process_events_from_wrapper() {
    val events = wrapper_queue.take_all_sync()
    val (task_events, special_events) = events.partition { event -> event.request_id >= 0 }

    // Processing special events
    var special_error_added = false
    special_events.each { event ->
      // If it's a special error failing all the current tasks
      if (event.event is Exception) {
        if (!special_error_added ) {
          tasks_by_rid.values.each { in_process -> in_process.special_errors.add(event.event) }
          // Adding only the first error for simplicity
          special_error_added = true
        }
      } else {
        log.warn("$worker_id unexpected async event ${event.ename}")
      }
    }

    // Processing task events
    task_events.each { event ->
      val in_process = tasks_by_rid[event.request_id]
      if (in_process != null) {
        assert(worker_id == event.worker_id)

        // Parsing event
        when (val e = event.event) {
          is AsyncError -> in_process.errors.add(e)
          is Exception  -> in_process.special_errors.add(e)
          is FinalEvent -> {
            if (in_process.final_event) in_process.special_errors.add(
              Exception("final event ${event.ename} set more than once")
            )
            in_process.final_event = true
          }
          else          -> in_process.events.add(e)
        }

      }
      // Ignoring events arrived after the task was finished
      else {
        if (!(event.event::class.simpleName in late_events_that_could_be_ignored)) {

          if (event.ename == "positionMulti") {
            p(event)
            System.exit(0)
          }

          log.warn(
            "$worker_id can't handle async event '${event.ename}', no task with request_id ${event.request_id}"
          )
        }
      }
    }
  }


  private fun calculate_results_for_worker_tasks(): List<TaskProcessedState> {
    val processed = mutableListOf<TaskProcessedState>()
    for (in_process in tasks_by_rid.values) {
      // Checking special errors
      if (!in_process.special_errors.is_empty()) {
        // Adding only the first error for simplicity
        processed.add(TaskProcessedState(
          in_process.task, in_process, Fail<Any>(in_process.special_errors.first())
        ))
      }
      // Checking timeout
      else if (in_process.timer() > in_process.task.timeout_ms) {
        val error = Exception("Timeout error after waiting for ${in_process.timer() / 1000}sec")
        processed.add(
          TaskProcessedState(in_process.task, in_process, Fail<Any>(error))
        )
      }
      // Calculating result
      else {
        try {
          val processed_task = in_process.task.executor.calculate_result(
            in_process.errors, in_process.events, in_process.final_event
          )
          if (processed_task != null) processed.add(
            TaskProcessedState(in_process.task, in_process, Success(processed_task))
          )
        } catch (e: Exception) {
          processed.add(TaskProcessedState(in_process.task, in_process, Fail<Any>(e)))
        }
      }
    }

    return processed
  }


  fun cancel_processed_tasks(processed: List<TaskProcessedState>): List<TaskProcessedState> {
    val processed_states = processed.map { processed_task ->
      rate_limiter.sleep_if_needed {
        when (val wrapper = get_wrapper()) {
          // If canceling task failed recording the error
          is Fail    -> {
            log.error("$worker_id $processed_task can't cancel as there's no TWS connection")
            TaskProcessedState(
              processed_task.task, processed_task.in_process, Fail<Any>(wrapper.error)
            )
          }
          is Success -> {
            try {
              log.info("$worker_id $processed_task cancel request on ${wrapper.result.id}")
              val cancel = {
                processed_task.task.executor.cancel_request(
                  processed_task.in_process.request_id, wrapper.result.get_client()
                )
              }

              // Waiting a little bit before canceling
              sleep(10)
              cancel()
              sleep(10)

              processed_task
            } catch (e: Exception) {
              log.error("$worker_id $processed_task error during cance request on ${wrapper.result.id}", e)
              TaskProcessedState(processed_task.task, processed_task.in_process, Fail<Any>(e))
            }
          }
        }
      }
    }

    // Removing processed tasks
    processed.each { processed_task -> tasks_by_rid.remove(processed_task.in_process.request_id) }

    return processed_states
  }

  fun send_results_to_ib(finished_tasks: List<TaskProcessedState>) {
    // Sending results to IB
    ib_queue.sync { inbox, outbox ->
      finished_tasks.each { processed ->
        val tried = processed.in_process.initial.tried + 1
        if ((processed.result is Fail) && (tried < IbConfig.retry_count) && should_retry(processed.result)) {
          // Retrying
          log.info(
            "$worker_id $processed failed in ${processed.in_process.timer()}ms and " +
            "will be re-tried, putting it back to queue"
          )
          outbox.add(TaskInitialState(processed.task, tried))
        } else {
          val time = "in ${processed.in_process.timer()}ms"
          when (processed.result) {
            is Success -> log.info(
              "$worker_id ${processed.task.type} success $time"
            )
            is Fail    -> log.warn(
              "$worker_id ${processed.task.type} error '${processed.result.error}' $time"
            )
          }

          inbox[processed.task] = processed.result
        }
      }
    }
  }

  private fun get_wrapper_without_creating(): IBWrapper? = this._wrapper

  private fun get_wrapper(): Errorneous<IBWrapper> {
    val timer = timer_ms()
    var wrapper = this._wrapper
    while (wrapper == null || !wrapper.ensure_connected_sync()) {
      // Checking timeout
      if (timer() > IbConfig.timeout_ms) {
        return Fail(Exception("Timeout error, can't connect to TWS"))
      }
      destroy_wrapper("it's not connected")
      if (is_first_connection) this.is_first_connection = false else sleep(IbConfig.delay_ms)
      wrapper = IBWrapper(
        port                 = port,
        worker_id            = worker_id,
        worker_connection_id = next_worker_connection_id()
      ) {
        event -> wrapper_queue.add_sync(event)
      }
      this._wrapper = wrapper
    }
    return Success(wrapper)
  }

  private fun destroy_wrapper(reason: String) {
    val wrapper = this._wrapper
    if (wrapper != null) {
      wrapper.disconnect_sync(reason)
      this._wrapper = null
    }
  }
}

fun should_retry(e: Fail<*>): Boolean {
  return !(error_type(e.error.message ?: "") in set_of(
//    "no_subscription",
    "not_found"
  ))
}