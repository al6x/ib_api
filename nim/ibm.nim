# How to run examples - uncomment code samples at the end of the page and run it.
#
# It depends on the `bon` library https://github.com/al6x/bon_nim download it and add to the nim
# paths in the `nim.cfg`.
#
#   nim c -r ibm.nim

import system except find
import basem, timem, httpm, jsonm, mathm, logm


# IB and Config ------------------------------------------------------------------------------------

let default_ib_api_port* = 8001
let default_timeout_sec* = 10 * 60

type IB = object
  base_url:    string
  timeout_sec: int

proc init_ib*(
  host        = "localhost",
  port        = default_ib_api_port,
  timeout_sec = default_timeout_sec
): Ib =
  IB(base_url: fmt"http://{host}:{port}", timeout_sec: timeout_sec)


# build_url ----------------------------------------------------------------------------------------
proc build_url(ib: Ib, path: string, query: tuple): string =
  build_url(fmt"{ib.base_url}{path}", query)

proc build_url(ib: Ib, path: string): string =
  build_url(fmt"{ib.base_url}{path}")


# log ----------------------------------------------------------------------------------------------
let log = Log.init("IB")

proc with_log[T](message: string, op: () -> T): T =
  log.info message
  let tic = timer_sec()
  result = op()
  log.with((duration: tic(),)).info(message & ", finished in {duration} sec")

proc with_log[T](data: tuple, message: string, op: () -> T): T =
  log.with(data).info message
  let tic = timer_sec()
  result = op()
  log.with(data).with((duration: tic(),)).info(message & ", finished in {duration} sec")


# get_stock_contract -------------------------------------------------------------------------------
type StockContract* = object
  symbol*:           string
  name*:             string
  exchange*:         string
  primary_exchange*: string
  currency*:         string
  id*:               int

proc get_stock_contract*(
  ib:        IB,
  symbol:    string,   # MSFT
  exchange:  string,   # SMART
  currency:  string,   # USD
): StockContract =
  with_log(
    (symbol: symbol, exchange: exchange, currency: currency),
    "get_stock_contract '{symbol} {exchange} {currency}'"
  ) do -> auto:
    let url = ib.build_url(
      "/api/v1/stock_contract",
      (exchange: exchange, symbol: symbol, currency: currency),
    )
    http_get[StockContract](url, ib.timeout_sec)


# get_stock_contracts ------------------------------------------------------------------------------
# Get all stock contracts on all exchanges
proc get_stock_contracts*(
  ib:        IB,
  symbol:    string # MSFT
): seq[StockContract] =
  with_log(
    ((symbol: symbol, )),
    "get_stock_contracts {symbol}"
  ) do -> auto:
    let url = ib.build_url("/api/v1/stock_contracts", (symbol: symbol, ))
    http_get[seq[StockContract]](url, ib.timeout_sec)


# get_stock_price ----------------------------------------------------------------------------------
type SnapshotPrice* = object
  last_price*:        Option[float]
  close_price*:       Option[float]
  ask_price*:         Option[float]
  bid_price*:         Option[float]
  approximate_price*: float
  data_type*:         string # IB code for market data type, realtime, delayed etc.

proc get_stock_price*(
  ib:         IB,
  symbol:     string,    # MSFT
  exchange:   string,    # SMART
  currency:   string,    # USD
  data_type = "realtime" # optional, realtime by default
): SnapshotPrice =
  with_log(
    (symbol: symbol, exchange: exchange, currency: currency, data_type: data_type),
    "get_stock_price '{symbol} {exchange} {currency}' {data_type}"
  ) do -> auto:
    let url = ib.build_url(
      "/api/v1/stock_price",
      (symbol: symbol, exchange: exchange, currency: currency, data_type: data_type)
    )
    http_get[SnapshotPrice](url, ib.timeout_sec)


# get_stock_option_chains --------------------------------------------------------------------------
type OptionChain* = object
  option_exchange*: string
  expirations_asc*: seq[string] # Sorted
  strikes_asc*:     seq[float]  # Sorted
  multiplier*:      int         # Multiplier 100 or 1000

type OptionChains* = object
  largest_desc*: seq[OptionChain]
  all*:          seq[OptionChain]

proc get_stock_option_chains*(
  ib:        IB,
  symbol:    string,   # MSFT
  exchange:  string,   # SMART
  currency:  string,   # USD
): OptionChains =
  with_log(
    (symbol: symbol, exchange: exchange, currency: currency),
    "get_stock_option_chains '{symbol} {exchange} {currency}'"
  ) do -> auto:
    let url = ib.build_url(
      "/api/v1/stock_option_chains",
      (symbol: symbol, exchange: exchange, currency: currency)
    )
    http_get[OptionChains](url, ib.timeout_sec)


