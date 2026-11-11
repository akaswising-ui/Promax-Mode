package com.promax.mode.audio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class VoiceEffect(val id: Int, val title: String) {
    CLEAN(0, "Clean"),
    ROBOT(1, "Robot"),
    ALIEN(2, "Alien"),
    MONSTER(3, "Monster"),
    ECHO(4, "Echo"),
    CHILD(5, "Child"),
    OLD_MAN(6, "Old man"),
    UNDERWATER(7, "Underwater"),
    HELIUM(8, "Helium"),
    DEMON(9, "Demon");

    companion object {
        fun fromId(id: Int): VoiceEffect = entries.firstOrNull { it.id == id } ?: CLEAN
    }
}

data class VoiceProfile(
    val name: String,
    val effect: VoiceEffect,
    val pitchSemitones: Float,
    val formantSemitones: Float,
    val noiseGateDb: Float,
    val systemNoiseSuppression: Boolean
)

object VoiceProfileStore {
    private const val PREFS = "promax_voice_profiles"
    private const val KEY_PROFILES = "profiles"

    fun list(context: Context): List<VoiceProfile> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PROFILES, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    add(
                        VoiceProfile(
                            name = item.optString("name", "Voice"),
                            effect = VoiceEffect.fromId(item.optInt("effect", 0)),
                            pitchSemitones = item.optDouble("pitch", 0.0).toFloat(),
                            formantSemitones = item.optDouble("formant", 0.0).toFloat(),
                            noiseGateDb = item.optDouble("gate", -80.0).toFloat(),
                            systemNoiseSuppression = item.optBoolean("ns", true)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, profile: VoiceProfile) {
        val profiles = list(context).filterNot { it.name.equals(profile.name, ignoreCase = true) }
            .toMutableList()
        profiles.add(0, profile)
        val array = JSONArray()
        profiles.take(30).forEach { item ->
            array.put(
                JSONObject()
                    .put("name", item.name)
                    .put("effect", item.effect.id)
                    .put("pitch", item.pitchSemitones.toDouble())
                    .put("formant", item.formantSemitones.toDouble())
                    .put("gate", item.noiseGateDb.toDouble())
                    .put("ns", item.systemNoiseSuppression)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PROFILES, array.toString()).apply()
    }

    fun delete(context: Context, name: String) {
        val remaining = list(context).filterNot { it.name == name }
        val array = JSONArray()
        remaining.forEach { item ->
            array.put(
                JSONObject()
                    .put("name", item.name)
                    .put("effect", item.effect.id)
                    .put("pitch", item.pitchSemitones.toDouble())
                    .put("formant", item.formantSemitones.toDouble())
                    .put("gate", item.noiseGateDb.toDouble())
                    .put("ns", item.systemNoiseSuppression)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PROFILES, array.toString()).apply()
    }
}


/** Settings shared by the floating controller and the full Voice Studio screen. */
data class VoiceControlSettings(
    val effect: VoiceEffect = VoiceEffect.ROBOT,
    val pitchSemitones: Float = 0f,
    val formantSemitones: Float = 0f,
    val noiseGateDb: Float = -80f,
    val systemNoiseSuppression: Boolean = true,
    val enabled: Boolean = true
)

object VoiceControlStore {
    private const val PREFS = "promax_voice_controls"

    fun load(context: Context): VoiceControlSettings {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return VoiceControlSettings(
            effect = VoiceEffect.fromId(prefs.getInt("effect", VoiceEffect.ROBOT.id)),
            pitchSemitones = prefs.getFloat("pitch", 0f).coerceIn(-12f, 12f),
            formantSemitones = prefs.getFloat("formant", 0f).coerceIn(-8f, 8f),
            noiseGateDb = prefs.getFloat("gate", -80f).coerceIn(-80f, -20f),
            systemNoiseSuppression = prefs.getBoolean("noise_suppression", true),
            enabled = prefs.getBoolean("voice_enabled", true)
        )
    }

    fun save(context: Context, settings: VoiceControlSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("effect", settings.effect.id)
            .putFloat("pitch", settings.pitchSemitones.coerceIn(-12f, 12f))
            .putFloat("formant", settings.formantSemitones.coerceIn(-8f, 8f))
            .putFloat("gate", settings.noiseGateDb.coerceIn(-80f, -20f))
            .putBoolean("noise_suppression", settings.systemNoiseSuppression)
            .putBoolean("voice_enabled", settings.enabled)
            .apply()
    }

    fun withPreset(base: VoiceControlSettings, effect: VoiceEffect): VoiceControlSettings {
        val values = when (effect) {
            VoiceEffect.CHILD -> 5f to 3.5f
            VoiceEffect.OLD_MAN -> -4.5f to -2.5f
            VoiceEffect.UNDERWATER -> 0f to -1.5f
            VoiceEffect.MONSTER -> -3f to -3f
            VoiceEffect.HELIUM -> 7.5f to 4.5f
            VoiceEffect.DEMON -> -7f to -5.5f
            else -> 0f to 0f
        }
        return base.copy(effect = effect, pitchSemitones = values.first, formantSemitones = values.second)
    }
}
