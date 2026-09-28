package app.lawnchair.predictions

/**
 * Decides which predicted apps may fill empty dock spots.
 *
 * Pixel Launcher lets people turn off suggestions on the Home screen so empty dock spots stay
 * empty. Turning suggestions off hides them without touching the predictor or the hybrid-hotseat
 * migration backup, so switching back on shows the latest predictions immediately.
 */
object DockSuggestionsPolicy {

    /** Predictions the dock should show for the current setting. */
    @JvmStatic
    fun <T> visibleSuggestions(enabled: Boolean, predictions: List<T>): List<T> =
        if (enabled) predictions else emptyList()

    /**
     * Upstream Launcher3 restores its hybrid-hotseat migration backup when the predictor stops
     * sending results. Hiding suggestions through the setting must never trigger that restore,
     * so the decision depends only on what the predictor actually sent.
     */
    @JvmStatic
    fun shouldRestoreMigrationBackup(predictions: List<*>): Boolean = predictions.isEmpty()
}
