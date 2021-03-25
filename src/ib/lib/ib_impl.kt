package ib.lib

import bon.*
import bon.thread.sleep
import com.ib.client.Contract
import com.ib.client.ContractDetails
import com.ib.client.EClientSocket
import com.ib.client.Types
import ib.IB
import ib.IbConfig
import ib.support.*

private val log = Log("IB")


// All methods are synchronized and will be called sequentially.
// This is done to provide request isolation, some methods like `client.reqMarketDataType()` are global
// and calling it in one request may affect another request.
class IBImpl(port: Int = IbConfig.ib_port) : IB() {
  private val queue   = IBQueue()
  private val workers = mutable_list_of<Worker>()

  init {
    log.info(
      "will be connected to TWS on port $port with ${IbConfig.workers_count} parallel connections"
    )
    for (worker_id in 1..IbConfig.workers_count) {
      workers.add(Worker(queue, port, worker_id))
    }
  }


  override fun get_portfolio(): List<Portfolio> {
    log.info("get_portfolio")
    val events = queue.process(
      "get_portfolio",
      "portfolio",
      { _, request_id, client ->
        client.reqPositionsMulti(request_id, "all", null)
      },
      { _, request_id, client ->
        client.cancelPositionsMulti(request_id)
      },
      { _, errors, events, final_event, _, _ -> when {
        !errors.is_empty() -> throw errors.first() // Throwing just the first error for simplicity
        final_event        -> events
        else               -> null
      }},
      IbConfig.recommended_waiting_time,
      IbConfig.timeout_ms
    )

    // Grouping events by account
    val accounts = events
      .map { e -> e as IBWrapper.ContractWithPositionMultiEvent }
      // The `reqPositionsMulti` returns multiple events for every position, one for position itself
      // I suppose with `model_code == null` and also multiple events for same position with different
      // models, with the model name specified in `model_code` - ignoring those duplicates.
      .filter { c -> c.model_code == null }
      .groupBy { e -> e.account_id }

    // Getting cash
    val cash = get_account_cash()

    // Parsing
    assert(accounts.keys == cash.keys) {
      "accounts for portfolio positions ${accounts.keys} and cash positions ${cash.keys} are not matching"
    }

    return accounts.map { events, account_id ->
      val stocks  = mutable_list_of<PortfolioPosition<PortfolioStockContract>>()
      val options = mutable_list_of<PortfolioPosition<PortfolioOptionContract>>()

      // Ensuring there's no duplicates, as IB in some cases respond with same position
      // and different event names and it's easy to make mistake and return multiple same positions.
      assert(stocks.distinct().size  == stocks.size)
      assert(options.distinct().size == options.size)

      events.each { event ->
        val position = Converter.parse_and_add_portfolio_position(account_id, event)
        when (position.contract) {
          is PortfolioStockContract  -> stocks.add(position as PortfolioPosition<PortfolioStockContract>)
          is PortfolioOptionContract -> options.add(position as PortfolioPosition<PortfolioOptionContract>)
        }
      }

      Portfolio(
        account_id    = account_id,
        stocks        = stocks,
        stock_options = options,
        cash_in_usd   = cash[account_id]!!
      )
    }.values.to_list()
  }


  // Get accounts cash, all cash converted to USD
  private fun get_account_cash(): Dict<String, Double> {
    log.info("get_account_cash")
    val events = queue.process(
      "get_account_cash",
      "account cash",
      { _, request_id, client ->
        client.reqAccountSummary(request_id, "All", "TotalCashValue")
      },
      { _, request_id, client ->
        client.cancelAccountSummary(request_id)
      },
      { _, errors, events, final_event, _, _ -> when {
        !errors.is_empty() -> throw errors.first() // Throwing just the first error for simplicity
        final_event        -> events
        else               -> null
      }},
      IbConfig.recommended_waiting_time,
      IbConfig.timeout_ms
    )

    val cash = mutable_dict_of<String, Double>()
    events.each { raw_event ->
      val event = raw_event as IBWrapper.AccountSummaryEvent
      assert(event.account_id != "") { "empty account" }
      assert(event.tag == "TotalCashValue") { "unexpected tag '${event.tag}'" }
      assert(event.currency == "USD") { "unexpected currency '${event.currency}'" }
      assert(!(event.account_id in cash)) { "multiple events for same account_id" }
      cash[event.account_id] = event.value.toDouble()
    }

    return dict_of(cash)
  }


