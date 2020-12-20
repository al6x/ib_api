package ib.lib

import bon.*

open class AsyncError(
  val request_id: Int,
  val code:       Int,
      message:    String
) : Exception("Error code $code $message${error_type(code, message)}") {

  fun is_contract_not_found_error(): Boolean {
    return (code == 200) and ("No security definition has been found for the request" == message)
  }

  companion object {
    // IB uses non-error messages in the `error` method, ignoring it.
    val non_errors = set_of(
      // Market data farm connection is OK	A notification that connection to the market data
      // server is ok.
      // This is a notification and not a true error condition, and is expected on first
      // establishing connection.
      2104,

      // A historical data farm is connected.	A notification that connection to the market data
      // server is ok.
      // This is a notification and not a true error condition, and is expected on first
      // establishing connection.
      2106,

      // No documentation for this code
      2158
    )

    val not_important_warnings = set_of(
      // A historical data farm is disconnected.
      // Indicates a connectivity problem to an IB server. Outside of the nightly IB server reset,
      // this typically indicates an underlying ISP connectivity issue.
      2105,

      // Not an error, delayed data this message will be sent
      // "Requested market data is not subscribed. Displaying delayed market data"
      10167,

      // "Warning: Approaching max rate of 50 messages per second"
      0,

      // "Part of requested market data is not subscribed"
      10090,

      // "Market data farm connection is inactive but should be available upon demand"
      2108
    )

    val not_important_errors = set_of(
      // Can't find EId with ticker Id:
      // An attempt was made to cancel market data for a ticker ID that was not associated with
      // a current subscription. With the DDE API this occurs by clearing the spreadsheet cell.
      300
    )

    fun ignore_error(id: Int, code: Int, message: String?): Boolean {
      return (code in non_errors) or (code in not_important_errors) or (code in not_important_warnings)
    }

    fun log_ignored_error(id: Int, code: Int, message: String?): Boolean {
      return (
        (code in not_important_errors) or (code in not_important_warnings)
      )
    }

    fun log_error(error: Exception): Boolean {
      return (!(error_type(error) in set_of(
        "not_found"
      )))
    }
  }
}

private fun error_type(code: Int, message: String): String = when {
  code == 200 && message == "No security definition has been found for the request" ->
    " :not_found"
  code == 354 ->
    " :no_subscription"
  else ->
    ""
}