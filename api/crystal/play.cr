require "json"

record Stock,
  symbol :           String,
  name :             String,
do
  include JSON::Serializable
end

p Stock.from_json %({ "symbol": "MSFT", "name": "Some" })