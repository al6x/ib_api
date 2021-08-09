// Run examples
//
//   deno run --allow-net --unstable deno/ib.ts

import {
  build_url, timer_sec, get_data, http_post, parse_data, HttpRawOptions
} from 'https://raw.githubusercontent.com/al6x/deno/main/base/base.ts'
import { Log } from 'https://raw.githubusercontent.com/al6x/deno/main/base/log.ts'

const log = new Log('IB')


export type Right = 'put' | 'call'
export type MarketDataType = 'realtime' | 'frozen' | 'delayed' | 'delayed_frozen'


export interface StockContract {
  symbol:           string
  name:             string
  exchange:         string
  primary_exchange: string
  currency:         string
  id:               number
}


export interface SnapshotPrice {
  last_price?:       number
  close_price?:      number
  ask_price?:        number
  bid_price?:        number
  approximate_price: number
  data_type:         MarketDataType
}


export interface OptionChain {
  option_exchange: string
  expirations_asc: string[] // Sorted
  strikes_asc:     number[] // Sorted
  multiplier:      number   // Multiplier 100 or 1000
}


export interface OptionChains {
  largest_desc: OptionChain[]
  // Different exchanges could have different stock option chains, with different amount of contracts,
  // sorting desc by contract amount.
  all:          OptionChain[]
}


export interface OptionContract {
  right:      Right
  expiration: string  // 2020-08-21
  strike:     number  // 120
}

export interface OptionContractWithId {
  right:      Right
  id:         number
  expiration: string  // 2020-08-21
  strike:     number // 120
}

export interface OptionContracts {
  multiplier:                               number                 // 100 or 1000
  contracts_asc_by_right_expiration_strike: OptionContractWithId[] // Sorted
}


export interface StockOptionParams {
  symbol:          string  // MSFT
  right:           Right
  expiration:      string  // 2020-08-21
  strike:          number  // 120
  option_exchange: string  // AMEX, option exchange, different from the stock exchange
  currency:        string  // USD
  data_type:       MarketDataType
}


export interface IdAndExchange {
  id:              number // Contract id 426933553
  option_exchange: string // AMEX, different from the stock exchange
  currency:        string // USD
  data_type:       MarketDataType
}


export interface PortfolioStockContract {
  symbol:    string
  exchange?: string // IB dosn't always provide it
  currency:  string
  id:        number // IB id for contract
}

export interface PortfolioOptionContract {
  symbol:     string
  right:      Right
  expiration: string // 2020-08-21
  strike:     number // 120
  exchange?:  string // IB dosn't always provide it
  currency:   string
  id:         number // IB id for contract
  multiplier: number // Usually 100
}

export interface PortfolioPosition<Contract> {
  position:     number
  average_cost: number
  contract:     Contract
}

export interface Portfolio {
  account_id:    string
  stocks:        PortfolioPosition<PortfolioStockContract>[]
  stock_options: PortfolioPosition<PortfolioOptionContract>[]
  cash_in_usd:   number
}


export class IB {
  constructor(
    public readonly base_url   = `http://localhost:8001`,
    public readonly timeout_ms = 10 * 60 * 1000
  ){}


  get_stock_contract(
    symbol:   string,   // MSFT
    exchange: string,   // SMART
    currency: string,   // USD
  ): Promise<StockContract> {
    const query  = { exchange, symbol, currency }
    const result = this.get_data<StockContract>('/api/v1/stock_contract', query)
    return this.with_log("get_stock_contract '{symbol} {exchange} {currency}'", query, result)
  }


  // Get all stock contracts on all exchanges
  get_stock_contracts(
    symbol: string // MSFT
  ): Promise<StockContract[]> {
    const query  = { symbol: symbol }
    const result = this.get_data<StockContract[]>('/api/v1/stock_contracts', query)
    return this.with_log('get_stock_contracts {symbol}', query, result)
  }


  get_stock_price(
    symbol:    string,    // MSFT
    exchange:  string,    // SMART
    currency:  string,    // USD
    data_type: MarketDataType = 'realtime'
  ): Promise<SnapshotPrice> {
    const query  = { symbol, exchange, currency, data_type }
    const result = this.get_data<SnapshotPrice>('/api/v1/stock_price', query)
    return this.with_log("get_stock_price '{symbol} {exchange} {currency}' {data_type}", query, result)
  }


  get_stock_option_chains(
    symbol:    string, // MSFT
    exchange:  string, // SMART
    currency:  string, // USD
  ): Promise<OptionChains> {
    const query  = { symbol, exchange, currency }
    const result = this.get_data<OptionChains>('/api/v1/stock_option_chains', query)
    return this.with_log("get_stock_option_chains '{symbol} {exchange} {currency}'", query, result)
  }


  async get_stock_option_chain(
    symbol:          string, // MSFT
    exchange:        string, // SMART
    option_exchange: string, // AMEX, differnt from the stock exchange
    currency:        string, // USD
  ): Promise<OptionChain> {
    const chains = await this.get_stock_option_chains(symbol, exchange, currency)
    const ochain = chains.largest_desc.find((chain) => chain.option_exchange == option_exchange)
    if (!ochain) throw `chain for exhange ${option_exchange} not found`
    return ochain
  }


