package io.github.takahirom.arbigent.coroutines

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*

inline internal fun <reified T, R> CoroutineScope.buildSingleSourceStateFlow(
  sourceStateFlow: StateFlow<T>,
  crossinline buildBlock: (T) -> R
): StateFlow<R> {
  val getSourceValueArray: () -> Array<*> = {
    arrayOf(sourceStateFlow.value)
  }
  val callBuildBlockByCurrentSource: () -> R = {
    buildBlock(sourceStateFlow.value)
  }
  val convertedStateFlow = sourceStateFlow.map {
    getSourceValueArray() to buildBlock(it)
  }.stateIn(
    this,
    SharingStarted.WhileSubscribed(5000),
    getSourceValueArray() to callBuildBlockByCurrentSource()
  )
  return buildStateFlow(
    callBuildBlockByCurrentSource = callBuildBlockByCurrentSource,
    getSourceValueArray = getSourceValueArray,
    convertedStateFlow = convertedStateFlow
  )
}

internal fun <R> buildStateFlow(
  callBuildBlockByCurrentSource: () -> R,
  getSourceValueArray: () -> Array<*>,
  convertedStateFlow: StateFlow<Pair<Array<*>, R>>
): StateFlow<R> {
  return object : StateFlow<R> {
    override val replayCache: List<R>
      get() {
        return listOf(value)
      }
    override val value: R
      get() {
        val result = callBuildBlockByCurrentSource()
        return result
      }

    override suspend fun collect(collector: FlowCollector<R>): Nothing {
      if (!getSourceValueArray().contentDeepEquals(convertedStateFlow.value.first)) {
        convertedStateFlow.first { it.first.contentDeepEquals(getSourceValueArray()) }
      }
      convertedStateFlow.collect {
        collector.emit(it.second)
      }
    }
  }
}

public inline fun <reified T, R> CoroutineScope.buildFlatMapLatestSingleSourceStateFlow(
  sourceStateFlow: StateFlow<T>,
  crossinline transformForFlow: suspend (value: T) -> Flow<R>,
  crossinline transformForValue: (T) -> R
): StateFlow<R> {
  val flow = sourceStateFlow.flatMapLatest { value: T ->
    transformForFlow(value)
  }
    .stateIn(this, SharingStarted.WhileSubscribed(5000), transformForValue(sourceStateFlow.value))
  return object : StateFlow<R> {
    override val replayCache: List<R>
      get() = listOf(value)
    override val value: R
      get() = transformForValue(sourceStateFlow.value)

    override suspend fun collect(collector: FlowCollector<R>): Nothing {
      flow.first { it == value }
      flow.collect(collector)
    }
  }
}