  override fun get_stock_contract(symbol: String, exchange: String, currency: String): StockContract {
    log.info("get_stock_contract $symbol $exchange $currency")

    val contract = Contract()
    contract.symbol(symbol)
    contract.currency(currency)
    contract.exchange(exchange)
    contract.secType(Types.SecType.STK)

    val cd = queue.process(
      "get_contract",
      contract,
      { _, request_id, client ->
        client.reqContractDetails(request_id, contract)
      },
      null,
      { _, errors, events, final_event, _, _-> when {
        !errors.is_empty() -> throw errors.first() // Throwing just the first error for simplicity
        final_event        -> {
          assert(events.size == 1) { "Wrong events size ${events.size}" }
          events.first() as ContractDetails
        }
        else               -> null
      }},
      IbConfig.recommended_waiting_time,
      IbConfig.timeout_ms
    )
    return Converter.parse_stock_contract(cd)
  }


  override fun get_stock_option_chain_contracts_by_expiration(
    symbol: String, expiration: String, option_exchange: String, currency: String
  ): List<OptionContract> {
    log.info("get_stock_option_chain_contracts_by_expiration $symbol $expiration $option_exchange $currency")

    val contract = Contract()
    contract.symbol(symbol)
    contract.lastTradeDateOrContractMonth(Converter.yyyy_mm_dd_to_yyyymmdd(expiration))
    contract.currency(currency)
    contract.exchange(option_exchange)
    contract.secType(Types.SecType.OPT)

    val events = queue.process(
      "get_stock_option_chain_contracts",
      contract,
      { _, request_id, client ->
        client.reqContractDetails(request_id, contract)
      },
      null,
      { _, errors, events, final_event, _, _ -> when {
        !errors.is_empty() -> throw errors.first() // Throwing just the first error for simplicity
        final_event        -> events
        else               -> null
      }},
      IbConfig.recommended_waiting_time,
      IbConfig.large_timeout_ms
    )

    return events.map { event -> when (event) {
      is ContractDetails -> Converter.parse_option_contract(
        symbol, option_exchange, currency, event
      ).first
      else -> throw Exception("wrong option contract class ${event::class}")
    }}
  }


  override fun get_stock_contracts(symbol: String): List<StockContract> {
    log.info("get_stock_contracts $symbol")

    val contract = Contract()
    contract.symbol(symbol)
    contract.secType(Types.SecType.STK)

    val events = queue.process(
      "get_contracts",
      contract,
      { _, request_id, client ->
        client.reqContractDetails(request_id, contract)
      },
      null,
      { _, errors, events, final_event, _, _ -> when {
        !errors.is_empty() -> throw errors.first() // Throwing just the first error for simplicity
        final_event        -> events.map { event -> event as ContractDetails }
        else               -> null
      }},
      IbConfig.recommended_waiting_time,
      IbConfig.timeout_ms
    )
    return events.map(Converter::parse_stock_contract)
  }


  override fun inspect_stock(
    symbol:   String  // MSFT
  ): StockInspection = inspect_stock(this, symbol)


  override fun get_stock_price(
    symbol: String, exchange: String, currency: String, data_type: MarketDataType?
  ): SnapshotPrice {
    log.info("get_stock_price $symbol $exchange $currency $data_type")
    val contract = Contract()
    contract.symbol(symbol)
    contract.secType(Types.SecType.STK)
    contract.currency(currency)
    contract.exchange(exchange)
    return get_stock_price(contract, data_type)
  }


