package de.davidgrieser.container.model

/**
 * A single web app that the container is allowed to display.
 *
 * @param name  Human-readable label shown in the menu.
 * @param iconUrl Absolute https URL of the app icon (optional).
 * @param url   Absolute https URL of the page to display. Navigation is locked
 *              to this URL's registered domain.
 */
data class AppEntry(
    val name: String,
    val iconUrl: String?,
    val url: String
)

/** The full remotely-controlled configuration. */
data class KioskConfig(
    val apps: List<AppEntry>
) {
    val isEmpty: Boolean get() = apps.isEmpty()
}
