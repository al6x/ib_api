require "./ib.cr"

ib = IB.new()

# US stocks ----------------------------------------------------------------------------------------
p ib.stock_contract "MSFT", "ISLAND", "USD"

p ib.stock_contracts "MSFT"

p ib.stock_price "MSFT", "ISLAND", "USD"

p ib.stock_option_chains "MSFT", "ISLAND", "USD"

p ib.stock_option_chain_contracts_by_expirations "MSFT", "CBOE", "USD", ["2022-09-16"]

# Crystal problem 2, auto type casting is not working
# p ib.stock_options_prices [
#   {
#     symbol: "MSFT", right: :call, expiration: "2022-06-17", strike: 220,
#     option_exchange: "CBOE", currency: "USD", data_type: :delayed_frozen
#   },
#   {
#     symbol: "MSFT", right: :call, expiration: "2022-06-17", strike: 225,
#     option_exchange: "CBOE", currency: "USD", data_type: :delayed_frozen
#   }
# ]

p ib.stock_options_prices [
  {
    symbol: "MSFT", right: IB::Right::Call, expiration: "2022-06-17", strike: 220.0,
    option_exchange: "CBOE", currency: "USD", data_type: IB::MarketDataType::DelayedFrozen
  },
  {
    symbol: "MSFT", right: IB::Right::Call, expiration: "2022-06-17", strike: 225.0,
    option_exchange: "CBOE", currency: "USD", data_type: IB::MarketDataType::DelayedFrozen
  }
]