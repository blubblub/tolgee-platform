package io.tolgee.model.enums

enum class BinaryAssetMediaType {
  AUDIO,
  VIDEO,
  IMAGE,
  ;

  companion object {
    fun parseList(raw: Collection<String>?): Set<BinaryAssetMediaType> {
      if (raw.isNullOrEmpty()) return emptySet()
      return raw
        .flatMap { it.split(',') }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { value ->
          entries.find { it.name.equals(value, ignoreCase = true) }
        }.toSet()
    }
  }
}
