require "./ib.cr"

ib = IB.new()

# US stocks ----------------------------------------------------------------------------------------
pp ib.stock_contract "MSFT", "ISLAND", "USD"

pp ib.stock_contracts "MSFT"

pp ib.stock_price "MSFT", "ISLAND", "USD"

pp ib.stock_option_chains "MSFT", "ISLAND", "USD"

pp ib.stock_option_chain_contracts_by_expirations "MSFT", "CBOE", "USD", ["2022-09-16"]

pp ib.stock_options_prices [
  IB::StockOptionParams.new(
    symbol: "MSFT", right: IB::Right::Call, expiration: "2022-06-17", strike: 220.0,
    option_exchange: "CBOE", currency: "USD", data_type: IB::MarketDataType::DelayedFrozen
  ),
  IB::StockOptionParams.new(
    symbol: "MSFT", right: IB::Right::Call, expiration: "2022-06-17", strike: 225.0,
    option_exchange: "CBOE", currency: "USD", data_type: IB::MarketDataType::DelayedFrozen
  )
]

# International stocks -----------------------------------------------------------------------------
# TODO add European and Asian stocks examples