  override fun get_stock_price_by_id(
    id: Int, exchange: String, currency: String, data_type: MarketDataType?
  ): SnapshotPrice {
    log.info("get_stock_price_by_id $id $exchange $currency $data_type")

    val contract = Contract()
    contract.conid(id)
    contract.secType(Types.SecType.STK)
    contract.currency(currency)
    contract.exchange(exchange)
    return get_stock_price(contract, data_type)
  }


  private fun get_stock_price(contract: Contract, data_type: MarketDataType?): SnapshotPrice {
    return get_last_price("get_last_stock_price", contract, data_type ?: MarketDataType.realtime)
  }


  override fun get_stock_option_chains(
    symbol: String, exchange: String, currency: String
  ): OptionChains {
    log.info("get_stock_option_chain $symbol $exchange $currency")

    // Getting underlying stock contract
    val ucontract: StockContract = get_stock_contract(symbol, exchange, currency)

    return get_stock_option_chains(symbol, ucontract.id)
  }

  fun get_stock_option_chains(
    symbol: String, underlying_id: Int
  ): OptionChains {
    // Getting expirations and strikes
    val events = queue.process(
      "get_stock_option_chains",
      Pair(symbol, underlying_id),
      { _, request_id, client ->
        // If exchange provide explicitly it's not returned, seems like it's either bug
        // or the parameter means something else.
        client.reqSecDefOptParams(request_id, symbol, "", "STK", underlying_id)
      },
      null,
      { _, errors, events, final_event, _, _ -> when {
        !errors.is_empty() -> throw errors.first() // Throwing just the first error for simplicity
        final_event        -> events
        else               -> null
      }},
      IbConfig.recommended_waiting_time,
      IbConfig.timeout_ms
    )

    // Parsing events
    var chains = events.map { event -> when (event) {
      is IBWrapper.SecurityDefinitionOptionalParameterEvent -> {
        val multiplier = Converter.parse_multiplier(event.multiplier, symbol)
//        assert(multiplier in set_of(100, 1000)) {
//          "wrong option contract multiplier for $symbol expected 100 got ${event.multiplier}"
//        }
        OptionChain(
          option_exchange = event.exchange,
          expirations_asc = event.expirations.map(Converter::yyyymmdd_to_yyyy_mm_dd).sorted(),
          strikes_asc     = event.strikes.to_list().sorted(),
          multiplier      = multiplier
        )
      }
      else -> throw Exception("wrong event class ${event::class}")
    }}

    // Selecting largest chain for every exchange
    // Sorting by size of expirations and strikes and then taking first unique
    chains = chains.sort_by({ it.expirations_asc.size }, { it.strikes_asc.size }).reversed()
    val largest_desc = mutable_list_of<OptionChain>()
    for (chain in chains) {
      if (!largest_desc.any { c -> c.option_exchange == chain.option_exchange}) {
        largest_desc.add(chain)
      }
    }

    return OptionChains(
      largest_desc = largest_desc,
      all          = chains
    )
  }

  override fun get_stock_option_chain_contracts(
    symbol: String, option_exchange: String, currency: String
  ): OptionContracts {
    log.info("get_stock_option_chain_contracts $symbol $option_exchange $currency")

    val contract = Contract()
    contract.symbol(symbol)
    contract.secType(Types.SecType.OPT)
    contract.currency(currency)
    contract.exchange(option_exchange)

    // Getting events
    val events = queue.process(
      "get_option_contracts",
      contract,
      { _, id, client ->
        client.reqContractDetails(id, contract)
      },
      null,
      { _, errors, events, final_event, _, _ -> when {
        !errors.is_empty() -> throw errors.first() // Throwing just the first error for simplicity
        final_event        -> events
        else               -> null
      }},
      IbConfig.recommended_waiting_time,
      IbConfig.large_timeout_ms
    )

    // Parsing and sorting events
    val contracts = events
      .map { event -> when (event) {
        is ContractDetails -> Converter.parse_option_contract(symbol, option_exchange, currency, event)
        else               -> throw Exception("Wrong event type $event")
      }}
    val multiplier = contracts.first().second
    assert (contracts.all { (_, m) -> m == multiplier }) { "all option contracts should have same multiplier" }

    return OptionContracts(
      multiplier                               = multiplier,
      contracts_asc_by_right_expiration_strike = contracts
        .map { (c, _) -> c }
        .sort_by({ it.right }, { it.expiration }, { it.strike })
    )
  }


