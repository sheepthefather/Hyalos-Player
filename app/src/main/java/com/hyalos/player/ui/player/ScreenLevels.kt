package com.hyalos.player.ui.player

/**
 * The brightness the player's gesture was last set to.
 *
 * Application-scoped (it lives in `AppContainer`) rather than owned by the
 * player's ViewModel, because that one is **per film**: a `Route.Play` entry
 * gets a ViewModel, and leaving the screen takes it with it. What was asked for
 * is a brightness that survives going from one film to the next — set it once
 * while watching an episode, and the next one should not need it again.
 *
 * It is deliberately not a setting. It is not written to disk, and it does not
 * outlive the app: a brightness chosen for one evening's film is not a
 * preference about how someone likes their phone.
 *
 * Plain mutable state rather than a `StateFlow`, because nothing observes it —
 * [PlayerScreen] reads it when it is composed and writes it when the gesture
 * moves. Anything watching it for changes would only be watching for its own
 * writes.
 */
class ScreenLevels {

    /**
     * `0..1`, or `null` for whatever the system is set to.
     *
     * Null is the honest starting state: the player has no brightness of its
     * own until someone asks for one, and the window says so with `-1f`.
     */
    var brightness: Float? = null
}
