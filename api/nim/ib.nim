# How to run examples - uncomment code samples at the end of the page and run it.
#
# It depends on the library https://github.com/al6x/nim download it and add to the nim
# paths in the `nim.cfg`.
#
#   nim c -r ibm.nim

import base, base/http_client


# IB and Config ------------------------------------------------------------------------------------
type IB* = object
  base_url*:    string
  timeout_sec*: int

proc init*(
  _:            type[IB],
  host        = "localhost",
  port        = 8001,
  timeout_sec = 10 * 60
): IB =
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
    get_data[StockContract](url, ib.timeout_sec)


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
    get_data[seq[StockContract]](url, ib.timeout_sec)


# get_stock_price ----------------------------------------------------------------------------------
type SnapshotPrice* = object
  last_price*:        Option[float]
  close_price*:       Option[float]
  ask_price*:         Option[float]
  bid_price*:         Option[float]
  approximate_price*: float
  data_type*:         string # IB code for market data type, realtime, delayed etc.

type SEC* = tuple
  symbol:   string    # MSFT
  exchange: string    # SMART
  currency: string    # USD

type StockPriceBody = tuple
  symbol:    string
  exchange:  string
  currency:  string
  data_type: string

proc batch_get_stock_price*(
  ib:         IB,
  stocks:     seq[SEC],
  data_type = "realtime" # optional, realtime by default
): seq[Fallible[SnapshotPrice]] =
  with_log(
    (count: stocks.len, data_type: data_type),
    "get_stock_prices for {count} stocks, {data_type}"
  ) do -> auto:
    let requests = stocks.map((stock) => (
      path: "/api/v1/stock_price",
      body: (symbol: stock.symbol, exchange: stock.exchange, currency: stock.currency, data_type: data_type)
    ))
    post_batch[StockPriceBody, SnapshotPrice](
      ib.build_url("/api/v1/call"), requests, ib.timeout_sec
    )


# get_stock_option_chains --------------------------------------------------------------------------
type OptionChain* = object
  option_exchange*: string
  expirations_asc*: seq[string] # Sorted
  strikes_asc*:     seq[float]  # Sorted
  multiplier*:      int         # Multiplier 100 or 1000

type OptionChains* = object
  largest_desc*: seq[OptionChain]
  # Different exchanges could have different stock option chains, with different amount of contracts,
  # sorting desc by contract amount.
  all*:          seq[OptionChain]

type SECO* = tuple
  symbol:          string # MSFT
  exchange:        string # SMART
  currency:        string # USD
  option_exchange: string # CBOE

proc batch_get_stock_options_chains*(
  ib:         IB,
  contracts:  seq[SEC]
): seq[Fallible[OptionChains]] =
  with_log(
    (count: contracts.len, ),
    "get_stock_option_chains for {count}"
  ) do -> auto:
    let requests = contracts.map((sec) => (
      path: "/api/v1/stock_option_chains",
      body: sec
    ))
    post_batch[SEC, OptionChains](
      ib.build_url("/api/v1/call"), requests, ib.timeout_sec
    )

proc batch_get_stock_options_chain*(
  ib:         IB,
  contracts:  seq[SECO]
): seq[Fallible[OptionChain]] =
  let contracts_sec = contracts.map((c) => (symbol: c.symbol, exchange: c.exchange, currency: c.currency))
  let batch = ib.batch_get_stock_options_chains(contracts_sec)
  for (contract, chains) in contracts.zip(batch):
    result.add:
      if chains.is_some:
        let c = contract
        let ochain = chains.get.largest_desc.fget((chain) => chain.option_exchange == c.option_exchange)
        if ochain.is_some: ochain.success
        else:              OptionChain.error fmt"chain for exhange {contract.option_exchange} not found"
      else:
        OptionChain.error chains.message

proc get_stock_option_chains*(
  ib:        IB,
  symbol:    string,   # MSFT
  exchange:  string,   # SMART
  currency:  string,   # USD
): OptionChains =
  let contract = (symbol: symbol, exchange: exchange, currency: currency)
  ib.batch_get_stock_options_chains(@[contract]).first.get

proc get_stock_option_chain*(
  ib:              IB,
  symbol:          string,   # MSFT
  exchange:        string,   # SMART
  option_exchange: string,   # AMEX, differnt from the stock exchange
  currency:        string,   # USD
): OptionChain =
  let contract = (symbol: symbol, exchange: exchange, currency: currency, option_exchange: option_exchange)
  ib.batch_get_stock_options_chain(@[contract]).first.get


# get_stock_option_chain_contracts -----------------------------------------------------------------
type OptionContract* = object
  expiration*: TimeD  # 2020-08-21
  strike*:     float  # 120
  right*:      string # "put" or "call"

type OptionContractWithId* = object
  id*:         int
  expiration*: TimeD  # 2020-08-21
  strike*:     float  # 120
  right*:      string # "put" or "call"

type OptionContracts* = object
  multiplier*:                               int                       # 100 or 1000
  contracts_asc_by_right_expiration_strike*: seq[OptionContractWithId] # Sorted

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
    get_data[OptionContracts](url, ib.timeout_sec)


# get_stock_option_chain_contracts_by_expiration ---------------------------------------------------
type ContractsByExpirationBody = tuple[
  symbol: string, option_exchange: string, currency: string, expiration: string]

