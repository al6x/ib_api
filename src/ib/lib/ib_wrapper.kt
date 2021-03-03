package ib.lib

import bon.*
import bon.thread.sleep
import com.ib.client.*
import ib.IB
import ib.IbConfig
import ib.lib.EventTypes.InternalPriceType
import ib.lib.EventTypes.InternalSizeType
import ib.lib.EventTypes.InternalTimeType
import java.io.IOException
import javax.swing.SwingUtilities
import kotlin.concurrent.thread

private val log = Log("Wrapper")

class FinalEvent

data class IbWrapperEvent(val ename: String, val request_id: Int, val worker_id: Int, val event: Any)

class IBWrapper(
          val port:                 Int,
          val worker_id:            Int,
          val worker_connection_id: Int,
  private val send_event_to_worker: (IbWrapperEvent) -> Void
) : EWrapper {
  val id = "connection_" + worker_id + "_" + worker_connection_id;
  val created_at_ms = System.currentTimeMillis()

  // TWS API throws bunch of crap error after socket disconnect, this check needed to ignore that.
  private var is_terminated                = false
  private var signal:       EJavaSignal?   = null
  private var client:       EClientSocket? = null
  private var reader:       EReader?       = null

  private var next_request_id_from_tws = -1
  fun get_next_request_id_from_tws_sync() = synchronized(this) { next_request_id_from_tws }

  fun get_client() = client!!

  // Handling connection -----------------------------------------------------------------
  private fun client_thread_run() {
    while ((client != null) and !is_terminated and (client?.isConnected == true)) {
      signal!!.waitForSignal()
      try {
        SwingUtilities.invokeAndWait {
          try {
            reader!!.processMsgs()
          } catch (e: IOException) {
            error(e)
          }
        }
      } catch (e: Exception) {
        error(e)
      }
    }
  }

  fun ensure_connected_sync(): Boolean = synchronized(this) {
    if (is_terminated) throw Exception("Wrong usage, wrapper can't be used after it's terminated")
    if (client != null) return client!!.isConnected
    log.debug("$id connecting")
    signal = EJavaSignal()
    client = EClientSocket(this, signal)
    client!!.eConnect("", port, worker_id)
    reader = EReader(client, signal)
    reader!!.start()
    thread(isDaemon = true, name = "ib_connection_$id") { client_thread_run() }
    sleep(IbConfig.delay_ms)
    return client!!.isConnected
  }

  fun disconnect_sync(reason: String): Void = synchronized(this) {
    if (is_terminated) return
    this.is_terminated = true
    log.debug("$id disconnecting because $reason")
    client!!.eDisconnect()

    // TWS API generates bunch of crap errors after disconnect, waiting a little for it to shut up.
    Thread.sleep(IbConfig.delay_ms.toLong())
  }

  private fun on_event_sync(ename: String?, request_id: Int?, event: Any?): Void = synchronized(this) {
    log.debug("$id received $ename for request_id $request_id")
//    p(event)
    if (!is_terminated) send_event_to_worker(IbWrapperEvent(
      ename ?: "null-ename", request_id ?: -1, worker_id, event ?: "null-event"
    ))
  }

  // Special listeners -------------------------------------------------------------------
  @Synchronized
  override fun connectionClosed() {
    // No need to listhen, it will reconnect automatically
  }

  @Synchronized
  override fun connectAck() = client!!.startAPI()

  @Synchronized
  override fun error(error: Exception?) {
    // TWS API throws garbage errors after disconnect, ignoring it.
    if (!is_terminated) {
      log.error("$id unexpected error", error)
      send_event_to_worker(IbWrapperEvent("error", -1, worker_id, error ?: Exception("null-exception")))
    }
  }

  @Synchronized
  override fun error(message: String?) = this.error(-1, -1, message)

  @Synchronized
  override fun error(request_id_optional: Int, code_optional: Int, message_optional: String?) {
    val request_id = request_id_optional ?: -1
    val code       = code_optional       ?: -1
    val message    = message_optional    ?: "null error message"
    // TWS API throws garbage errors after disconnect, ignoring it.
    if (!is_terminated) {
      if (AsyncError.ignore_error(request_id, code, message)) {
        if (AsyncError.log_ignored_error(request_id, code, message)) {
          log.info("$id for request_id $request_id ignoring '$message'")
        }
      } else {
        val error = AsyncError(request_id, code, message)
        if (AsyncError.log_error(error)) log.error("$id for request_id $request_id", error)
        send_event_to_worker(
          IbWrapperEvent("error", request_id, worker_id, error)
        )
      }
    }
  }

  private fun catch_errors(block: () -> Void): Void = try { block() } catch (e: Exception) { error(e) }

  // Listeners ---------------------------------------------------------------------------
  @Synchronized
  override fun nextValidId(id: Int) { this.next_request_id_from_tws = id }

  override fun managedAccounts(accountsList: String?) {
    // Ignoring
  }

  // Data listeners --------------------------------------------------------------------
  private fun unexpected_emit(info: String?) { this.error("unexpected emit: $info") }

  data class PriceEvent(val type: InternalPriceType, val price: Double)

  override fun tickPrice(request_id: Int, field: Int, price: Double, attribs: TickAttrib?) = catch_errors {
    on_event_sync("tickPrice", request_id, PriceEvent(
      InternalPriceType.value_of(field), price)
    )
  }

  data class SizeEvent(val type: InternalSizeType, val size: Long)
  override fun tickSize(request_id: Int, field: Int, size: Int) = catch_errors {
    on_event_sync("tickSize", request_id, SizeEvent(
      InternalSizeType.value_of(field), size.toLong())
    )
  }

  val tickGeneric_ignored = set_of(
    TickType.HALTED
  )

  override fun tickGeneric(tickerId: Int, tickType: Int, value: Double) = catch_errors {
    if (TickType.get(tickType) in tickGeneric_ignored) {
      // Ignoring
    } else {
      unexpected_emit("tickGeneric tickType=${TickType.get(tickType)} value=$value")
    }
  }

  data class PriceTimestampEvent(val type: InternalTimeType, val time: Long)

  val tickString_ignored = set_of(
    TickType.LAST_EXCH,
    TickType.BID_EXCH,
    TickType.ASK_EXCH
  )

  override fun tickString(request_id: Int, tickType: Int, value: String?) = catch_errors {
    if (tickString_ignored.contains(TickType.get(tickType))) {
      // Ignoring
    } else {
      on_event_sync("tickString", request_id, PriceTimestampEvent(
        InternalTimeType.value_of(tickType), value!!.toLong()
      ))
    }
  }

  override fun tickSnapshotEnd(tickerId: Int) { unexpected_emit("tickSnapshotEnd") }

  override fun tickOptionComputation(
    tickerId: Int, field: Int, impliedVol: Double,
    delta: Double, optPrice: Double, pvDividend: Double,
    gamma: Double, vega: Double, theta: Double, undPrice: Double
  ) {
    // Ignored
  }

  override fun tickEFP(
    tickerId: Int, tickType: Int, basisPoints: Double,
    formattedBasisPoints: String?, impliedFuture: Double, holdDays: Int,
    futureLastTradeDate: String?, dividendImpact: Double, dividendsToLastTradeDate: Double
  ) {
    unexpected_emit("tickEFP")
  }

  override fun orderStatus(
    orderId: Int, status: String?, filled: Double, remaining: Double,
    avgFillPrice: Double, permId: Int, parentId: Int, lastFillPrice: Double,
    clientId: Int, whyHeld: String?, mktCapPrice: Double
  ) {
    unexpected_emit("orderStatus")
  }

  override fun openOrder(orderId: Int, contract: Contract?, order: Order?, orderState: OrderState?) {
    unexpected_emit("openOrder")
  }

  override fun openOrderEnd() {
    unexpected_emit("openOrderEnd")
  }

  override fun updateAccountValue(key: String?, value: String?, currency: String?, accountName: String?) {
    unexpected_emit("updateAccountValue")
  }

  override fun updatePortfolio(
    contract: Contract, position: Double, marketPrice: Double, marketValue: Double,
    averageCost: Double, unrealizedPNL: Double, realizedPNL: Double, accountName: String
  ) {
    unexpected_emit("updatePortfolio")
  }

  override fun updateAccountTime(timeStamp: String?) {
    unexpected_emit("updateAccountTime")
  }

  override fun accountDownloadEnd(accountName: String?) {
    unexpected_emit("accountDownloadEnd")
  }

  override fun contractDetails(request_id: Int, contractDetails: ContractDetails?) {
    on_event_sync("contractDetails", request_id, contractDetails)
  }

  override fun contractDetailsEnd(request_id: Int) {
    on_event_sync("contractDetailsEnd", request_id, FinalEvent())
  }

  override fun bondContractDetails(request_id: Int, contractDetails: ContractDetails?) {
    unexpected_emit("bondContractDetails")
  }

  override fun execDetails(request_id: Int, contract: Contract?, execution: Execution?) {
    unexpected_emit("execDetails")
  }

  override fun execDetailsEnd(request_id: Int) {
    unexpected_emit("execDetailsEnd")
  }

  override fun updateMktDepth(
    request_id: Int, position: Int, operation: Int, side: Int, price: Double, size: Int
  ) {
    unexpected_emit("updateMktDepth")
  }

  override fun updateMktDepthL2(
    request_id: Int, position: Int, marketMaker: String?, operation: Int,
    side: Int, price: Double, size: Int, isSmartDepth: Boolean
  ) {
    unexpected_emit("updateMktDepthL2")
  }

  override fun updateNewsBulletin(request_id: Int, msgType: Int, message: String?, origExchange: String?) {
    unexpected_emit("updateNewsBulletin")
  }

  override fun receiveFA(faDataType: Int, xml: String?) {
    unexpected_emit("receiveFA")
  }

  override fun historicalData(request_id: Int, bar: Bar?) {
    unexpected_emit("historicalData")
  }

  override fun scannerParameters(xml: String?) {
    unexpected_emit("scannerParameters")
  }

  override fun scannerData(
    request_id: Int, rank: Int, contractDetails: ContractDetails?, distance: String?,
    benchmark: String?, projection: String?, legsStr: String?
  ) {
    unexpected_emit("scannerData")
  }

  override fun scannerDataEnd(request_id: Int) {
    unexpected_emit("scannerDataEnd")
  }

  override fun realtimeBar(
    request_id: Int, time: Long, open: Double, high: Double, low: Double, close: Double,
    volume: Long, wap: Double, count: Int
  ) { unexpected_emit("realtimeBar") }

  override fun currentTime(millis: Long) {
    unexpected_emit("currentTime")
  }

  override fun fundamentalData(request_id: Int, data: String?) {
    unexpected_emit("fundamentalData")
  }

  override fun deltaNeutralValidation(reqId: Int, deltaNeutralContract: DeltaNeutralContract?) {
    unexpected_emit("deltaNeutralValidation")
  }

  data class MarketDataTypeEvent(val type: IB.MarketDataType)
  override fun marketDataType(request_id: Int, marketDataType: Int) = catch_errors {
    on_event_sync("marketDataType", request_id,
      MarketDataTypeEvent(IB.MarketDataType.value_of(marketDataType))
    )
  }

  override fun commissionReport(commissionReport: CommissionReport?) {
    // Ignored
  }

  override fun position(account: String?, contract: Contract?, pos: Double, avgCost: Double) {
    unexpected_emit("position")
  }

  override fun positionEnd() {
    unexpected_emit("positionEnd")
  }

  data class AccountSummaryEvent(
    val account_id: String,
    val tag:        String,
    val value:      String,
    val currency:   String?
  )

  override fun accountSummary(reqId: Int, account: String?, tag: String?, value: String?, currency: String?) {
    on_event_sync("accountSummary", reqId,
      AccountSummaryEvent(account!!, tag!!, value!!, currency)
    )
  }

  override fun accountSummaryEnd(reqId: Int) {
    on_event_sync("accountSummaryEnd", reqId, FinalEvent())
  }

  override fun verifyMessageAPI(apiData: String?) {
    unexpected_emit("verifyMessageAPI")
  }

  override fun verifyCompleted(isSuccessful: Boolean, errorText: String?) {
    unexpected_emit("verifyCompleted")
  }

  override fun verifyAndAuthMessageAPI(apiData: String?, xyzChallenge: String?) {
    unexpected_emit("verifyAndAuthMessageAPI")
  }

  override fun verifyAndAuthCompleted(isSuccessful: Boolean, errorText: String?) {
    unexpected_emit("verifyAndAuthCompleted")
  }

  override fun displayGroupList(reqId: Int, groups: String?) {
    unexpected_emit("displayGroupList")
  }

  override fun displayGroupUpdated(reqId: Int, contractInfo: String?) {
    unexpected_emit("displayGroupUpdated")
  }

  data class ContractWithPositionMultiEvent(
    val account_id:   String,
    val model_code:   String?,
    val contract:     Contract,
    val position:     Double,
    val average_cost: Double
  )
  override fun positionMulti(
    request_id: Int, account_id: String?, model_code: String?, contract: Contract?, position: Double,
    average_cost: Double
  ) = catch_errors {
    on_event_sync("positionMulti", request_id,
      ContractWithPositionMultiEvent(
        account_id!!, model_code, contract!!, position, average_cost
      )
    )
  }

  override fun positionMultiEnd(request_id: Int) {
    on_event_sync("positionMultiEnd", request_id, FinalEvent())
  }

  override fun accountUpdateMulti(
    request_id: Int, account: String?, modelCode: String?, key: String?, value: String?, currency: String?
  ) { unexpected_emit("accountUpdateMulti") }

  override fun accountUpdateMultiEnd(request_id: Int) {
    unexpected_emit("accountUpdateMultiEnd")
  }

  data class SecurityDefinitionOptionalParameterEvent(
    val exchange:          String,
    val underlying_con_id: Int,
    val trading_class:     String,
    val multiplier:        String,
    val expirations:       Set<String>,
    val strikes:           Set<Double>
  )

  override fun securityDefinitionOptionalParameter(
    request_id: Int, exchange: String?, underlying_con_id: Int, trading_class: String?,
    multiplier: String?, expirations: Set<String?>?, strikes: Set<Double>?
  ) = catch_errors {
    on_event_sync(
      "securityDefinitionOptionalParameter", request_id,
      SecurityDefinitionOptionalParameterEvent(
        exchange!!,
        underlying_con_id,
        trading_class!!,
        multiplier!!,
        expirations!!.map { v -> v!! }.to_set(),
        strikes!!
      )
    )
  }

  override fun securityDefinitionOptionalParameterEnd(request_id: Int) {
    on_event_sync("securityDefinitionOptionalParameterEnd", request_id, FinalEvent())
  }

  override fun softDollarTiers(request_id: Int, tiers: Array<SoftDollarTier>?) {
    unexpected_emit("softDollarTiers")
  }

  override fun familyCodes(familyCodes: Array<FamilyCode>?) {
    unexpected_emit("familyCodes")
  }

  override fun symbolSamples(request_id: Int, contractDescriptions: Array<ContractDescription>?) {
    unexpected_emit("symbolSamples")
  }

  override fun historicalDataEnd(request_id: Int, startDateStr: String?, endDateStr: String?) {
    unexpected_emit("historicalDataEnd")
  }

  override fun mktDepthExchanges(depthMktDataDescriptions: Array<DepthMktDataDescription>?) {
    unexpected_emit("mktDepthExchanges")
  }

  override fun tickNews(
    tickerId: Int, timeStamp: Long, providerCode: String?, articleId: String?, headline: String?,
    extraData: String?
  ) {
    unexpected_emit("tickNews")
  }

  override fun smartComponents(reqId: Int, theMap: Map<Int, Map.Entry<String, Char>>?) {
    unexpected_emit("smartComponents")
  }

  data class TickReqParamsEvent(
    val min_tick: Double, val bbo_exchange: String?, val snapshot_permissions: Int
  )

  override fun tickReqParams(
    request_id: Int, min_tick: Double, bbo_exchange: String?, snapshot_permissions: Int
  ) {
    on_event_sync("tickReqParams", request_id,
      TickReqParamsEvent(min_tick, bbo_exchange, snapshot_permissions)
    )
  }

  override fun newsProviders(newsProviders: Array<NewsProvider>?) {
    unexpected_emit("newsProviders")
  }

  override fun newsArticle(requestId: Int, articleType: Int, articleText: String?) {
    unexpected_emit("newsArticle")
  }

  override fun historicalNews(
    requestId: Int, time: String?, providerCode: String?, articleId: String?, headline: String?
  ) { unexpected_emit("historicalNews") }

  override fun historicalNewsEnd(requestId: Int, hasMore: Boolean) {
    unexpected_emit("historicalNewsEnd")
  }

  override fun headTimestamp(reqId: Int, headTimestamp: String?) {
    unexpected_emit("headTimestamp")
  }

  override fun histogramData(reqId: Int, items: List<HistogramEntry>?) {
    unexpected_emit("histogramData")
  }

  override fun historicalDataUpdate(reqId: Int, bar: Bar?) {
    unexpected_emit("historicalDataUpdate")
  }

  override fun pnl(reqId: Int, dailyPnL: Double, unrealizedPnL: Double, realizedPnL: Double) {
    unexpected_emit("pnl")
  }

  override fun rerouteMktDataReq(reqId: Int, conId: Int, exchange: String?) {
    unexpected_emit("rerouteMktDataReq")
  }

  override fun rerouteMktDepthReq(reqId: Int, conId: Int, exchange: String?) {
    unexpected_emit("rerouteMktDepthReq")
  }

  override fun marketRule(marketRuleId: Int, priceIncrements: Array<PriceIncrement>?) {
    unexpected_emit("marketRule")
  }

  override fun pnlSingle(
    reqId: Int, pos: Int, dailyPnL: Double, unrealizedPnL: Double, realizedPnL: Double, value: Double
  ) {
    unexpected_emit("pnlSingle")
  }

  override fun historicalTicks(reqId: Int, ticks: List<HistoricalTick>?, last: Boolean) {
    unexpected_emit("historicalTicks")
  }

  override fun historicalTicksBidAsk(reqId: Int, ticks: List<HistoricalTickBidAsk>?, done: Boolean) {
    unexpected_emit("historicalTicksBidAsk")
  }

  override fun historicalTicksLast(reqId: Int, ticks: List<HistoricalTickLast>?, done: Boolean) {
    unexpected_emit("historicalTicksLast")
  }

  override fun tickByTickAllLast(
    reqId: Int, tickType: Int, time: Long, price: Double, size: Int, tickAttribLast: TickAttribLast?,
    exchange: String?, specialConditions: String?
  ) {
    unexpected_emit("tickByTickAllLast")
  }

  override fun tickByTickBidAsk(
    reqId: Int, time: Long, bidPrice: Double, askPrice: Double, bidSize: Int, askSize: Int,
    tickAttribBidAsk: TickAttribBidAsk?
  ) {
    unexpected_emit("tickByTickBidAsk")
  }

  override fun tickByTickMidPoint(reqId: Int, time: Long, midPoint: Double) {
    unexpected_emit("tickByTickMidPoint")
  }

  override fun orderBound(orderId: Long, apiClientId: Int, apiOrderId: Int) {
    unexpected_emit("orderBound")
  }

  override fun completedOrder(contract: Contract?, order: Order?, orderState: OrderState?) {
    unexpected_emit("completedOrder")
  }

  override fun completedOrdersEnd() {
    unexpected_emit("completedOrdersEnd")
  }
}

// Sometimes event may arrive too late, after the request already processed, in such case some events
// could be ignored and some should be reported as waringns.
val late_events_that_could_be_ignored = set_of(
  IBWrapper.PriceEvent::class.simpleName,
  IBWrapper.SizeEvent::class.simpleName,
  IBWrapper.MarketDataTypeEvent::class.simpleName,
  IBWrapper.TickReqParamsEvent::class.simpleName,
  IBWrapper.PriceTimestampEvent::class.simpleName
)