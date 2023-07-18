package org.breezyweather.common.source

import android.content.Context
import org.breezyweather.common.preference.Preference

/**
 * Implement this if you need a preference screen
 */
interface ConfigurableSource : Source {

    fun getPreferences(context: Context): List<Preference>
}