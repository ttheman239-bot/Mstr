package com.mstr.btccompare.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mstr.btccompare.data.CompareSeries
import com.mstr.btccompare.data.PriceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface UiState {
    data object Loading : UiState
    data class Ready(val data: CompareSeries) : UiState
    data class Error(val message: String) : UiState
}

class MainViewModel(
    private val repo: PriceRepository = PriceRepository()
) : ViewModel() {

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _period = MutableStateFlow(365)
    val period: StateFlow<Int> = _period.asStateFlow()

    init {
        refresh()
    }

    fun setPeriod(days: Int) {
        if (_period.value == days) return
        _period.value = days
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = UiState.Loading
            _state.value = try {
                UiState.Ready(repo.load(_period.value))
            } catch (t: Throwable) {
                UiState.Error(t.message ?: "Unknown error")
            }
        }
    }
}