# get_stock_option_chain_contracts -----------------------------------------------------------------
type OptionContract* = object
  id*:         int
  expiration*: TimeD  # 2020-08-21
  strike*:     float  # 120
  right*:      string # "put" or "call"

type OptionContracts* = object
  multiplier*:                               int                 # 100 or 1000
  contracts_asc_by_right_expiration_strike*: seq[OptionContract] # Sorted

proc get_stock_option_chain_contracts*(
  ib:              IB,
  symbol:          string,   # MSFT
  option_exchange: string,   # AMEX, differnt from the stock exchange
  currency:        string,   # USD
): OptionContracts =
  with_log(
    (symbol: symbol, option_exchange: option_exchange, currency: currency),
    "get_stock_option_chain_contracts '{symbol} {option_exchange} {currency}'"
  ) do -> auto:
    let url = ib.build_url(
      "/api/v1/stock_option_chain_contracts",
      (symbol: symbol, option_exchange: option_exchange, currency: currency),
    )
    http_get[OptionContracts](url, ib.timeout_sec)


# get_stock_option_chain_contracts_by_expiration ---------------------------------------------------
proc get_stock_option_chain_contracts_by_expirations*(
  ib:              IB,
  symbol:          string,      # MSFT
  expirations:     seq[string], # 2020-01-01
  option_exchange: string,      # AMEX, differnt from the stock exchange
  currency:        string,      # USD
): seq[OptionContract] =
  with_log(
    (symbol: symbol, option_exchange: option_exchange, currency: currency),
    "get_stock_option_chain_contracts_by_expirations '{symbol} {option_exchange} {currency}'"
  ) do -> auto:
    let requests = expirations.map((expiration) => %* [
      "/api/v1/stock_option_chain_contracts_by_expiration",
      (symbol: symbol, option_exchange: option_exchange, currency: currency, expiration: expiration),
    ])
    http_post_batch[JsonNode, seq[OptionContract]](
      ib.build_url("/api/v1/call"), requests, ib.timeout_sec
    )
      .map((e) => e.get)
      .flatten


# get_stock_options_prices -------------------------------------------------------------------------
# type OptionContractPrice* = object
#   last_price:         Option[float]
#   close_price:        Option[float]
#   ask_price:          Option[float]
#   bid_price:          Option[float]
#   approximate_price*: float
#   data_type*:         string # IB code for market data type, realtime, delayed etc.

type StockOptionParams* = tuple
  symbol:          string  # MSFT
  right:           string  # "put" or "call"'
  expiration:      string  # 2020-08-21
  strike:          float   # 120
  option_exchange: string  # AMEX, option exchange, different from the stock exchange
  currency:        string  # USD
  data_type:       string  # Market data type

proc get_stock_options_prices*(
  ib:         IB,
  contracts:  seq[StockOptionParams],
): seq[Fallible[SnapshotPrice]] =
  with_log(
    (count: contracts.len, ),
    "get_stock_options_prices for {count} contracts"
  ) do -> auto:
    let requests = contracts.map((c) => %* [
      "/api/v1/stock_option_price",
      (
        symbol: c.symbol, right: c.right, expiration: c.expiration, strike: c.strike,
        option_exchange: c.option_exchange, currency: c.currency, data_type: c.data_type
      )
    ])
    http_post_batch[JsonNode, SnapshotPrice](
      ib.build_url("/api/v1/call"), requests, ib.timeout_sec
    )


# get_stock_options_prices_by_ids ------------------------------------------------------------------
type IdAndExchange* = tuple
  id:              int    # Contract id 426933553
  option_exchange: string # AMEX, different from the stock exchange
  currency:        string # USD
  data_type:       string # Market data type

proc get_stock_options_prices_by_ids*(
  ib:         IB,
  contracts:  seq[IdAndExchange]
): seq[Fallible[SnapshotPrice]] =
  with_log(
    (count: contracts.len, ),
    "get_stock_options_prices_by_ids for {count} ids"
  ) do -> auto:
    let requests = contracts.map((c) => %* [
      "/api/v1/stock_option_price_by_id",
      (id: c.id, option_exchange: c.option_exchange, currency: c.currency, data_type: c.data_type)
    ])
    http_post_batch[JsonNode, SnapshotPrice](
      ib.build_url("/api/v1/call"), requests, ib.timeout_sec
    )


# get_stock_options_chain_prices -------------------------------------------------------------------
type StockOptionContractWithPrice* = object
  contract*: OptionContract
  price*:    Fallible[SnapshotPrice]