  override fun get_stock_option_price(
    symbol:          String,         // MSFT
    right:           String,         // "put" or "call"'
    expiration:      String,         // 2020-08-21
    strike:          Double,         // 120
    option_exchange: String,         // AMEX, different from the stock exchange
    currency:        String,         // USD
    data_type:       MarketDataType? // optional, realtime by default
  ): SnapshotPrice {
    log.info("get_stock_option_price $symbol-$right-$expiration-$strike $option_exchange $currency $data_type")

    val contract = Contract()
    contract.secType(Types.SecType.OPT)
    contract.symbol(symbol)
    contract.right(Converter.right_to_ib_right(right))
    contract.lastTradeDateOrContractMonth(Converter.yyyy_mm_dd_to_yyyymmdd(expiration))
    contract.strike(strike)
    contract.exchange(option_exchange)
    contract.currency(currency)
//    contract.multiplier("100")

    return get_last_price("get_stock_option_price", contract, data_type ?: MarketDataType.realtime)
  }


  override fun get_stock_option_price_by_id(
    id:              Int,            // Contract id 426933553
    option_exchange: String,         // AMEX, different from the stock exchange
    currency:        String,         // USD
    data_type:       MarketDataType? // optional, realtime by default
  ): SnapshotPrice {
    log.info("get_stock_option_price_by_id $id $option_exchange $currency $data_type")

    // // Querying option contract ids if it's not provided, it's very slow operation.
    // if (option_contracts_ids_optional == null) {
    //   option_contracts_ids_optional = map(
    //     get_stock_option_contracts(symbol, exchange, currency).contracts_asc_by_right_expiration_strike,
    //     (contract) -> contract.id
    //   );
    // }

    val contract = Contract()
    contract.exchange(option_exchange)
    contract.currency(currency)
    contract.conid(id)
    contract.secType(Types.SecType.OPT)
    return get_last_price("get_stock_option_price_by_id", contract, data_type ?: MarketDataType.realtime)
  }


  private fun get_last_price(
    type: String, contract: Contract, data_type: MarketDataType
  ): SnapshotPrice {
    val prices = get_last_prices(type, list_of(contract), data_type)
    assert(prices.size == 1) { "wrong size for prices ${prices.size}" }
    return prices[0]
  }