proc batch_get_stock_option_chain_contracts_by_expirations*(
  ib:              IB,
  symbol:          string,      # MSFT
  expirations:     seq[string], # 2020-01-01
  option_exchange: string,      # AMEX, differnt from the stock exchange
  currency:        string,      # USD
): seq[OptionContractWithId] =
  with_log(
    (symbol: symbol, option_exchange: option_exchange, currency: currency),
    "get_stock_option_chain_contracts_by_expirations '{symbol} {option_exchange} {currency}'"
  ) do -> auto:
    let requests = expirations.map((expiration) => (
      path: "/api/v1/stock_option_chain_contracts_by_expiration",
      body: (symbol: symbol, option_exchange: option_exchange, currency: currency, expiration: expiration)
    ))
    post_batch[ContractsByExpirationBody, seq[OptionContractWithId]](
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

type StockOptionRequestBody = tuple[
  symbol: string, right: string, expiration: string, strike: float,
  option_exchange: string, currency: string, data_type: string
]

proc batch_get_stock_options_prices*(
  ib:         IB,
  contracts:  seq[StockOptionParams],
): seq[Fallible[SnapshotPrice]] =
  with_log(
    (count: contracts.len, ),
    "get_stock_options_prices for {count} contracts"
  ) do -> auto:
    let requests = contracts.map((c) => (
      path: "/api/v1/stock_option_price",
      body: (
        symbol: c.symbol, right: c.right, expiration: c.expiration, strike: c.strike,
        option_exchange: c.option_exchange, currency: c.currency, data_type: c.data_type
      )
    ))
    post_batch[StockOptionRequestBody, SnapshotPrice](
      ib.build_url("/api/v1/call"), requests, ib.timeout_sec
    )


# get_stock_options_prices_by_ids ------------------------------------------------------------------
type IdAndExchange* = tuple
  id:              int    # Contract id 426933553
  option_exchange: string # AMEX, different from the stock exchange
  currency:        string # USD
  data_type:       string # Market data type

type OptionPriceByIdRequestBody = tuple[id: int, option_exchange: string, currency: string, data_type: string]

proc batch_get_stock_options_prices_by_ids*(
  ib:         IB,
  contracts:  seq[IdAndExchange]
): seq[Fallible[SnapshotPrice]] =
  with_log(
    (count: contracts.len, ),
    "get_stock_options_prices_by_ids for {count} ids"
  ) do -> auto:
    let requests = contracts.map((c) => (
      path: "/api/v1/stock_option_price_by_id",
      body: (id: c.id, option_exchange: c.option_exchange, currency: c.currency, data_type: c.data_type)
    ))
    post_batch[OptionPriceByIdRequestBody, SnapshotPrice](
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
  let chain = ib.get_stock_option_chain(
    symbol = symbol, exchange = exchange, option_exchange = option_exchange, currency = currency)

  # Getting contracts
  var contracts: seq[OptionContractWithId] = @[]
  let expiration_batches = chain.expirations_asc.batches(6)
  for i, batch in expiration_batches:
    log2
      .with((batch: i + 1, total: expiration_batches.len))
      .info(log_message & " getting contracts, batch {batch} of {total}")
    contracts.add ib.batch_get_stock_option_chain_contracts_by_expirations(
      symbol, batch, option_exchange, currency
    )
  # Shuffling to distribute option contracts uniformly
  contracts = contracts.shuffle

  # Getting prices
  let ids = contracts.map((c) =>
    (id: c.id, option_exchange: option_exchange, currency: currency, data_type: data_type)
  )
  if ids.is_empty: throw("there's no contracts")

  let success_rate = proc (prices: seq[Fallible[SnapshotPrice]]): float =
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
    let bprices = ib.batch_get_stock_options_prices_by_ids(batch)
    let sr = success_rate(bprices)
    if sr < min_success_rate: throw(fmt"success rate is too low {sr.format(2)}")
    prices.add bprices

  # Preparing result
  result.success_rate = success_rate(prices)
  if result.success_rate < min_success_rate: throw fmt"success rate is too low {result.success_rate}"
  result.chain = chain
  result.contracts_asc_by_right_expiration_strike = contracts
    .map((c) => OptionContract(expiration: c.expiration, strike: c.strike, right: c.right))
    .zip(prices)
    .map((pair) => StockOptionContractWithPrice(contract: pair[0], price: pair[1]))
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
    get_data[seq[Portfolio]](url, ib.timeout_sec)


if is_main_module:
  let ib = IB.init()

  # US Testing -------------------------------------------------------------------------------------
  # p ib.get_stock_contract(symbol="MSFT", exchange="ISLAND", currency="USD").to_json

  # p ib.get_stock_contracts(symbol = "MSFT").to_json

  # p ib.get_stock_prices(@[
  #   (symbol: "MSFT", exchange: "ISLAND", currency: "USD")
  # ]).to_json

  # p ib.get_stock_price(symbol="FNV", exchange="TSE", currency="CAD", data_type = "delayed_frozen").to_json

  # p ib.get_stock_option_chains(symbol = "MSFT", exchange = "ISLAND", currency = "USD").to_json

  # p ib.batch_get_stock_options_prices(@[
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

  # p ib.batch_get_stock_option_chain_contracts_by_expirations(
  #   "DAI", @["2021-11-19"], "DTB", "EUR"
  # ).to_json.to_s

  # p ib.batch_get_stock_options_prices_by_ids(@[
  #   (id: 510217398, option_exchange: "DTB", currency: "EUR", data_type: "delayed_frozen")
  # ])

  # p ib.batch_get_stock_options_prices(@[
  #   ("DAI", "call", "2021-11-19", 70.0, "DTB", "EUR", "delayed_frozen"),
  # ]).to_json

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