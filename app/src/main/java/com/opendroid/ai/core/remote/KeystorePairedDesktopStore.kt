package com.opendroid.ai.core.remote

import com.opendroid.ai.core.security.SecretBytesStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * Persists paired desktops in the Keystore-backed [SecretBytesStore].
 *
 * Only the pairing secret is sensitive; the host, port and names are stored beside it as JSON in
 * the same encrypted record, so a single read returns everything needed to reconnect. The secret is
 * never written to shared preferences or logs.
 */
class KeystorePairedDesktopStore(
    private val secrets: SecretBytesStore,
) : PairedDesktopStore {

    override fun load(): List<PairedDesktop> {
        val raw = secrets.read(KEY) ?: return emptyList()
        return try {
            val array = JSONArray(String(raw, Charsets.UTF_8))
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val secret = obj.optString("secret", "")
                if (secret.isEmpty()) return@mapNotNull null
                PairedDesktop(
                    host = obj.getString("host"),
                    port = obj.getInt("port"),
                    deviceId = obj.getString("deviceId"),
                    serverName = obj.optString("serverName", obj.getString("host")),
                    secret = Base64.getDecoder().decode(secret),
                )
            }
        } catch (_: Exception) {
            // A corrupt record is dropped rather than crashing the screen; the user can pair again.
            emptyList()
        }
    }

    override fun save(desktop: PairedDesktop) {
        val updated = load().filterNot { it.deviceId == desktop.deviceId } + desktop
        persist(updated)
    }

    override fun remove(deviceId: String) {
        persist(load().filterNot { it.deviceId == deviceId })
    }

    private fun persist(desktops: List<PairedDesktop>) {
        val array = JSONArray()
        for (desktop in desktops) {
            array.put(
                JSONObject()
                    .put("host", desktop.host)
                    .put("port", desktop.port)
                    .put("deviceId", desktop.deviceId)
                    .put("serverName", desktop.serverName)
                    .put("secret", Base64.getEncoder().encodeToString(desktop.secret)),
            )
        }
        secrets.write(KEY, array.toString().toByteArray(Charsets.UTF_8))
    }

    private companion object {
        const val KEY = "remote_desktop_pairings_v1"
    }
}