  private fun get_last_prices(
    name: String, contracts: List<Contract>, data_type: MarketDataType
  ): List<SnapshotPrice> {
    class GetLastPriceExecutor(contract: Contract) : Executor<Contract, SnapshotPrice> (contract) {
      var state = "make_requests"

      override fun step(
        request_id: Int, client: () -> EClientSocket,
        errors: List<AsyncError>, events: List<Any>, final_event: Boolean,
        waited_recommended_time: Boolean, timed_out: Boolean
      ): SnapshotPrice? = when (state) {
        "make_requests" -> {
          client().reqMarketDataType(data_type.code)
          sleep(50)
          client().reqMktData(request_id, task, "", false, false, null)
          state = "wait_for_prices"
          null
        }
        // "wait_for_data_type_event" -> {
        //   if (!errors.is_empty()) throw errors.first() // Throwing just the first error for simplicity
        //   val found = events.find { event -> when (event) {
        //     is IBWrapper.MarketDataTypeEvent -> event.type.code == data_type.code
        //     else                             -> false
        //   } }
        //   if (found != null) {
        //     state = "wait_for_prices"
        //   }
        //   null
        // }
        "wait_for_prices" -> wait_for_prices(errors, events, waited_recommended_time)
        else              -> throw java.lang.Exception("invalid state $state")
      }

      override fun cancel(request_id: Int, client: () -> EClientSocket): Void {
        client().cancelMktData(request_id)
      }

      private fun wait_for_prices(
        errors: List<AsyncError>, events: List<Any>, waited_recommended_time: Boolean
      ): SnapshotPrice? {
        if (!errors.is_empty()) throw errors.first() // Throwing just the first error for simplicity

        // Scanning if events contain price events
        val last_price_events  = mutable_list_of<IBWrapper.PriceEvent>()
        val close_price_events = mutable_list_of<IBWrapper.PriceEvent>()
        val ask_price_events   = mutable_list_of<IBWrapper.PriceEvent>()
        val bid_price_events   = mutable_list_of<IBWrapper.PriceEvent>()

        val last_price_event_types = set_of(
          EventTypes.InternalPriceType.LAST,
          EventTypes.InternalPriceType.DELAYED_LAST
        )

        val close_price_event_types = set_of(
          EventTypes.InternalPriceType.CLOSE,
          EventTypes.InternalPriceType.DELAYED_CLOSE
        )

        val ask_price_event_types = set_of(
          EventTypes.InternalPriceType.ASK,
          EventTypes.InternalPriceType.DELAYED_ASK
        )

        val bid_price_event_types = set_of(
          EventTypes.InternalPriceType.BID,
          EventTypes.InternalPriceType.DELAYED_BID
        )

        // If specified data type event occured
        // var data_type_event_occured = false

        for (event in events) when (event) {
          is IBWrapper.PriceEvent -> {
            // if (data_type_event_occured) {
            when (event.type) {
              in last_price_event_types  -> last_price_events.add(event)
              in close_price_event_types -> close_price_events.add(event)
              in ask_price_event_types   -> ask_price_events.add(event)
              in bid_price_event_types   -> bid_price_events.add(event)
              else                       -> {
                // Ignoring
              }
            }
            // }
          }
          is IBWrapper.MarketDataTypeEvent -> {
            // Ignoring, it works in a very strange way
            //
            // if (data_type_event_occured) {
            //   if (event.type.code != data_type.code) throw Exception(
            //     "Wrong MarketDataType event occured ${event.type}"
            //   )
            // } else {
            //   // Ignoring all events before the specified MarketDataType event occured
            //   if (event.type.code == data_type.code) data_type_event_occured = true
            // }
          }
          is IBWrapper.PriceTimestampEvent -> {
            // Ignoring for now, not sure how it works, it's not always available
            // if (event.type == TimeType.LAST_TIME || timeEvent.type == TimeType.LAST_TIME_DELAYED) {
            //   timeMs = event.time;
            // }
          }
          is IBWrapper.SizeEvent, is IBWrapper.TickReqParamsEvent -> {
            // Ignoring for now
          }
          else -> throw Exception("wrong price event ${event::class}")
        }

        fun get_price(events: List<IBWrapper.PriceEvent>): Double? {
          // Checking that price > 0, the Interactive Brokers may respond with `0` or `-1`
          // Try /api/v1/stock_price?symbol=6752&currency=JPY&data_type=delayed
          return events.find { it.price > 0 } ?.price
        }

        // Parsing found prices
        val last_price  = get_price(last_price_events)
        val close_price = get_price(close_price_events)
        val ask_price   = get_price(ask_price_events)
        val bid_price   = get_price(bid_price_events)

        val approximate_price = approximate_price(
          last_price = last_price, close_price = close_price, ask_price = ask_price, bid_price = bid_price
        )

        return if (
          // Approximate price should always be available
          approximate_price != null && (
            // All price events fired
            (
              !last_price_events.is_empty() && !close_price_events.is_empty() &&
              !ask_price_events.is_empty() && !bid_price_events.is_empty()
            ) ||
            // Waited for recommended time and has some price
            waited_recommended_time
          )
        ) {
          SnapshotPrice(
            last_price        = last_price,
            close_price       = close_price,
            ask_price         = ask_price,
            bid_price         = bid_price,
            approximate_price = approximate_price,
            data_type         = data_type
          )
        } else null
      }
    }

    return queue.process_all(
      name,
      contracts,
      { contract -> GetLastPriceExecutor(contract) },
      IbConfig.recommended_waiting_time,
      IbConfig.timeout_ms
    )
  }
}