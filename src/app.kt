import bon.*
import bon.http.*
import ib.IbConfig
import ib.lib.IBImpl

fun main() {
  Env.set(
//    "workers_count"      to 1,
    "retry_count"        to 1,
//    "disable_logs"       to "wrapper,worker.info,worker.debug"
    "disable_logs"       to "wrapper"
  )

  log_info("Made by http://al6x.com, I do Analytics, Data Mining, Visualisations, $110/hr")

  val server = Server(
    port                   = IbConfig.http_port,
    batch_call_thread_pool = IbConfig.http_server_batch_call_thread_pool
  )
  val ib = IBImpl()
  ib.expose_on(server)

//  server.on("get", "/hi") { dict_of(1 to 3, "2" to "sdds adfa a dfasdfs a") }
}