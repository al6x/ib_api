package bon.io

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter


object Json {
  fun to_json(v: Any, pretty: Boolean = true): String {
    val builder = GsonBuilder()
    builder.registerTypeAdapterFactory(EnumTypeAdapterFactory())
    builder.disableHtmlEscaping()
    if (pretty) builder.setPrettyPrinting()
    val gson: Gson = builder.create()
    return gson.toJson(v)
  }

  // The difference from GSON is that it's more strict, GSON won't throw error if
  // missing property accessed.
  fun from_json(data: String): UntypedJson = UntypedJson(JsonParser.parseString(data))

  // toJson ------------------------------------------------------------------------------
  // Making GSON to emit the actual enum values, not Java enum names, see http://tiny.cc/2ydksz
  class EnumTypeAdapter<T> : TypeAdapter<T>() {
    override fun write(out: JsonWriter, value: T) { out.value(value.toString()) }
    override fun read(in_reader: JsonReader?): T? { return null }
  }

  class EnumTypeAdapterFactory : TypeAdapterFactory {
    // Actually it might be easier to just make EnumTypeAdapter non-generic
    // but on the other hand it might be better if used in some other contexts
    // and in some other ways. Thus these suppressions
    override fun <T> create(gson: Gson?, type: TypeToken<T>): TypeAdapter<T>? {
      // This if applies to all Enums. Change if not wanted.
      return if (Enum::class.java.isAssignableFrom(type.getRawType())) EnumTypeAdapter<T>()
      else null
    }
  }

  // fromJson ----------------------------------------------------------------------------
  class UntypedJson(private val json: JsonElement) {
    private operator fun get(name: String): JsonElement {
      if (!json.asJsonObject.has(name)) throw Exception("json doesn't have property $name")
      return json.asJsonObject.get(name)
    }

    operator fun contains(name: String) = json.asJsonObject.has(name)

    fun get_string()             = json.asString
    fun get_string(name: String) = get(name).asString

    fun get_integer()             = json.asInt
    fun get_integer(name: String) = get(name).asInt

    fun get_array(name: String): List<UntypedJson> = get(name).asJsonArray.map(Json::UntypedJson)

    fun get_array(): List<UntypedJson> = json.asJsonArray.map(Json::UntypedJson)
  }
}