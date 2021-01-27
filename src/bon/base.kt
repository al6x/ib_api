package bon

import bon.io.Json
import bon.io.Yaml

typealias Void = Unit

// Iterable  ----------------------------------------------------------------------------------------
fun <V, R> Iterable<V>.map(transform: (V, Int) -> R): List<R> =
  this.mapIndexed { i, v -> transform(v, i) }

operator fun <V> List<V>.get(range: IntRange): List<V> = this.slice(range)
operator fun <V> List<V>.get(indices: Set<Int>): List<V> = this.slice(indices)

inline fun <V> Iterable<V>.each(action: (V) -> Void): Void = this.forEach(action)
inline fun <V> Array<V>.each(action: (V) -> Void): Void = this.forEach(action)

fun <V> Iterable<V>.sort_by(s1: (V) -> Comparable<*>, s2: (V) -> Comparable<*>, s3: (V) -> Comparable<*>) =
  sort_iterable_by(this, s1, s2, s3)
fun <V> Iterable<V>.sort_by(s1: (V) -> Comparable<*>, s2: (V) -> Comparable<*>) =
  sort_iterable_by(this, s1, s2)
fun <V> Iterable<V>.sort_by(s1: (V) -> Comparable<*>) = sort_iterable_by(this, s1)

fun <V> sort_iterable_by(iterable: Iterable<V>, vararg selectors: (V) -> Comparable<*>): List<V> {
  if (selectors.is_empty()) throw Exception("at least one comparator required")
  return iterable.sortedWith(compareBy(*selectors))
}

fun Iterable<*>.is_empty() = !this.iterator().hasNext()
fun Array<*>.is_empty() = size == 0


// List --------------------------------------------------------------------------------------------
fun <V> list_of(vararg array: V): List<V> = listOf(*array)
fun <V> list_of(iterable: Iterable<V>): List<V> = iterable.toList()

fun <V> Iterable<V>.to_list() = list_of(this)

fun <T, K> Iterable<T>.group_by(keySelector: (T) -> K): Dict<K, List<T>> = dict_of(this.groupBy(keySelector))

fun <V, R> Iterable<V>.filter_map(convert: (V) -> R?): List<R> {
  val results = mutable_list_of<R>()
  for (v in this) {
    val r = convert(v)
    if (r != null) results.add(r)
  }
  return results
}


// MutableList -------------------------------------------------------------------------------------
fun <V> mutable_list_of(vararg array: V): MutableList<V> = mutableListOf(*array)
fun <V> mutable_list_of(iterable: Iterable<V>): MutableList<V> = iterable.toMutableList()

fun <V> MutableList<V>.add_all(iterable: Iterable<V>): Boolean = this.addAll(iterable)


// Set ---------------------------------------------------------------------------------------------
fun <V> set_of(vararg array: V) = setOf(*array)
fun <V> set_of(iterable: Iterable<V>) = iterable.toSet()

fun <V> Iterable<V>.to_set() = this.toSet()


// MutableSet -------------------------------------------------------------------------------------
fun <V> mutable_set_of(vararg array: V): MutableSet<V> = mutableSetOf(*array)
fun <V> mutable_set_of(iterable: Iterable<V>): MutableSet<V> = iterable.toMutableSet()


// Dict --------------------------------------------------------------------------------------------
class Dict<K, V>(private val map: Map<K, V>) : Map<K, V> by map
//  , Iterable<Map.Entry<K, V>>
{
  operator fun get(key: K, default: V): V = map[key] ?: default

//  override fun iterator() = map.entries.iterator()
}

fun <K, V> dict_of(vararg pairs: Pair<K, V>) = Dict(mapOf(*pairs))
fun <K, V> dict_of(map: Map<K, V>) = Dict(map)

fun <K, V, R> Map<out K, V>.map(transform: (V) -> R): Dict<K, R> {
  val result = mutable_dict_of<K, R>()
  for ((k, v) in this) result[k] = transform(v)
  return dict_of(result)
}

fun <K, V, R> Map<out K, V>.map(transform: (V, K) -> R): Dict<K, R> {
  val result = mutable_dict_of<K, R>()
  for ((k, v) in this) result[k] = transform(v, k)
  return dict_of(result)
}

