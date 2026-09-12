package com.mygoll.fourform

/**
 * The ONLY place for loop timings (criterion 10 of brief 242): on 09/12, calibrating
 * means editing here and rebuilding, no hunting for a scattered constant.
 */
object Pace {
    // ponytail: both numbers are MY GUESS, not a measurement. Anthuan asked for a visible
    // pace ("it's fine if it takes a little longer"): the pause between fields is what lets
    // the person SEE the agent filling one at a time, in order, instead of everything
    // appearing already done.
    const val ENTRE_CAMPOS_MS = 350L

    // how long to wait after ACTION_SCROLL_FORWARD for the screen to settle and the tree
    // to reflect what entered the viewport; too short = the scan sees the stale screen.
    const val POS_ROLAGEM_MS = 500L

    // drag duration when the tree action doesn't move the screen (WebView). Too fast
    // turns into a "fling" and the page flies past fields; too slow and the user thinks it
    // froze. 300ms is a deliberate drag that stops where the finger stopped.
    // ponytail: single value; if some app needs a different one, it becomes a per-package table.
    const val GESTO_MS = 300L
}
