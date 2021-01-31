package bon

object LogConfig {
  // List of components and levels to hide, separated by comma,
  // could be "HTTP" or "debug" or "HTTP_debug"
  val disable_logs: Set<String> = Env["disable_logs", ""].toLowerCase().split(",").to_set()

  fun is_enabled(component: String, level: String): Boolean {
    val c = component.toLowerCase(); val l = level
    return !((c in disable_logs) or (l in disable_logs) or ("${c}.$l" in disable_logs))
  }
}

class Log (
  val component: String = ""
) {
  fun info(message: String): Void {
    if (!LogConfig.is_enabled(component, "info")) return
    log_stdout("  " + format_component(component) + " " + message)
  }

  fun warn(message: String, error: Throwable? = null): Void {
    if (!LogConfig.is_enabled(component, "warn")) return
    log_stderr("W " + format_component(component) + " " + message)
    if (error != null) log_stderr(error) // Utils.get_clean_stack_trace(e)
  }

  fun error(message: String, error: Throwable? = null): Void {
    if (!LogConfig.is_enabled(component, "error")) return
    log_stderr("E " + format_component(component) + " " + message)
    if (error != null) log_stderr(error)
  }

  fun debug(message: String): Void {
    if (!LogConfig.is_enabled(component, "debug")) return
    log_stdout("  " + format_component(component) + " " + message)
  }
}

fun log_info(message: String): Void {
  if (!LogConfig.is_enabled("", "info")) return
  log_stdout("  " + format_component("") + " " + message)
}

fun log_warn(message: String): Void {
  if (!LogConfig.is_enabled("", "warn")) return
  log_stderr("W " + format_component("") + " " + message)
}

fun log_error(message: String, error: Throwable?): Void {
  if (!LogConfig.is_enabled("", "error")) return
  log_stderr("E " + format_component("") + " " + message)
  if (error != null) log_stderr(error)
}

private fun format_component(component: String): String {
  val padded = StringBuffer(component)
  while (padded.length < 4) padded.append(" ")
  return padded.to_string().substring(0, 4).toLowerCase() + " |"
}

private fun log_stdout(v: String) { println(v) }

private fun log_stderr(v: String) { System.err.println(v) }

private fun log_stderr(e: Throwable) { e.printStackTrace(System.err) }