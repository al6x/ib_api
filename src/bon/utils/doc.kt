package utils

import bon.*

sealed class DocItem()

class TextDoc(
  val title: String,
  val text:  String,
  val tags:  List<String>
) : DocItem()

enum class TodoPriority { low, normal, high }

class TodoDoc(
  val todo:     String,
  val priority: TodoPriority,
  val tags:     List<String>
) : DocItem()

val all_docs: MutableList<DocItem> = mutable_list_of()