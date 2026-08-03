package io.github.draumaz.artlock.data.model

sealed class LookupResult {
    data class StaticHighRes(val imageUrl: String) : LookupResult()
    data object NotFound : LookupResult()
    data class Error(val message: String) : LookupResult()
}