  get_stock_option_chain_contracts(
    symbol:          string,   // MSFT
    option_exchange: string,   // AMEX, differnt from the stock exchange
    currency:        string,   // USD
  ): Promise<OptionContracts> {
    const query = { symbol, option_exchange, currency }
    const result = this.get_data<OptionContracts>('/api/v1/stock_option_chain_contracts', query)
    return this.with_log("get_stock_option_chain_contracts '{symbol} {option_exchange} {currency}'", query, result)
  }


  get_stock_option_chain_contracts_by_expirations(
    symbol:          string,   // MSFT
    expirations:     string[], // 2020-01-01
    option_exchange: string,   // AMEX, differnt from the stock exchange
    currency:        string,   // USD
  ): Promise<OptionContractWithId[]> {
    const requests = expirations.map((expiration) => ({
      path: "/api/v1/stock_option_chain_contracts_by_expiration",
      body: { symbol, option_exchange, currency, expiration }
    }))

    const result = this.post_batch<
      { symbol: string, option_exchange: string, currency: string, expiration: string },
      OptionContractWithId
    >('/api/v1/call', requests)
      .then((contracts) => contracts.map((c) => ensure(c)))

    return this.with_log(
      "get_stock_option_chain_contracts_by_expirations '{symbol} {option_exchange} {currency}'",
      { symbol, option_exchange, currency },
      result
    )
  }


  get_stock_options_prices(
    contracts: StockOptionParams[]
  ): Promise<E<SnapshotPrice>[]> {
    const requests = contracts.map((c) => ({ path: "/api/v1/stock_option_price", body: c }))
    const result = this.post_batch<StockOptionParams, SnapshotPrice>('/api/v1/call', requests)
    return this.with_log("get_stock_options_prices for {count} contracts", { count: contracts.length }, result)
  }


  get_stock_options_prices_by_ids(
    contracts: IdAndExchange[]
  ): Promise<E<SnapshotPrice>[]> {
    const requests = contracts.map((c) => ({ path: '/api/v1/stock_option_price_by_id', body: c }))
    const result = this.post_batch<IdAndExchange, SnapshotPrice>('/api/v1/call', requests)
    return this.with_log('get_stock_options_prices_by_ids for {count} ids', { count: contracts.length }, result)
  }


  get_portfolio(): Promise<Portfolio[]> {
    const result = this.get_data<Portfolio[]>('/api/v1/portfolio')
    return this.with_log('get_portfolio', {}, result)
  }


  // -----------------------------------------------------------------------------------------------
  // Helpers ---------------------------------------------------------------------------------------
  // -----------------------------------------------------------------------------------------------
  protected get_data<T>(path: string, query: { [key: string]: string | number | boolean } = {}): Promise<T> {
    return get_data(build_url(this.base_url + path, query), { timeout_ms: this.timeout_ms })
  }

  protected async post_batch<Req, Res>(url: string, requests: { path: string, body: Req }[]): Promise<E<Res>[]> {
    return await post_batch<Req, Res>(build_url(this.base_url + url), requests, { timeout_ms: this.timeout_ms })
  }

  protected async with_log<T>(message: string, data: { [key: string]: any }, result: Promise<T>): Promise<T> {
    let l = log.with(data)
    l.info(message)
    let tic = timer_sec()
    await result
    l.with({ message, duration: tic() }).info(`${message}, finished in {duration} sec`)
    return result
  }
}


export async function post_batch<Req, Res>(
  url:      string,
  requests: { path: string, body: Req }[],
  options?: HttpRawOptions
): Promise<E<Res>[]> {
  const headers = { "Content-Type": "application/json" }
  const json = await http_post(url, to_json(requests), { headers, ...(options || {}) })
  const data = JSON.parse(json)
  if (is_object(data) && 'is_error' in data) {
    const message = data.message || 'Unknown error'
    return requests.map((_r) => ({ is_error: true, message }))
  } else if (is_array(data)) {
    if (data.length != requests.length) throw new Error(`invalid length of response array`)
    return data.map((data) => parse_data<Res>(data))
  } else {
    throw new Error(`wrong response format for post_batch, should be an array`)
  }
}


// -------------------------------------------------------------------------------------------------
// Test --------------------------------------------------------------------------------------------
// -------------------------------------------------------------------------------------------------
if (import.meta.main) {
  const ib = new IB()

  // US stocks -------------------------------------------------------------------------------------
  p(await ib.get_stock_contract('MSFT', 'ISLAND', 'USD'))

  p(await ib.get_stock_contracts('MSFT'))

  p(await ib.get_stock_price('MSFT', 'ISLAND', 'USD'))

  p(await ib.get_stock_option_chains('MSFT', 'ISLAND', 'USD'))

  p(await ib.get_stock_options_prices([
    {
      symbol: 'MSFT', right: 'call', expiration: '2022-06-17', strike: 220,
      option_exchange: 'CBOE', currency: 'USD', data_type: 'delayed_frozen'
    },
    {
      symbol: 'MSFT', right: 'call', expiration: '2022-06-17', strike: 225,
      option_exchange: 'CBOE', currency: 'USD', data_type: 'delayed_frozen'
    }
  ]))
}