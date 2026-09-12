package com.mygoll.fourform.agent

/**
 * The PURE part of the bench probe: what the uploaded file is named and where it goes.
 * Lives here, away from Android, so it can be tested. The part that talks to the network
 * (Bench.kt) is thin on purpose, because networking isn't testable in a JVM.
 *
 * Context (09/10/2026): the same probe Binspector has used since 08/12. It's a plain PUT
 * to nginx's WebDAV module: no service, no server code, no new port. The path carries a
 * secret in the middle of the URL, and nginx refuses any method other than PUT, so not
 * even whoever discovers the address can READ back what was uploaded.
 */
object Probe {

    const val BASE = "https://hooks.mygoll.com/sonda/fxjSuQ7VJfaa1ZOiSK0dI8jHgwI4/"

    /**
     * One file per upload, never overwritten, with the millisecond in the name: this is
     * what lets CC see the SEQUENCE of scans ("round 1 read 3 fields, round 2 had 4 more
     * show up") instead of just the final snapshot. The "preenche-" prefix separates it
     * from Binspector's dumps, which live in the same folder.
     */
    fun nome(quandoMs: Long): String = "preenche-$quandoMs.json"

    fun url(quandoMs: Long): String = BASE + nome(quandoMs)
}
