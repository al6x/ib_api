package bon.io

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator

object Yaml {
  fun to_yaml(v: Any): String {
    val mapper = ObjectMapper(
    YAMLFactory()
      .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
      .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
      .disable(YAMLGenerator.Feature.SPLIT_LINES)
//      .enable(YAMLGenerator.Feature.INDENT_ARRAYS)
//      .enable(YAMLGenerator.Feature.LITERAL_BLOCK_STYLE)
    )
    mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    return mapper.writeValueAsString(v)
  }
}