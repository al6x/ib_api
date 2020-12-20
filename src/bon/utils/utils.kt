package bon.utils

import bon.*
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

fun to_string(stream: InputStream): String {
  val bis = BufferedInputStream(stream)
  val buf = ByteArrayOutputStream()
  var result = bis.read()
  while (result != -1) {
    val b = result.toByte()
    buf.write(b.toInt())
    result = bis.read()
  }
  return buf.toString()
}