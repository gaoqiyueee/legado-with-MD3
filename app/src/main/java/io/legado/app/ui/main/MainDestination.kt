package io.legado.app.ui.main

import androidx.annotation.StringRes
import io.legado.app.R

sealed class MainDestination(
    val route: String,
    @StringRes val labelId: Int
) {
    object Bookshelf : MainDestination(
        route = "bookshelf",
        labelId = R.string.bookshelf
    )

    object Explore : MainDestination(
        route = "explore",
        labelId = R.string.discovery
    )

    object ReadRecordTab : MainDestination(
        route = "readRecord",
        labelId = R.string.read_record
    )

    object Rss : MainDestination(
        route = "rss",
        labelId = R.string.rss
    )

    object My : MainDestination(
        route = "my",
        labelId = R.string.my
    )

    companion object {
        val mainDestinations = listOf(Bookshelf, Explore, ReadRecordTab, Rss, My)
    }
}
