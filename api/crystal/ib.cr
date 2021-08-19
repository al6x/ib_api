require "json"
require "http"

class IB
  alias Float = Float64; alias Int = Int32

  enum Right; Put; Call end
  enum MarketDataType; Realtime; Frozen; Delayed; DelayedFrozen end


  def initialize(@base_url = "http://localhost:8001"); end


  def stock_contract(
    symbol :   String, # MSFT
    exchange : String, # SMART
    currency : String, # USD
  ) : StockContract
    StockContract.from_json http_get "/api/v1/stock_contract",
      { symbol: symbol, exchange: exchange, currency: currency }
  end

  record StockContract,
    symbol :           String,
    name :             String,
    exchange :         String,
    primary_exchange : String,
    currency :         String,
    id :               Int,
  do
    include JSON::Serializable
  end


  # Get all stock contracts on all exchanges
  def stock_contracts(
    symbol : String # MSFT
  ) : Array(StockContract)
    Array(StockContract).from_json http_get "/api/v1/stock_contracts", { symbol: symbol }
  end


  def stock_price(
    symbol :    String, # MSFT
    exchange :  String, # SMART
    currency :  String, # USD
    data_type : MarketDataType = :realtime
  ) : SnapshotPrice
    SnapshotPrice.from_json http_get "/api/v1/stock_price",
      { symbol: symbol, exchange: exchange, currency: currency, data_type: data_type.to_s.underscore }
  end

  record SnapshotPrice,
    last_price :        Float?,
    close_price :       Float?,
    ask_price :         Float?,
    bid_price :         Float?,
    approximate_price : Float,
    data_type :         MarketDataType,
  do
    include JSON::Serializable
  end


  def stock_option_chains(
    symbol :   String, # MSFT
    exchange : String, # SMART
    currency : String, # USD
  ) : OptionChains
    OptionChains.from_json http_get "/api/v1/stock_option_chains",
      { symbol: symbol, exchange: exchange, currency: currency }
  end

  record OptionChain,
    option_exchange : String,
    expirations_asc : Array(String), # Sorted
    strikes_asc :     Array(Float),  # Sorted
    multiplier :      Int,           # Multiplier 100 or 1000
  do
    include JSON::Serializable
  end

  record OptionChains,
    largest_desc : Array(OptionChain),
    # Different exchanges could have different stock option chains, with different amount of contracts,
    # sorting desc by contract amount.
    all :          Array(OptionChain),
  do
    include JSON::Serializable
  end


  def stock_option_chain(
    symbol :          String, # MSFT
    exchange :        String, # SMART
    option_exchange : String, # AMEX, differnt from the stock exchange
    currency :        String, # USD
  ) : OptionChain
    chains = stock_option_chains(symbol, exchange, currency)
    ochain = chains.largest_desc.find { |chain| chain.option_exchange == option_exchange }
    raise "chain for exhange #{option_exchange} not found" if ochain.nil?
    ochain
  end


  def stock_option_chain_contracts(
    symbol :          String, # MSFT
    option_exchange : String, # AMEX, differnt from the stock exchange
    currency :        String, # USD
  ): OptionContracts
    OptionContracts.from_json http_get "/api/v1/stock_option_chain_contracts",
      { symbol: symbol, option_exchange: option_exchange, currency: currency }
  end

  record OptionContract,
    right :      Right,
    expiration : String, # 2020-08-21
    strike :     Float,  # 120
  do
    include JSON::Serializable
  end

  record OptionContractWithId,
    id :         Int,
    expiration : String, # 2020-08-21
    strike :     Float,  # 120
    right :      Right,
  do
    include JSON::Serializable
  end

  record OptionContracts,
    multiplier :                               Int,                         # 100 or 1000
    contracts_asc_by_right_expiration_strike : Array(OptionContractWithId), # Sorted
  do
    include JSON::Serializable
  end


  def stock_option_chain_contracts_by_expirations(
    symbol :          String,        # MSFT
    option_exchange : String,        # AMEX, differnt from the stock exchange
    currency :        String,        # USD
    expirations :     Array(String)  # 2020-01-01
  ): Array(OptionContractWithId)
    requests = expirations.map { |expiration| {
      path: "/api/v1/stock_option_chain_contracts_by_expiration",
      body: { symbol: symbol, option_exchange: option_exchange, currency: currency, expiration: expiration }
    }}
    http_post_batch("/api/v1/call", requests, Array(OptionContractWithId)).map do |r|
      raise r if r.is_a? Exception
      r
    end
      .flatten
  end


  def stock_options_prices(
    contracts : Array(StockOptionParams),
  ): Array(Exception | SnapshotPrice)
    requests = contracts.map { |params| { path: "/api/v1/stock_option_price", body: params } }
    http_post_batch("/api/v1/call", requests, SnapshotPrice)
  end

  record StockOptionParams,
    symbol :          String,  # MSFT
    right :           Right,   # "put" or "call"'
    expiration :      String,  # 2020-08-21
    strike :          Float,   # 120
    option_exchange : String,  # AMEX, option exchange, different from the stock exchange
    currency :        String,  # USD
    data_type :       MarketDataType,
  do
    include JSON::Serializable
  end


  def portfolio : Array(Portfolio)
    Array(Portfolio).from_json http_get "/api/v1/portfolio"
  end

  record PortfolioStockContract,
    symbol :   String,
    exchange : String?, # IB dosn't always provide it
    currency : String,
    id :       Int,     # IB id for contract
  do
    include JSON::Serializable
  end

  record PortfolioOptionContract,
    symbol :     String,
    right :      Right,   # "put" or "call"'
    expiration : String,  # 2020-08-21
    strike :     Float,   # 120
    exchange :   String?, # IB dosn't always provide it
    currency :   String,
    id :         Int,     # IB id for contract
    multiplier : Int,     # Usually 100
  do
    include JSON::Serializable
  end

  record StockPortfolioPosition,
    position :     Int,
    average_cost : Float,
    contract :     PortfolioStockContract,
  do
    include JSON::Serializable
  end

  record OptionPortfolioPosition,
    position :     Int,
    average_cost : Float,
    contract :     PortfolioOptionContract,
  do
    include JSON::Serializable
  end

  record Portfolio,
    account_id :    String,
    stocks :        Array(StockPortfolioPosition),
    stock_options : Array(OptionPortfolioPosition),
    cash_in_usd :   Float,
  do
    include JSON::Serializable
  end


  # Helpers ----------------------------------------------------------------------------------------


  protected def http_get(path : String, query = NamedTuple.new())
    resp = HTTP::Client.get @base_url + path + '?' + URI::Params.encode(query)
    unless resp.success?
      raise "can't get #{path} #{query}, #{resp.headers["error"]? || resp.body? || "unknown error"}"
    end
    resp.body
  end

  protected def http_post_batch(
    path :     String,
    requests : Array(NamedTuple(path: String, body: Object)),
    klass :    T.class
  ) : Array(T | Exception) forall T
    resp = HTTP::Client.post @base_url + path, body: requests.to_json
    unless resp.success?
      raise "can't post batch #{path}, #{resp.headers["message"]? || resp.body? || "unknown error"}"
    end
    parts = JSON.parse(resp.body); i = 0; results = [] of T | Exception;
    while i < parts.size
      part = parts[i]; i += 1
      is_errorneous_wrapper = begin
        part["is_error"] # it throws an error if it's not object or doesn't have `is_error`
        true
      rescue
        false
      end
      if is_errorneous_wrapper
        if part["is_error"].as_bool
          results << Exception.new(part["error"].as_s)
        else
          results << klass.from_json(part["value"].to_json)
        end
      else
        results << klass.from_json(part.to_json)
      end
    end
    results
  end

end