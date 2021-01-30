package ib.lib

import bon.*
import bon.thread.execute_in_parallel
import ib.IB.*
import ib.IbConfig

// Prints info about stock, exchanges it trades, prices, option chain, option chain prices etc.
fun inspect_stock(
  ib:       IBImpl,
  symbol:   String  // MSFT
): StockInspection {
  // Stock info
  val stock_contracts = ib.get_stock_contracts(symbol)
  val grouped = stock_contracts
    .group_by { stock -> stock.id }
    .map { list, id ->
      // Currency
      val currencies = list.map { c -> c.currency }.distinct()
      assert(currencies.size == 1) { "different currencies $currencies for same id $id" }
      val currency = currencies.first()

      // Exchanges
      val secondary_exchanges = list.map { c -> c.exchange }.distinct()
      val primary_exchanges = list.map { c -> c.primary_exchange }.distinct()
      assert(primary_exchanges.size == 1) {
        "different primary exchanges $primary_exchanges for same id $id"
      }
      val primary_exchange = primary_exchanges.first()
      val exchanges = (list_of(primary_exchange) + secondary_exchanges).distinct()

      // Getting option chain for every exchange
      val option_chains = ib.get_stock_option_chains(symbol, id)

      // Getting option chain contracts for every exchange
      val option_chain_contracts = execute_in_parallel(option_chains.largest_desc.map { chain -> {
        ib.get_stock_option_chain_contracts(symbol, chain.option_exchange, currency)
      }}, threads = IbConfig.http_server_batch_call_thread_pool)

      // Counting contracts for every exchange
      val option_chain_contracts_count: Dict<String, ErrorneousS<Int>> = option_chains.largest_desc
        .zip(option_chain_contracts)
        .map { (chain, contracts) ->
          Pair(chain.option_exchange, when (contracts) {
            is Success -> SuccessS(contracts.result.contracts_asc_by_right_expiration_strike.size)
            is Fail    -> FailS<Int>(contracts.error)
          })
        }
        .to_dict()

      // Getting option prices
      val option_chain_prices = get_aggregated_option_prices(
        ib, currency, option_chains.largest_desc, option_chain_contracts
      )

      GroupedStocks(
        currency                     = currency,
        names                        = list.map { c -> c.name }.distinct(),
        exchanges                    = exchanges,
        primary_exchange             = primary_exchange,
        largest_option_chains_desc   = option_chains.largest_desc.to_dict { v -> v.option_exchange },
        stock_prices                 = get_stock_prices(ib, symbol, currency, exchanges),
        option_chain_contracts_count = option_chain_contracts_count,
        option_chain_prices          = option_chain_prices
      )
    }

  return StockInspection(
    stocks = grouped
  )
}

private fun get_stock_prices(
  ib:        IBImpl,
  symbol:    String,
  currency:  String,
  exchanges: List<String>
): Dict<String, ErrorneousS<SnapshotPrice>> {
  val prices = execute_in_parallel(exchanges.map { exchange -> {
    ib.get_stock_price(symbol, exchange, currency, MarketDataType.delayed_frozen)
  }}, threads = IbConfig.http_server_batch_call_thread_pool)
    .map { errorneous -> ErrorneousS.from(errorneous) }

  return exchanges.zip(prices).to_dict()
}

private fun get_aggregated_option_prices(
  ib:                     IBImpl,
  currency:               String,
  option_chains:          List<OptionChain>,
  option_chain_contracts: List<Errorneous<OptionContracts>>
): Dict<String, StockOptionPricesInfo> {
  val contracts = option_chains
    .zip(option_chain_contracts)
    .filter_map { (chain, contracts) -> when (contracts) {
      is Success -> Pair(chain, contracts.result)
      is Fail    -> null
    }}

  // Getting all prices at once because it's much faster than getting price for each exchange sequentially
  val flat_tasks = contracts
    .map { (chain, contracts) ->
      contracts.contracts_asc_by_right_expiration_strike.map { contract ->
        val task = {
          ib.get_stock_option_price_by_id(
            contract.id, chain.option_exchange, currency, MarketDataType.delayed_frozen
          )
        }
        Pair(chain.option_exchange, task)
      }
    }
    .flatten()

  val flat_prices = execute_in_parallel(flat_tasks.map { (_, fn) -> fn },
    threads = IbConfig.http_server_batch_call_thread_pool)

  // Grouping flat tasks by exchange
  val exchanges_prices = flat_tasks
    .zip(flat_prices)
    .map { (exchange_task_pair, price) -> Pair(exchange_task_pair.first, price) }
    .group_by { (exchange, _) -> exchange }
    .map { prices -> prices.map { (_, price) -> price } }

  // Counting chain stats for every exchange
  return exchanges_prices
    .map { prices ->
      val errors_count = mutable_dict_of<String, Int>()
      val price_types_count = mutable_dict_of<String, Int>()
      var success_count = 0
      for (price in prices) when (price) {
        is Fail -> {
          var message = price.error.message ?: "Unknown error"
          // There are lots of `Timeout error after waiting for 20064ms` errors, removing the exact time
          message = message.replace(""" \d+ms""".toRegex(), " Xms")
          errors_count[message] = errors_count[message, 0] + 1
        }
        is Success -> {
          success_count += 1
          val sprice = price.result
          if (sprice.last_price != null)
            price_types_count["last_price"] = price_types_count["last_price", 0] + 1
          if (sprice.close_price != null)
            price_types_count["close_price"] = price_types_count["close_price", 0] + 1
          if (sprice.ask_price != null)
            price_types_count["ask_price"] = price_types_count["ask_price", 0] + 1
          if (sprice.bid_price != null)
            price_types_count["bid_price"] = price_types_count["bid_price", 0] + 1
          // val price_type = price.result.price_type
          // price_types_count[price_type] = price_types_count[price_type, 0] + 1
        }
      }

      StockOptionPricesInfo(
        total_count   = prices.size,
        success_count = success_count,
        errors_count  = dict_of(errors_count),
        price_types   = dict_of(price_types_count)
      )
    }
}