type OptionContractPrices* = object
  chain*:                                    OptionChain
  success_rate*:                             float
  contracts_asc_by_right_expiration_strike*: seq[StockOptionContractWithPrice] # Sorted

proc get_stock_option_chain_prices*(
  ib:                IB,
  symbol:            string,      # MSFT
  exchange:          string,      # Stock exchange NYSE
  option_exchange:   string,      # AMEX, different from the stock exchange
  currency:          string,      # USD
  data_type        = "realtime",  # optional, realtime by default
  min_success_rate = 0.6
): OptionContractPrices =
  let log2 = log.with((
    symbol: symbol, option_exchange: option_exchange, currency: currency, data_type: data_type
  ))
  let log_message = "get_stock_option_chain_prices '{symbol} {option_exchange} {currency}' {data_type}"
  log2.info log_message
  let tic = timer_sec()

  # Getting chain
  let chains = ib.get_stock_option_chains(
    symbol = symbol, exchange = exchange, currency = currency
  )
  let ochain = chains.largest_desc.find((chain) => chain.option_exchange == option_exchange)
  if not ochain.is_some: throw(fmt"chain for exhange {option_exchange} not found")
  let chain = ochain.get

  # Getting contracts
  var contracts: seq[OptionContract] = @[]
  let expiration_batches = chain.expirations_asc.batches(6)
  for i, batch in expiration_batches:
    log2
      .with((batch: i + 1, total: expiration_batches.len))
      .info(log_message & " getting contracts, batch {batch} of {total}")
    contracts.add ib.get_stock_option_chain_contracts_by_expirations(
      symbol, batch, option_exchange, currency
    )
  # Shuffling to distribute option contracts uniformly
  contracts = contracts.shuffle

  # Getting prices
  let ids = contracts.map((c) =>
    (id: c.id, option_exchange: option_exchange, currency: currency, data_type: data_type)
  )
  if ids.is_empty: throw("thre's no contracts")

  func success_rate(prices: seq[Fallible[SnapshotPrice]]): float =
    if prices.is_empty: 0.0
    else:               prices.count((price) => not price.is_error) / prices.len

  # Using batches to fail fast, if the success rate is low failing with the first batch
  # without trying the rest of the contracts
  let batches = ids.batches(400)
  var prices: seq[Fallible[SnapshotPrice]] = @[]
  for i, batch in batches:
    log2
      .with((batch: i + 1, total: batches.len))
      .info(log_message & " {batch} batch of {total}")
    let bprices = ib.get_stock_options_prices_by_ids(batch)
    let sr = success_rate(bprices)
    if sr < min_success_rate: throw(fmt"success rate is too low {sr.format(2)}")
    prices.add bprices

  # Preparing result
  result.success_rate = success_rate(prices)
  if result.success_rate < min_success_rate: throw(fmt"success rate is too low {result.success_rate}")
  result.chain = chain
  result.contracts_asc_by_right_expiration_strike = contracts
    .zip(prices)
    .map((pair) => StockOptionContractWithPrice(
      contract: pair[0],
      price:    pair[1]
    ))
    .sort_by((pair) => (pair.contract.right, pair.contract.expiration, pair.contract.strike))

  log2
    .with((duration: tic(), success_rate: (100 * result.success_rate).round.int))
    .info(log_message & "finished in {duration} sec, found {success_rate}% of contracts")


# get_portfolio ------------------------------------------------------------------------------------
type PortfolioStockContract* = object
  symbol*:   string
  exchange*: Option[string] # IB dosn't always provide it
  currency*: string
  id*:       int            # IB id for contract

type PortfolioOptionContract* = object
  symbol*:     string
  right*:      string  # "put" or "call"'
  expiration*: TimeD   # 2020-08-21
  strike*:     float   # 120
  exchange*:   Option[string] # IB dosn't always provide it
  currency*:   string
  id*:         int     # IB id for contract
  multiplier*: int     # Usually 100

type PortfolioPosition*[Contract] = object
  position*:     int
  average_cost*: float
  contract*:     Contract

type Portfolio* = object
  account_id*:    string
  stocks*:        seq[PortfolioPosition[PortfolioStockContract]]
  stock_options*: seq[PortfolioPosition[PortfolioOptionContract]]
  cash_in_usd*:   float

proc get_portfolio*(ib: IB): seq[Portfolio] =
  with_log(
    "get_portfolio"
  ) do -> auto:
    let url = ib.build_url("/api/v1/portfolio")
    http_get[seq[Portfolio]](url, ib.timeout_sec)


