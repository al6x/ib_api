package ib.support

import bon.*
import bon.thread.sleep
import ib.IbConfig
import ib.lib.*
import java.util.*
import kotlin.concurrent.thread
import kotlin.math.floor
import kotlin.math.max


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

class ActiveRequest<Task, Result>(
  val id:         Int,
  val request:    Request<Task, Result>,
  val executor:   Executor<Task, Result>
) {
  val timer          = timer_ms()
  val errors         = LinkedList<AsyncError>()
  val events         = LinkedList<Any>()
  var final_event    = false
  val fatal_errors   = LinkedList<Exception>()
  var result:          Result? = null

  override fun toString(): String = "$request request_id $id"
}


// Worker has a separate connection to TWS and executes tasks (requests).
class Worker(
  private val ib_queue:   IBQueue,
  val port:       Int,
  val worker_id:  Int
) {
  private val wrapper_queue   = WorkerQueue()
  private val active_requests = mutable_dict_of<Int, ActiveRequest<Any, Any>>()
  private val rate_limiter    = RateLimiter(IbConfig.requests_per_second_limit)

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
      if ((ib_queue.sync { requests, _ -> requests.size == 0 }) && (active_requests.size == 0)) {
        sleep(IbConfig.delay_ms)
        continue
      }

      // Reconnect every min, just to clear the state
      val wrapper = get_wrapper_without_creating()
      if (
        (wrapper != null) &&
        (active_requests.size == 0) && // There's no current tasks
        // (finished_tasks.any { processed -> processed.result is Fail }) &&
        ((System.currentTimeMillis() - wrapper.created_at_ms) > 10 * min_ms)
      ) {
        // destroy_wrapper("there were errors during task processing")
        destroy_wrapper("planned reconnection")
      }

      add_new_requests()

      step()

      collect_and_process_events_from_wrapper()

      val finished = cancel_processed_requests()

      send_results_back(finished)

      sleep(IbConfig.thread_sleep_ms)
    }
  }


  private fun add_new_requests(): Void {
    // Setting batch so that load will be distributed evenly between workers, each
    // worker would get only 1/n on each tick.
    val queue_size = ib_queue.sync({ requests, _ -> requests.size })
    val batch_size = max(floor(queue_size.toFloat() / IbConfig.workers_count.toFloat()).toInt(), 1)

    // Getting new requests
    val new_requests = mutable_list_of<ActiveRequest<Any, Any>>()
    var i = 0
    while (
      (i < batch_size) &&
      ((new_requests.size + active_requests.size) < IbConfig.parallel_requests_limit_per_worker)
    ) {
      val request = ib_queue.sync({ requests, _ ->
        if (requests.size > 0) requests.removeFirst() else null
      }) ?: break
      new_requests.add(ActiveRequest(
        id       = next_request_id_sync(),
        request  = request,
        executor = request.build_executor(request.task)
      ))
      i++
    }

    // Adding to the active requests
    new_requests.each { active ->
      active_requests[active.id] = active
      log.info("$worker_id running $active")
    }
  }


  private fun step() {
    assert(active_requests.size <= IbConfig.parallel_requests_limit_per_worker)

    for (active in active_requests.values) {
      // Result could be already set as error if error occured in wrapper
      assert(active.fatal_errors.is_empty() && active.result == null)

      when (val wrapper = get_wrapper()) {
        is Fail    -> {
          log.error("$worker_id can't run $active as there's no TWS connection")
          active.fatal_errors.add(wrapper.error)
        }
        is Success -> {
          try {
            val timed_out               = active.timer() > active.request.timeout_ms
            val waited_recommended_time = active.timer() > active.request.recommended_waiting_time_ms

            active.result = active.executor.step(
              request_id              = active.id,
              client                  = { -> rate_limiter.sleep_if_needed { wrapper.get().get_client() } },
              errors                  = active.errors,
              events                  = active.events,
              final_event             = active.final_event,
              waited_recommended_time = waited_recommended_time,
              timed_out               = timed_out
            )

            // Checking timeout
            if (timed_out && active.result == null) active.fatal_errors.add(
              Exception("Timeout error after waiting for ${active.timer() / 1000}sec")
            )
          } catch (e: Exception) {
            log.error("$worker_id running $active on ${wrapper.result.id}", e)
            active.fatal_errors.add(e)
          }
        }
      }
    }
  }


  private fun collect_and_process_events_from_wrapper() {
    val events = wrapper_queue.take_all_sync()
    val (task_events, unexpected_events) = events.partition { event -> event.request_id >= 0 }

    // Processing special events
    unexpected_events.each { event ->
      // If it's an unexpected error failing all the current tasks
      if (event.event is Exception) {
        active_requests.values.each { active -> active.fatal_errors.add(event.event) }
      } else {
        log.warn("$worker_id received unexpected async event ${event.ename}")
      }
    }

    // Processing task events
    task_events.each { event ->
      val active = active_requests[event.request_id]
      if (active != null) {
        assert(worker_id == event.worker_id)

        // Parsing event
        when (val e = event.event) {
          is AsyncError -> active.errors.add(e)
          is Exception  -> active.fatal_errors.add(e)
          is FinalEvent -> {
            if (active.final_event) {
              active.fatal_errors.add(Exception("final event ${event.ename} set more than once"))
            }
            active.final_event = true
          }
          else          -> active.events.add(e)
        }
      }
      // Ignoring events arrived after the task was finished
      else {
        if (event.event::class.simpleName !in late_events_that_could_be_ignored) {
          log.warn(
            "$worker_id can't handle async event '${event.ename}', no task with request_id ${event.request_id}"
          )
        }
      }
    }
  }


  private fun cancel_processed_requests(): List<ActiveRequest<Any, Any>> {
    val processed_requests = active_requests.values.filter { active ->
      active.result != null || !active.fatal_errors.is_empty()
    }

    // Cancelling processed requests
    processed_requests.map { processed ->
      when (val wrapper = get_wrapper()) {
        is Fail    -> {
          log.error("$worker_id can't cancel $processed as there's no TWS connection")
          processed.fatal_errors.add(wrapper.error)
        }
        is Success -> {
          try {
            log.info("$worker_id canceling $processed on ${wrapper.result.id}")
            processed.executor.cancel(
              request_id              = processed.id,
              client                  = { ->
                sleep(10) // Waiting a little bit before canceling, just in case
                rate_limiter.sleep_if_needed { wrapper.get().get_client() }
              }
            )
          } catch (e: Exception) {
            log.error("$worker_id error during canceling $processed on ${wrapper.result.id}", e)
            processed.fatal_errors.add(e)
          }
        }
      }
    }

    // Removing processed requests
    processed_requests.each { processed -> active_requests.remove(processed.id) }

    return processed_requests
  }


  fun send_results_back(finished_requests: List<ActiveRequest<Any, Any>>) {
    val to_retry = mutable_list_of<Request<Any, Any>>()
    val to_results = mutable_dict_of<Pair<String, Any>, Errorneous<*>>()

    for (finished in finished_requests) {
      val key = Pair(finished.request.name, finished.request.task)
      if (finished.fatal_errors.is_empty()) {
        // Success
        assert(finished.result != null)
        log.info("$worker_id successfully finished ${finished.request.name} in ${finished.timer()}ms")
        to_results[key] = Success(finished.result)
      } else {
        val tried = finished.request.try_n
        if ((tried < IbConfig.retry_count) && should_retry(finished.fatal_errors.first)) {
          // Error, retrying
          log.info("$worker_id failed $finished in ${finished.timer()}ms and will be re-tried")
          val request = finished.request
          request.try_n += 1
          to_retry.add(request)
        } else {
          // Error
          val error = finished.fatal_errors.first // Reporting just first error
          log.warn("$worker_id failed ${finished.request.name}, ${error}, in ${finished.timer()}ms")
          to_results[key] = Fail<Any>(error)
        }
      }
    }

    // Sending results back
    ib_queue.sync { requests, results ->
      requests.add_all(to_retry)
      for ((key, result) in to_results) {
        assert(key !in results)
        results[key] = result
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

  override fun toString(): String = "worker_$worker_id"
}

fun should_retry(e: Exception): Boolean {
  return error_type(e.message ?: "") !in set_of(
    // "no_subscription",
    "not_found"
  )
}