fun <K, V> Iterable<Pair<K, V>>.to_dict(): Dict<K, V> {
  val result = mutable_dict_of<K, V>()
  for ((k, v) in this)
    if (k in result) throw Exception("duplicate key $k")
    else             result[k] = v
  return dict_of(result)
}

fun <K, V> List<V>.to_dict(key: (V) -> K): Dict<K, V> {
  val result = mutable_dict_of<K, V>()
  for (v in this) {
    val k = key(v)
    if (k in result) throw Exception("duplicate key $k")
    else             result[k] = v
  }
  return dict_of(result)
}

// MutableDict ------------------------------------------------------------------------------------
class MutableDict<K, V>(
  private val map: MutableMap<K, V>
) : MutableMap<K, V> by map
//  , Iterable<Map.Entry<K, V>>
{
//  override operator fun get(key: K): V = map[key].assert_not_null {"key required $key" }

  operator fun get(key: K, default: V): V = map[key] ?: default

  fun get_or_put(key: K, default: (key: K) -> V): V = map.getOrPut(key) { default(key) }

//  override fun iterator() = map.entries.iterator()
}

fun <K, V> mutable_dict_of(vararg pairs: Pair<K, V>) = MutableDict(mutableMapOf(*pairs))
fun <K, V> mutable_dict_of(map: MutableMap<K, V>) = MutableDict(map)


// Errorneous --------------------------------------------------------------------------------------
sealed class Errorneous<R> {
  fun get(): R = when (this) {
    is Success -> this.result
    is Fail    -> throw this.error
  }
}
data class Success<R>(val result: R) : Errorneous<R>()
data class Fail<R>(val error: Exception) : Errorneous<R>()

sealed class ErrorneousS<R> {
  fun get(): R = when (this) {
    is SuccessS -> this.result
    is FailS    -> throw Exception(this.error)
  }

  companion object {
    fun <R> from(er: Errorneous<R>): ErrorneousS<R> = when (er) {
      is Success<R> -> SuccessS(er.result)
      is Fail<R>    -> FailS(er.error)
    }
  }
}
data class SuccessS<R>(val result: R) : ErrorneousS<R>()
data class FailS<R>(val error: String) : ErrorneousS<R>() {
  constructor(error: Exception): this(error.message ?: "Unknown error")
}

// JSON and YAML -----------------------------------------------------------------------------------
fun Any.to_json(pretty: Boolean = true) = Json.to_json(this, pretty)
fun from_json(json: String) = Json.from_json(json)

fun Any.to_yaml() = Yaml.to_yaml(this)


// Any --------------------------------------------------------------------------------------------
fun Any.hash_code() = this.hashCode()
fun Any.to_string() = this.toString()


fun <T> T?.assert_not_null(message: () -> String = { "not null required" }): T =
  this ?: throw Exception(message())

fun <T> p(a: T): Void = println(a)


//object BaseConfig {
//  val test = (get_env("test", "false").toLowerCase() == "true"
//}
//
//fun test(name: String, test: () -> Void) {
//  try {
//    if (BaseConfig.test) test()
//  } catch (e: Exception) {
//    log_error("'$name' failed", null)
//    throw e
//  }
//}

// error_type --------------------------------------------------------------------------------------
// Extract error type from message, the last part `... :type`
// Example `"Error code 200 No security definition has been found for the request :not_found"`
fun error_type(message: String): String? {
  return if (message.matches(".*\\s:[a-zA-Z0-9_-]+$".toRegex())) message.split("\\s:".toRegex()).last()
  else null
}
fun error_type(e: Exception): String? = error_type(e.message ?: "")


// timer_ms ----------------------------------------------------------------------------------------
fun timer_ms(start_ms: Long = System.currentTimeMillis()): () -> Long =
  { System.currentTimeMillis() - start_ms }

// assert ------------------------------------------------------------------------------------------
// Kotlin `assert` isn't enabled by default and requires `-ea` runtime JVM option.
fun assert(condition: Boolean, message: (() -> String) = { "Assert failed" }): Void {
  if (!condition) throw Exception(message())
}

// Constants ---------------------------------------------------------------------------------------
val sec_ms  = 1000
val min_ms  = 60 * sec_ms
val hour_ms = 60 * min_ms
val day_ms  = 24 * hour_ms