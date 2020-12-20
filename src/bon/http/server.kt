package bon.http

import bon.*
import bon.io.Json
import bon.thread.execute_in_parallel
import spark.Service
import java.util.concurrent.atomic.AtomicInteger


typealias Handler = (Request) -> Any // Result could be any object or Response

data class Response(
  val body:   Any,
  val status: Int = 200
)

enum class Format { html, json, yaml, text }

private val log = Log("HTTP")

class Request (
  val id:              Int,
  val method:          String,
  val format:          Format,
  val path:            String,
  val query_as_string: String,
  val query:           Dict<String, String>,
  val route_params:    Dict<String, String>,
  val body_as_string:  String,
  val body:            Json.UntypedJson
) {
  fun get_string(name: String): String {
    return get_string_optional(name).assert_not_null { "The $name parameter required!" }
  }
  fun get_integer(name: String): Int = get_string(name).toInt()
  fun get_double(name: String): Double = get_string(name).toDouble()

  fun get_list_of_string(name: String): List<String> {
    return get_list_of_string(name).assert_not_null { "The $name parameter required!" }
  }

  fun get_as_list_of_integers(name: String): List<Int> {
    return get_list_of_integers_optional(name).assert_not_null { "The $name parameter required!" }
  }

  fun get_string_optional(name: String): String? = when {
    (name in route_params) -> route_params[name]
    (name in query)        -> query[name]
    (name in body)         -> body.get_string(name)
    else                   -> null
  }

  fun <T> get_string_optional(name: String, convert: (String) -> T): T? =
    if (name in this) convert(get_string(name)) else null

  operator fun contains(name: String): Boolean = (name in route_params) or (name in query) or (name in body)

  fun get_list_of_strings_optional(name: String): List<String>? = when {
    (name in route_params) -> route_params[name, ""].split(",")
    (name in query)        -> query[name, ""].split(",")
    (name in body)         -> body.get_array(name).map { v -> v.get_string() }
    else                   -> null
  }

  fun get_list_of_integers_optional(name: String): List<Int>? {
    return get_list_of_strings_optional(name)?.map { v -> v.toInt() }
  }

  override fun toString() = "$method $path" + if (query_as_string == "") "" else "?$query_as_string"
}


open class Server (
  val port:                   Int,
  val default_format:         Format  = Format.json,
  val batch_call_thread_pool: Int     = 10 // Used for `/call`
) {
  private val request_id_counter = AtomicInteger()
  private val http: Service = Service.ignite().port(port)
  private val handlers: MutableDict<String, Handler> = mutable_dict_of()

  init {
    log.info("started on port $port")
    http.apply {}

    // Setting batch call handler
    add_call_alias("/call")
  }

  // Sometimes `/call` needs to be aliased, with something like `/api/v1/call`
  fun add_call_alias(alias: String): Void {
    http.post(alias, to_spark_handler(batch_call_handler(), false))
  }

  fun on(method: String, route: String, handler: Handler, privacy: Boolean = false): Void {
    handlers[route] = handler
    when (method) {
      "get"  -> http.get(route, to_spark_handler(handler, privacy))
      "post" -> http.post(route, to_spark_handler(handler, privacy))
      else   -> throw Exception("unknown http method $method")
    }
  }

  fun get(route: String, handler: Handler)                   = on("get", route, handler, false)
  fun get(route: String, handler: Handler, privacy: Boolean) = on("get", route, handler, privacy)

  // Call multiple requests in single batch
  private fun batch_call_handler(): Handler {
    return { call_request ->
      // Creating requests
      val requests = call_request.body.get_array().map { command ->
        val route = command.get_array()[0].get_string()
        val body  = command.get_array()[1]
        Request(
          id              = request_id_counter.getAndIncrement(),
          method          = call_request.method,
          format          = call_request.format,
          path            = route,
          query_as_string = "",
          query           = dict_of(),
          route_params    = dict_of(),
          body_as_string  = "",
          body            = body
        )
      }

      // Preparing tasks
      val tasks = requests.map { request -> {
        val handler = handlers[request.path] ?: throw Exception("unknown route ${request.path}")
        handler(request)
      }}

      // Eecuting in parallel
      val results = execute_in_parallel(tasks, threads = batch_call_thread_pool)

      // Responding with results
      val responses = results.map { result, i -> when (result) {
        is Success -> {
          if (result.result is Response) ErrorResponse("Returing Response is not allowed in `/call`")
          else                           result.result
        }
        is Fail    -> ErrorResponse(result.error.message ?: "Unknown error")
      }}

      responses
    }
  }

  private fun to_spark_handler(handler: Handler, privacy: Boolean): (spark.Request, spark.Response) -> String  {
    return lambda@ { sreq, sres ->
      // Parsing request
      val req = try {
        to_request(sreq)
      } catch (unsafe_error: Exception) {
        try {
          val e = if (privacy) Exception("Error [hidden]") else unsafe_error
          log.error("can't create request", e)
          return@lambda format_error(default_format, e.message, sres)
        } catch (unsafe_error: Exception) {
          val e = if (privacy) Exception("Error [hidden]") else unsafe_error
          log.error("can't reply with request error", e)
          return@lambda "Can't reply with request error"
        }
      }

      // Handling request
      try {
        log.info(if(privacy) "[hidden]" else req.to_string())
        val any_res = handler(req)
        val res = if (any_res is Response) any_res else Response(any_res)
        format_response(req.format, res, sres)
      } catch (unsafe_error: Exception) {
        try {
          val e        = if (privacy) Exception("Error [hidden]") else unsafe_error
          val req_info = if(privacy) "[hidden]"                   else req.to_string()
          log.error(req_info, e)
          format_error(req.format, e.message, sres)
        } catch (unsafe_error: Exception) {
          val e        = if (privacy) Exception("Error [hidden]") else unsafe_error
          log.error("can't reply with handler error", e)
          "Can't reply with handler error"
        }
      }
    }
  }

  private fun format_error(format: Format, message: String?, sres: spark.Response): String =
    format_response(format, Response(ErrorResponse(message ?: "Unknown error")), sres)

  private fun format_response(format: Format, res: Response, sres: spark.Response): String = when (format) {
    Format.json -> {
      sres.status(res.status)
      sres.type("application/json")
      if (res.body is String) res.body else res.body.to_json()
    }

    Format.yaml -> {
      sres.status(res.status)
      sres.type("text/yaml")
      if (res.body is String) res.body else res.body.to_yaml()
    }

    Format.text -> {
      sres.status(res.status)
      sres.type("text/plain")
      res.body.to_string()
    }

    Format.html -> {
      sres.status(res.status)
      sres.type("text/html")
      res.body.to_string()
    }
  }

  private fun to_request(sreq: spark.Request): Request {
    val body_as_string = sreq.body()
    val mquery = mutableMapOf<String, String>()
    sreq.queryParams().each { mquery[it] = sreq.queryParams(it) }
    val query = dict_of(mquery)

    return Request(
      id              = request_id_counter.getAndIncrement(),
      method          = sreq.requestMethod().toLowerCase(),
      format          = Format.valueOf(query["format", default_format.to_string()]),
      path            = sreq.pathInfo(),
      query_as_string = sreq.queryString() ?: "",
      query           = query,
      body_as_string  = body_as_string,
      route_params    = dict_of(sreq.params()),
      body            = from_json(if ("" == body_as_string) "{}" else body_as_string)
    )
  }
}

private data class ErrorResponse(
  val error: String
) {
  val is_error = true
}