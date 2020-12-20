multiple top level site declarations

null checks 

    val event = mevent ?: throw Exception("event not found")

immutability by default for arguments

immutability for variables and properties with val 

immutable interface List, Map, Set

top level functions

flexible file structure, no 1 to 1 Java class-to files limitation

mulit-methods/multi-dispatch via extension functions, proper function overload, control flow as expressions

closed classes by default and requiring open/override explicitly

multiple inheritance and way to resolve conflicting method names

immutable val

short and compact syntax

type infer

extension properties

named arguments and destructors

Lambda last argument passing
p(items.fold(0) { acc, i -> acc + i })

No compact way to initialise lists and maps, not good

Almost like Ruby (1..10).map { p(it) }

no semicolons

The `Any` has three methods: `equals()`, `hashCode()` and `toString()`.

`copy`, `hashCode` and `toString` on data classes.

Named arguments and destructors for data classes `jack.copy(age = 2)`, `val (name, age) = jack`

The `name` and `ordinal` for enum.

Implementation by delegation

Typealiases

    typealias MyHandler = (Int, String, Any) -> Unit
    typealias Predicate<T> = (T) -> Boolean

Lambda argument passing foo(1) { println("hello") }

Vararg `fun fn(vararg list: String)`, `fn("a", "b", "c")`, `fn(*arrayOf("a", "b", "c"))`

Top level and local function declaration

Tail recursion

Functions

    fun <T, R> Collection<T>.fold(
        initial: R,
        combine: (acc: R, nextElement: T) -> R
    ): R {
        var accumulator: R = initial
        for (element: T in this) {
            accumulator = combine(accumulator, element)
        }
        return accumulator
    }

    val fn: (acc: Int, i: Int) -> Int = { acc, i -> acc + i }
    val fn: (Int, Int) -> Int         = { acc, i -> acc + i }

    p(items.fold(0) { acc, i -> acc + i })

    function with and without receiver are interchangeable

    list.filter { it > 0 }

    (1..10).map { p(it) }
    
    val numbers = to_list("one", "two", "three", "four")  
    p(list - "one")
    
    list.sortedWith(compareBy({ it.age }, { it.name }))
    
    class Person(val name: String, val age: Int)
    to_list(Person("Alex", 20).sort_by({ it.name }, { it.age })
    
    to_list(1, 2) + 3
    to_list(1, 2) - 2
    // Same for map
    
    numbers.slice(1..3)
    numbers.slice(to_set(3, 5, 0))
    
    operator fun <V> List<V>.get(range: IntRange): List<V> = this.slice(range)
    operator fun <V> List<V>.get(indices: Set<Int>): List<V> = this.slice(indices)
    list_of(1, 2, 3)[1..2]
    
    for ((key, value) in map) {}
    list.map { (v, k) -> v } 
    some_fn { (a, b), c -> ... }
    
    when (x) {
        0, 1 -> print("x == 0 or x == 1")
        else -> print("otherwise")
    }
    
    when {
        x.isOdd() -> print("x is odd")
        y.isEven() -> print("y is even")
        else -> print("x+y is even.")
    }
    
    fun Request.getBody() = when (val response = executeRequest()) {
        is Success -> response.body
        is HttpError -> throw HttpException(response.status)
    }
    
    a(i, j)	a.invoke(i, j)
    
    Properly working catch(e: EClass) blocks
    
    val v = try { parse_int(str) } catch () {}
 
    Never returning functions   
    fun fail(message: String): Nothing {
        throw IllegalArgumentException(message)
    } 
    
    fun <T> T.apply(block: T.() -> Unit): T {}
    fun <T, R> T.let(block: (T) -> R): R {}
    
    @Synchronized and synchronized(lock) { ... }
    
    fun some() = try {
      ...
    } catch (e: Exception) {
      ...
    }
    
Short syntax for Lambdas

# Extra

Delegated properties

Delegated properties in Map

Local delegates, lazy

# Disadvantages

No list and map literals

Compilation still not very fast, feels slow

Bad Java build tools, old ugly and bloated Maven, and new Gradle that's even worse.

# TypeScript

No literal types

No flexible, type safe, object destructing and constructing

Less flexible and type safe metaprogramming

Lack of literal types and object constructors destructors - harder to build state machines, like 

    let task =  { ...task.state, processed: true }