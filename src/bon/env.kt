package bon

import bon.*

// Allows to set defaults for shell env variables. The `get` would get value from:
// - shell variable if defined
// - varialbe set by `set`
// - default
object Env {
  private val variables:      MutableDict<String, String> = mutable_dict_of()
  private val used_variables: MutableSet<String>          = mutable_set_of()

  fun set(vararg pairs: Pair<String, Any>): Void { for ((k, v) in pairs) set(k, v.to_string()) }

  operator fun set(key: String, value: Boolean): Void { set(key, value.to_string()) }
  operator fun set(key: String, value: Int): Void { set(key, value.to_string()) }
  operator fun set(key: String, value: String): Void {
    assert(key !in variables)      { "environment variable $key already set" }
    assert(key !in used_variables) { "environment variable $key already used" }
    variables[key] = value
  }

  operator fun get(key: String, default: String): String {
    if (key !in used_variables) used_variables.add(key)
    return System.getenv(key) ?: variables[key] ?: default
  }

  operator fun get(key: String): String {
    if (key !in used_variables) used_variables.add(key)
    return System.getenv(key) ?: variables[key] ?: throw Exception("no environment variable $key")
  }
}