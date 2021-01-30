package ib

import bon.*
import bon.http.*


// API to IB through TWS.
//
// For most REST API calls - parameters could be specified in query or JSON body.
//
// There's no way to distinguish stock from ETF contract, so "stock" type used for both.
// That's because IB uses "STK" type for both.
//
// For configs see `Config`, many config options could be overriden as shell variables.
//
abstract class IB {
  // inspect ---------------------------------------------------------------------------------------
  abstract fun inspect_stock(
    symbol:   String  // MSFT
  ): StockInspection

  private fun expose_inspect_stock(server: Server) =
    server.get("/api/v1/inspect_stock") { request ->
      this.inspect_stock(
        request.get_string("symbol")
      )
    }

  class StockInspection(
    val stocks: Map<Int, GroupedStocks> // id -> GroupedStocks
  )

  class GroupedStocks(
    val currency:                     String,
    val names:                        List<String>,
    val primary_exchange:             String,
    val exchanges:                    List<String>,
    //                                     exchange ->  price
    val stock_prices:                 Dict<String, ErrorneousS<SnapshotPrice>>,
    val largest_option_chains_desc:   Dict<String, OptionChain>,
    val option_chain_contracts_count: Dict<String, ErrorneousS<Int>>,
    val option_chain_prices:          Dict<String, StockOptionPricesInfo>
  )

  class StockOptionPricesInfo(
    val total_count:   Int,
    val success_count: Int,
    val errors_count:  Dict<String, Int>,
    val price_types:   Dict<String, Int>
  )


  // get_portfolio ---------------------------------------------------------------------------------
  abstract fun get_portfolio(): List<Portfolio>

  private fun expose_get_portfolio(server: Server) =
    server.get("/api/v1/portfolio") { request ->
      this.get_portfolio()
    }

  class Portfolio(
    val account_id:    String,
    val stocks:        List<PortfolioPosition<PortfolioStockContract>>,
    val stock_options: List<PortfolioPosition<PortfolioOptionContract>>,
    val cash_in_usd:   Double
  )

  class PortfolioPosition<Contract> (
    val position:     Int,
    val average_cost: Double,  // IB somehow approximately calculates it, could be
                               // quite different from the actual average cost.
    val contract:     Contract
  )

  class PortfolioStockContract (
    val symbol:   String,
    val exchange: String?, // IB dosn't always provide it
    val currency: String,
    val id:       Int      // IB id for contract
  )

  class PortfolioOptionContract(
    val symbol:     String,
    val right:      String,  // "put" or "call"'
    val expiration: String,  // 2020-08-21
    val strike:     Double,  // 120
    val exchange:   String?, // IB dosn't always provide it
    val currency:   String,
    val id:         Int,     // IB id for contract
    val multiplier: Int      // Usually 100
  )


  // get_stock_contract ----------------------------------------------------------------------------
  abstract fun get_stock_contract(
    symbol:   String,  // MSFT
    exchange: String,  // SMART
    currency: String   // USD
  ): StockContract

  private fun expose_get_stock_contract(server: Server) =
    server.get("/api/v1/stock_contract") { request ->
      this.get_stock_contract(
        request.get_string("symbol"),
        request.get_string("exchange"),
        request.get_string("currency")
      )
    }

  class StockContract(
    val symbol:           String,
    val name:             String,
    val exchange:         String,
    val primary_exchange: String,
    val currency:         String,
    val id:               Int
  ) {
    val type = ContractType.stock
  }


  // get_stock_contracts ---------------------------------------------------------------------------
  // Get all stock contracts on all exchanges
  abstract fun get_stock_contracts(
    symbol:   String  // MSFT
  ): List<StockContract>

  private fun expose_get_stock_contracts(server: Server) =
    server.get("/api/v1/stock_contracts") { request ->
      this.get_stock_contracts(
        request.get_string("symbol")
      )
    }


