package ib.lib

import bon.*
import com.ib.client.TickType

// EventTypes separates TickType events into meaningfull groups. Also TickType.get has a
// problem and shoulnd't be used as it would silently return UNKNOWN for unknown event instead
// of throwing exception.
class EventTypes {


  // InternalPriceType -----------------------------------------------------------------------------
  enum class InternalPriceType(private val type: TickType) {
    HIGH          (TickType.HIGH),
    DELAYED_HIGH  (TickType.DELAYED_HIGH),
    LOW           (TickType.LOW),
    DELAYED_LOW   (TickType.DELAYED_LOW),
    BID           (TickType.BID),
    DELAYED_BID   (TickType.DELAYED_BID),
    ASK           (TickType.ASK),
    DELAYED_ASK   (TickType.DELAYED_ASK),
    LAST          (TickType.LAST),
    DELAYED_LAST  (TickType.DELAYED_LAST),
    CLOSE         (TickType.CLOSE),
    DELAYED_CLOSE (TickType.DELAYED_CLOSE),
    OPEN          (TickType.OPEN),
    DELAYED_OPEN  (TickType.DELAYED_OPEN);

    override fun toString(): String = type.field()

    companion object {
      fun value_of(value: Int): InternalPriceType {
        for (type in values()) if (type.type.index() == value) return type
        throw Exception("Unknown InternalPriceType $value")
      }
    }

  }

  // SizeType ---------------------------------------------------------------------------
  enum class InternalSizeType(private val type: TickType) {
    LAST_SIZE             (TickType.LAST_SIZE),
    DELAYED_LAST_SIZE     (TickType.DELAYED_LAST_SIZE),
    BID_SIZE              (TickType.BID_SIZE),
    DELAYED_BID_SIZE      (TickType.DELAYED_BID_SIZE),
    ASK_SIZE              (TickType.ASK_SIZE),
    DELAYED_ASK_SIZE      (TickType.DELAYED_ASK_SIZE),
    VOLUME                (TickType.VOLUME),
    DELAYED_VOLUME        (TickType.DELAYED_VOLUME),
    FUTURES_OPEN_INTEREST (TickType.FUTURES_OPEN_INTEREST),
    AVG_OPT_VOLUME        (TickType.AVG_OPT_VOLUME),
    SHORTABLE_SHARES      (TickType.SHORTABLE_SHARES);

    override fun toString(): String = type.field()

    companion object {
      fun value_of(value: Int): InternalSizeType {
        for (type in values()) if (type.type.index() == value) return type
        throw Exception("Unknown InternalPriceType $value")
      }
    }

  }

  // TimeType ----------------------------------------------------------------------------
  enum class InternalTimeType(private val type: TickType) {
    LAST_TIMESTAMP         (TickType.LAST_TIMESTAMP),
    DELAYED_LAST_TIMESTAMP (TickType.DELAYED_LAST_TIMESTAMP);

    override fun toString(): String = type.to_string()

    companion object {
      fun value_of(value: Int): InternalTimeType {
        for (type in values()) if (type.type.index() == value) return type
        throw Exception("Unknown InternalTimeType $value")
      }
    }

  }
}