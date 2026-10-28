package com.example.agenticcar.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Facts about the car that OBD-II cannot report but some estimates need. */
@Singleton
class VehicleSettings @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _engineDisplacementL = MutableStateFlow(prefs.getFloat(KEY_DISPLACEMENT, 0f).takeIf { it > 0f }?.toDouble())

    /** Engine size in litres, used for the speed-density fuel estimate; null when not set. */
    val engineDisplacementL: StateFlow<Double?> = _engineDisplacementL.asStateFlow()

    fun setEngineDisplacementL(litres: Double?) {
        val valid = litres?.takeIf { it in MIN_L..MAX_L }
        prefs.edit().apply { if (valid == null) remove(KEY_DISPLACEMENT) else putFloat(KEY_DISPLACEMENT, valid.toFloat()) }.apply()
        _engineDisplacementL.value = valid
    }

    companion object {
        const val MIN_L = 0.5
        const val MAX_L = 8.0
        private const val PREFS = "vehicle"
        private const val KEY_DISPLACEMENT = "engine_displacement_l"
    }
}