  // get_stock_price -------------------------------------------------------------------------------
  abstract fun get_stock_price(
    symbol:    String,         // MSFT
    exchange:  String,         // SMART
    currency:  String,         // USD
    data_type: MarketDataType? // optional, realtime by default
  ): SnapshotPrice

  private fun expose_get_stock_price(server: Server) =
    server.get("/api/v1/stock_price") { request ->
      this.get_stock_price(
        request.get_string("symbol"),
        request.get_string("exchange"),
        request.get_string("currency"),
        request.get_data_type_optional("data_type")
      )
    }

  class SnapshotPrice(
    val last_price:        Double?,      // Some prices could be unavailable
    val close_price:       Double?,
    val ask_price:         Double?,
    val bid_price:         Double?,

    val approximate_price: Double,        // Always available, see `approximate_price` function.

    val data_type:         MarketDataType // IB code for market data type, realtime, delayed etc.
  )

  // Not all prices always available in TWS API, calculating the best guess for the price
  fun approximate_price(
    last_price: Double?, close_price: Double?, ask_price: Double?, bid_price: Double?
  ): Double? = when {
    bid_price != null && ask_price != null -> (ask_price + bid_price) / 2
    last_price != null                     -> last_price
    close_price != null                    -> close_price
    else                                   -> null
  }


  // get_stock_price -------------------------------------------------------------------------------
  abstract fun get_stock_price_by_id(
    id:        Int,            // IB contract id
    exchange:  String,         // SMART, exchange used just to be sure the proper contract will be found
    currency:  String,         // USD, currency used just to be sure the proper contract will be found
    data_type: MarketDataType? // optional, realtime by default
  ): SnapshotPrice

  private fun expose_get_stock_price_by_id(server: Server) =
    server.get("/api/v1/stock_price_by_id") { request ->
      this.get_stock_price_by_id(
        request.get_integer("id"),
        request.get_string("exchange"),
        request.get_string("currency"),
        request.get_data_type_optional("data_type")
      )
    }


  // get_stock_option_chains -----------------------------------------------------------------------
  abstract fun get_stock_option_chains(
    symbol:   String, // IB contract id
    exchange: String, // SMART
    currency: String  // USD
  ): OptionChains

  private fun expose_get_stock_option_chains(server: Server) =
    server.get("/api/v1/stock_option_chains") { request ->
      this.get_stock_option_chains(
        request.get_string("symbol"),
        request.get_string("exchange"),
        request.get_string("currency")
      )
    }

  class OptionChains(
    val largest_desc: List<OptionChain>, // Some exchanges contain multiple option chains, in such case for
                                         // every exchange the largest chain is selected.
                                         // Largest by size of expirations and strikes.
                                         // Example AIR:DTB:EUR
    val all:          List<OptionChain>  // Full list of chains.
  )

  class OptionChain(
    val option_exchange: String,       // Could be different from stock exchange
    val expirations_asc: List<String>, // Sorted
    val strikes_asc:     List<Double>, // Sorted
    val multiplier:      Int           // Usually 100
  )


  // get_stock_option_chain --------------------------------------------------------------------
  abstract fun get_stock_option_chain_contracts(
    symbol:          String, // MSFT
    option_exchange: String, // AMEX, different from the stock exchange
    currency:        String  // USD
  ): OptionContracts

  private fun expose_stock_option_chain_contracts(server: Server) =
    server.get("/api/v1/stock_option_chain_contracts") { request ->
      this.get_stock_option_chain_contracts(
        request.get_string("symbol"),
        request.get_string("option_exchange"),
        request.get_string("currency")
      )
    }

  class OptionContracts(
    val multiplier: Int,                                                // Usually 100
    val contracts_asc_by_right_expiration_strike:  List<OptionContract>
  )

  class OptionContract(
    val id:         Int,
    val expiration: String, // 2020-08-21
    val strike:     Double, // 120
    val right:      String  // "put" or "call"
  )


