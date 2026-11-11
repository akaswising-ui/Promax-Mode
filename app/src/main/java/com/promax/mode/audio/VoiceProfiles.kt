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
    UNDERWATER(7, "Underwater");

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