if is_main_module:
  let ib = init_ib()

  # US Testing -------------------------------------------------------------------------------------
  # p ib.get_stock_contract(symbol="MSFT", exchange="ISLAND", currency="USD").to_json

  # p ib.get_stock_contracts(symbol = "MSFT").to_json

  # p ib.get_stock_price(symbol="MSFT", exchange="ISLAND", currency="USD").to_json

  # p ib.get_stock_option_chains(symbol = "MSFT", exchange = "ISLAND", currency = "USD").to_json

  # p ib.get_stock_options_prices(@[
  #   ("MSFT", "call", "2022-06-17", 220.0, "AMEX", "USD", "delayed_frozen"),
  #   ("MSFT", "call", "2022-06-17", 220.0, "AMEX", "USD", "delayed_frozen")
  # ]).to_json

  # let chains = ib.get_stock_option_chains(symbol = "SPY", exchange = "NYSE", currency = "USD")
  # let chain = chains.search((chain) => chain.option_exchange == "AMEX").get
  # p ib.get_stock_option_chain_prices(
  #   symbol = "MSFT", currency = "USD", chain = chain, data_type = "delayed_frozen"
  # ).to_json

  # p ib.get_portfolio().to_json

  # ib.get_stock_option_chain_prices(
  #   symbol = "SPY", exchange = "NYSE", option_exchange = "AMEX", currency = "USD", data_type = "realtime"
  # )

  # p ib.get_stock_option_chain_prices(
  #   symbol = "MSFT", option_exchange = "AMEX", currency = "USD", data_type = "realtime"
  # ).to_json

  # European testint -------------------------------------------------------------------------------

  # p ib.get_stock_contract(symbol = "VAR1", exchange = "SMART", currency = "EUR")

  # p ib.get_stock_contracts(symbol = "VAR1").to_json

  # Using SMART instead of IBIS because there's no subscription
  # p ib.get_stock_price(symbol = "VAR1", exchange = "SMART", currency = "EUR")

  # p ib.get_stock_option_chains(symbol = "VAR1", exchange = "IBIS", currency = "EUR").to_json

  # p ib.get_stock_options_prices(@[
  #   ("VAR1", "call", "2021-12-17", 130.0, "DTB", "EUR", "delayed_frozen"),
  #   ("VAR1", "call", "2021-12-17", 135.0, "DTB", "EUR", "delayed_frozen")
  # ]).to_json

  # p ib.get_stock_option_chain_contracts(symbol = "VAR1", option_exchange = "DTB", currency = "EUR")
  # p ib.get_stock_option_chain_contracts_by_expirations(
  #   symbol = "VAR1", expirations = @["2022-12-16"], option_exchange = "DTB", currency = "EUR"
  # )

  # let chains = ib.get_stock_option_chains(symbol = "VAR1", exchange = "IBIS", currency = "EUR")
  # let chain = chains.search((chain) => chain.option_exchange == "DTB").get
  # p ib.get_stock_option_chain_prices(
  #   symbol = "VAR1", currency = "EUR", chain = chain, data_type = "delayed_frozen"
  # ).to_json

  # p ib.get_stock_options_prices_by_ids(@[
  #   (446264064, "DTB", "EUR", "realtime")
  # ])

  # p ib.get_stock_option_chain_prices(
  #   symbol = "VAR1", option_exchange = "DTB", currency = "EUR", data_type = "delayed_frozen"
  # ).to_json

  # p ib.get_stock_option_chain_prices(
  #   symbol = "VAR1", exchange = "IBIS", option_exchange = "DTB", currency = "EUR", data_type = "delayed_frozen"
  # ).to_json

  # Japan stocks, Panasonic

  # p ib.get_stock_option_chain_contracts(symbol = "6752", option_exchange = "OSE.JPN", currency = "JPY")
  # p ib.get_stock_options_prices(@[
  #   ("6752", "call", "2021-03-11", 1300.0, "SMART", "JPY", "delayed_frozen"),
  #   # ("VAR1", "call", "2021-12-17", 130.0, "DTB", "EUR"),
  #   # ("VAR1", "call", "2021-12-17", 135.0, "DTB", "EUR")
  # ]).to_json
  # p ib.get_stock_options_prices_by_ids(@[
  #   (416784710, "OSE.JPN", "JPY", "delayed_frozen")
  # ])
  # p ib.get_stock_options_prices_by_ids(@[
  #   (416784710, "OSE.JPN", "JPY", "delayed_frozen")
  # ])

  # p ib.get_stock_option_chain_prices(
  #   symbol = "CRM", exchange = "NYSE", option_exchange = "CBOE", currency = "USD", data_type = "delayed_frozen"
  # ).to_json

  # p ib.get_stock_options_prices_by_ids(@[
  #   (436952728, "OSE.JPN", "JPY", "delayed_frozen")
  # ])