package com.yunget.app.ui.navigation

/** A tablet in portrait still needs a rail; a short phone window needs a bottom bar. */
internal fun usesNavigationRail(widthDp: Int, heightDp: Int): Boolean =
    widthDp >= 600 && heightDp >= 360