  // get_stock_option_chain --------------------------------------------------------------------
  abstract fun get_stock_option_chain_contracts_by_expiration(
    symbol:          String, // MSFT
    expiration:      String, // 2020-01-01
    option_exchange: String, // AMEX, different from the stock exchange
    currency:        String  // USD
  ): List<OptionContract>

  private fun expose_get_stock_option_chain_contracts_by_expiration(server: Server) =
    server.get("/api/v1/stock_option_chain_contracts_by_expiration") { request ->
      this.get_stock_option_chain_contracts_by_expiration(
        request.get_string("symbol"),
        request.get_string("expiration"),
        request.get_string("option_exchange"),
        request.get_string("currency")
      )
    }


  // get_stock_option_price ------------------------------------------------------------------------
  abstract fun get_stock_option_price(
    symbol:          String,         // MSFT
    right:           String,         // "put" or "call"'
    expiration:      String,         // 2020-08-21
    strike:          Double,         // 120
    option_exchange: String,         // AMEX, different from the stock exchange
    currency:        String,         // USD
    data_type:       MarketDataType? // optional, realtime by default
  ): SnapshotPrice

  private fun expose_get_stock_option_price(server: Server) =
    server.get("/api/v1/stock_option_price") { request ->
      this.get_stock_option_price(
        request.get_string("symbol"),
        request.get_string("right"),
        request.get_string("expiration"),
        request.get_double("strike"),
        request.get_string("option_exchange"),
        request.get_string("currency"),
        request.get_data_type_optional("data_type")
      )
    }

  // get_stock_option_price_by_id ------------------------------------------------------------
  // When searching by id - the currency is ignored by IB
  abstract fun get_stock_option_price_by_id(
    id:              Int,            // Contract id 426933553
    option_exchange: String,         // AMEX, different from the stock exchange
    currency:        String,         // USD
    data_type:       MarketDataType? // optional, realtime by default
  ): SnapshotPrice

  private fun expose_get_stock_option_price_by_id(server: Server) =
    server.get("/api/v1/stock_option_price_by_id") { request ->
      this.get_stock_option_price_by_id(
        request.get_integer("id"),
        request.get_string("option_exchange"),
        request.get_string("currency"),
        request.get_data_type_optional("data_type")
      )
    }


  // Minor types -----------------------------------------------------------------------------------
  enum class ContractType { stock, option }

  enum class MarketDataType(val code: Int) {
    realtime       (com.ib.client.MarketDataType.REALTIME),
    frozen         (com.ib.client.MarketDataType.FROZEN),
    delayed        (com.ib.client.MarketDataType.DELAYED),
    delayed_frozen (com.ib.client.MarketDataType.DELAYED_FROZEN);

    companion object {
      fun value_of(code: Int): MarketDataType =
        values().find { it.code == code } ?: throw Exception("Unknown MarketDataType '$code'")

      fun value_of(name: String): MarketDataType =
        values().find { it.to_string() == name } ?: throw Exception("Unknown MarketDataType '$name'")
    }
  }

  // REST API --------------------------------------------------------------------------------------
  fun expose_on(server: Server) {
    expose_inspect_stock(server)

    expose_get_portfolio(server)

    expose_get_stock_contract(server)
    expose_get_stock_contracts(server)

    expose_get_stock_price(server)
    expose_get_stock_price_by_id(server)

    expose_get_stock_option_chains(server)
    expose_stock_option_chain_contracts(server)
    expose_get_stock_option_chain_contracts_by_expiration(server)

    expose_get_stock_option_price(server)
    expose_get_stock_option_price_by_id(server)

    server.add_call_alias("/api/v1/call")
  }
}

// Helpers -----------------------------------------------------------------------------------------

fun Request.get_data_type(key: String) = IB.MarketDataType.value_of(this.get_string(key))
fun Request.get_data_type_optional(key: String) = this.get_string_optional(key) {
  IB.MarketDataType.value_of(it)
}