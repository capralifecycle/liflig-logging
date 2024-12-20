package no.liflig.logging

@PublishedApi
internal inline fun <T> List<T>.forEachReversed(action: (T) -> Unit) {
  // downTo returns an empty range if first argument is less than the second, so we don't need to
  // check bounds here
  for (index in (this.size - 1) downTo 0) {
    action(this[index])
  }
}

// Duplicated implementations for List and Array, since they share no common interface, and we don't
// want to wrap the Array where we use this.
@PublishedApi
internal inline fun <T> Array<T>.forEachReversed(action: (T) -> Unit) {
  // downTo returns an empty range if first argument is less than the second, so we don't need to
  // check bounds here
  for (index in (this.size - 1) downTo 0) {
    action(this[index])
  }
}
