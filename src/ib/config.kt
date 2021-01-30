package ib

import bon.*

object IbConfig {
  val ib_port: Int = Env["ib_port", "7496"].toInt()

  // Port for the HTTP API that would be provided by this adapter
  val http_port: Int = Env["http_port", "8001"].toInt()
  val http_server_batch_call_thread_pool: Int = Env["http_server_batch_call_thread_pool", "300"].toInt()

  val delay_ms:       Int = Env["delay_ms", "1000"].toInt()

  // Huge timeout for slow reqeusts like getting Option Contract from IB TWS,
  // and smaller timeout for other.
  val large_timeout_ms:         Int = Env["large_timeout_ms", "120000"].toInt()
  val timeout_ms:               Int = Env["timeout_ms", "10000"].toInt()

  // Used to optimize waiting for price events
  val recommended_waiting_time: Int = Env["recommended_waiting_time", "3000"].toInt()

  // 50 msg/sec, no more than 60 historic requests per 10 minutes
  // Lowering it a little to be safe.
  val requests_per_second_limit: Int = Env["requests_per_second_limit", "40"].toInt()

  // http://interactivebrokers.github.io/tws-api/top_data.html
  // It's 100, but keeping it a little less
  val parallel_requests_limit_per_worker: Int = Env["parallel_requests_limit_per_worker", "90"].toInt()

  val thread_sleep_ms: Int = Env["thread_sleep_ms", "100"].toInt()

  // Each worker has separate connection to TWS.
  // The max amount is 32 but using a little less to be safe,
  // see https://interactivebrokers.github.io/tws-api/connection.html
  //
  // Seems like TWS doesn't accept more than 2 simultaneous connection reliably
  val workers_count: Int = Env["workers_count", "4"].toInt()

  // Failed requests to TWS will be retried.
  val retry_count: Int = Env["retry_count", "2"].toInt()
}