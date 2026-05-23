package xyz.kbrowser.webview

sealed interface LoadingState {
    data object Initializing : LoadingState
    data object Loading : LoadingState
    data object Finished : LoadingState
    data class Error(val errorCode: Int, val description: String, val failingUrl: String) : LoadingState
}
