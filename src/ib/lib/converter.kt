package ib.lib

import bon.*
import com.ib.client.Contract
import com.ib.client.ContractDetails
import com.ib.client.Types.Right
import com.ib.client.Types.SecType
import ib.IB.*
import ib.lib.IBWrapper.ContractWithPositionMultiEvent
import kotlin.math.roundToInt

// Converts TWS data into more sane formats.
object Converter {
  // parse_stock_contract --------------------------------------------------------------------------
  fun parse_stock_contract(cd: ContractDetails): StockContract {
    assert(cd.contract().secType() == SecType.STK)
    return StockContract(
      symbol           = cd.contract().symbol(),
      name             = cd.longName(),
      exchange         = cd.contract().exchange(),
      primary_exchange = cd.contract().primaryExch(),
      currency         = cd.contract().currency(),
      id               = cd.contract().conid()
    )
  }


  // parse_portfolio_position ----------------------------------------------------------------------
  fun parse_and_add_portfolio_position(
    account_id: String?, chunk: ContractWithPositionMultiEvent
  ): PortfolioPosition<Any> {
    val symbol = chunk.contract.symbol()
    if (account_id != null && account_id != chunk.account_id) throw Exception(
      "Expected position for account $account_id but got instead account ${chunk.account_id}"
    )

    val position = chunk.position.roundToInt()
    if (position.toDouble() != chunk.position) {
      throw Exception("invalid position is not integer ${chunk.position} for $symbol")
    }

    val average_cost = chunk.average_cost
    if (average_cost < 0) throw Exception("negative average cost $average_cost for $symbol")

    return PortfolioPosition(
      position     = position,
      average_cost = average_cost,
      contract     = parse_portfolio_contract(chunk.contract)
    )
  }


  // parse_portfolio_contract ----------------------------------------------------------------------
  fun parse_portfolio_contract(ibc: Contract): Any {
    val id                = ibc.conid()
    val ib_ctype          = ibc.secType()
    val symbol            = ibc.symbol()
    val exchange: String? = ibc.exchange() // It could be null
    val currency          = ibc.currency()
    val multiplier        = parse_multiplier(ibc.multiplier(), symbol)
    return when (ib_ctype) {
      SecType.STK -> {
        assert(multiplier == 1) { "wrong multiplier for stock or ETF position $symbol $multiplier" }
        PortfolioStockContract(
          symbol   = symbol,
          exchange = exchange,
          currency = currency,
          id       = id
        )
      }

      SecType.OPT -> {
//        assert(multiplier == 100) { "wrong multiplier for option position $symbol $multiplier" }
        PortfolioOptionContract(
          symbol     = symbol,
          exchange   = exchange,
          currency   = currency,
          id         = id,
          expiration = yyyymmdd_to_yyyy_mm_dd(ibc.lastTradeDateOrContractMonth()),
          strike     = ibc.strike(),
          right      = ib_right_to_right(ibc.right(), symbol),
          multiplier = multiplier
        )
      }

      else -> throw Exception("Unsupported contract type '$ib_ctype'")
    }
  }


  // parse_option_contract -------------------------------------------------------------------------
  fun parse_option_contract(
    symbol: String, exchange: String, currency: String, cd: ContractDetails
  ): Pair<OptionContract, Int> {
    val c = cd.contract()
    assert(SecType.OPT == c.secType()) {
      "wrong type for option contract $symbol ${c.secType()}"
    }
    assert(symbol == c.symbol()) {
      "wrong symbol for option contract expected $symbol got ${c.symbol()}"
    }
    assert(exchange == c.exchange()) {
      "wrong exchange for option contract $symbol expected $exchange got ${c.exchange()}"
    }
    assert(currency == c.currency()) {
      "wrong currency for option contract $symbol expected $currency got ${c.currency()}"
    }
    val multiplier = parse_multiplier(c.multiplier(), symbol)
//    assert(multiplier in set_of(100, 1000)) {
//      "wrong multiplier for option contract $symbol expected $multiplier got ${c.multiplier()}"
//    }
    return Pair(OptionContract(
      id         = cd.conid(),
      expiration = yyyymmdd_to_yyyy_mm_dd(c.lastTradeDateOrContractMonth()),
      strike     = c.strike(),
      right      = ib_right_to_right(c.right(), symbol)
    ), multiplier)
  }


  // Helpers -----------------------------------------------------------------------------
  // Converts "20180101" into "2018-01-01"
  fun yyyymmdd_to_yyyy_mm_dd(yyyymmdd: String): String {
    if (!yyyymmdd.matches("\\d\\d\\d\\d\\d\\d\\d\\d".toRegex())) throw Exception(
      "Invalid date format, expected yyyymmdd got $yyyymmdd"
    )
    return yyyymmdd.substring(0, 4) + "-" + yyyymmdd.substring(4, 6) + "-" + yyyymmdd.substring(6, 8)
  }

  // Converts "2018-01-01" into "20180101"
  fun yyyy_mm_dd_to_yyyymmdd(yyyy_mm_dd: String): String {
    if (!yyyy_mm_dd.matches("\\d\\d\\d\\d-\\d\\d-\\d\\d".toRegex())) throw Exception(
      "Invalid date format, expected yyyy-mm-dd got $yyyy_mm_dd"
    )
    return yyyy_mm_dd.replace("-", "")
  }

  // Checks if it's none, 1 or 100
  fun parse_multiplier(multiplier: String?, error_info: String): Int =
    (multiplier ?: "1").toFloat().toInt()
//    when (multiplier) {
//      "1"    -> 1
//      null   -> 1    // I guess null means 1?
//      "10"   -> 10   // Example AIR:DTB:EUR
//      "100"  -> 100
//      "1000" -> 1000 // Example GLEN:EUREXUK:GBP
//      else   -> throw Exception("unknown multiplier $multiplier for $error_info")
//    }

  fun ib_right_to_right(right: Right, error_info: String): String = when (right) {
    Right.Call -> "call"
    Right.Put  -> "put"
    else       -> throw Exception("unknown right $right for $error_info")
  }

  fun right_to_ib_right(right: String): Right = when (right) {
    "call" -> Right.Call
    "put"  -> Right.Put
    else       -> throw Exception("unknown right $right")
  }
}