package com.macedonga.visor.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macedonga.visor.R
import com.macedonga.visor.band.BandRecord
import com.macedonga.visor.band.Bands
import com.macedonga.visor.connection.GlassesService
import com.macedonga.visor.core.assistant.AssistantConfigStore
import com.macedonga.visor.core.assistant.ChatGptPlan
import com.macedonga.visor.core.assistant.Provider
import com.macedonga.visor.core.assistant.TtsPath
import com.macedonga.visor.core.assistant.mcp.AuthKind
import com.macedonga.visor.core.assistant.mcp.McpServer
import com.macedonga.visor.core.assistant.mcp.McpStore
import com.macedonga.visor.core.assistant.mcp.OAuthGrant
import com.macedonga.visor.core.assistant.mcp.Transport
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.GlassesLink
import com.macedonga.visor.core.connection.GlassesTarget
import com.macedonga.visor.core.connection.strings
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.core.identity.IdentityBundle
import com.macedonga.visor.core.identity.IdentityStore
import com.macedonga.visor.core.identity.readPrivateKeyPem
import com.macedonga.visor.core.media.Albums
import com.macedonga.visor.core.media.BroadcastCodec
import com.macedonga.visor.core.media.LiveOverlay
import com.macedonga.visor.core.media.LiveSettingsStore
import com.macedonga.visor.core.media.StreamQuality
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.core.settings.GlassesSettings
import com.macedonga.visor.core.settings.same
import com.macedonga.visor.dat.ToolkitStore
import com.macedonga.visor.gallery.AutoImport
import com.macedonga.visor.gallery.ImportChoice
import com.macedonga.visor.kit.protocol.Scope
import com.macedonga.visor.language.LanguageInstaller
import com.macedonga.visor.language.VOICE_KEY
import com.macedonga.visor.log.EventLog
import com.macedonga.visor.login.MetaLogin
import com.macedonga.visor.notify.NotifyApps
import com.macedonga.visor.notify.NotifyChoice
import com.macedonga.visor.outside.DeveloperMode
import com.macedonga.visor.outside.NetworkClient
import com.macedonga.visor.outside.NetworkClients
import com.macedonga.visor.pairs.Pairs
import com.macedonga.visor.phone.SpotifySearch
import com.macedonga.visor.update.Updates
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** A Visor backup file read from disk: either plain settings or a passphrase-encrypted envelope. */
sealed interface BackupFile {
    data class Plain(val data: JSONObject) : BackupFile
    data class Encrypted(val envelope: JSONObject, val savedAt: Long?) : BackupFile
}

/** Every setting in one file and back. Keys and credentials stay out unless explicitly encrypted with a passphrase. */
@Singleton
class Backups @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: GlassesSettings,
    private val connection: GlassesConnection,
    private val assistant: AssistantConfigStore,
    private val chatGpt: ChatGptPlan,
    private val notify: NotifyChoice,
    private val apps: NotifyApps,
    private val import: ImportChoice,
    private val albums: Albums,
    private val live: LiveSettingsStore,
    private val pins: Pins,
    private val mcp: McpStore,
    private val languages: LanguageInstaller,
    private val updates: Updates,
    private val identities: IdentityStore,
    private val metaLogin: MetaLogin,
    private val bands: Bands,
    private val toolkit: ToolkitStore,
    private val spotify: SpotifySearch,
    private val developer: DeveloperMode,
    private val networkClients: NetworkClients,
    private val pairs: Pairs,
    private val log: EventLog,
) {
    private val prefs = context.getSharedPreferences("backup", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _last = MutableStateFlow(prefs.getLong(LAST, 0L).takeIf { it > 0 })

    /** When a backup was last saved, if ever. */
    val last: StateFlow<Long?> = _last.asStateFlow()

    private val _restoring = MutableStateFlow<Pair<Int, Int>?>(null)

    /** The glasses' settings written and to write while a restore runs, null when none does. */
    val restoring: StateFlow<Pair<Int, Int>?> = _restoring.asStateFlow()

    private val _restored = MutableStateFlow<Result<Restored>?>(null)

    /** How the last restore of this run ended. */
    val restored: StateFlow<Result<Restored>?> = _restored.asStateFlow()

    /** The restore outcome: unwritten glasses settings, voice check, and whether credentials/identities came back. */
    data class Restored(
        val notWritten: Int,
        val voiceChecked: Boolean,
        val credentialsRestored: Boolean = false,
        val identitiesRestored: Int = 0,
    )

    fun build(includeKeys: Boolean = false): JSONObject {
        val state = settings.state.value
        val glasses = JSONObject()
        for ((key, value) in state.stored) {
            val info = settings.catalogue[key] ?: continue
            if (info.writable && state.changed(key, info)) glasses.put(key, value)
        }
        val a = assistant.config.value
        val l = live.settings.value
        val assistantJson = JSONObject()
            .put("enabled", a.enabled).put("provider", a.provider.name).put("model", a.model).put("linesModel", a.linesModel)
            .put("baseUrl", a.baseUrl).put("webSearch", a.webSearch).put("vision", a.vision).put("voicePhotos", a.voicePhotos)
            .put("glassesVoice", a.tts == TtsPath.GLASSES).put("quietAfterActions", a.quietAfterActions).put("speechLanguage", a.speechLanguage)
            .put("instructions", a.instructions).put("memoryMinutes", a.memoryMinutes)
            .put("answerWaitSeconds", a.answerWaitSeconds)
            // The speech service's key stays out unless explicitly encrypted, like the model's.
            .put("tts", a.tts.name).put("ttsBaseUrl", a.ttsBaseUrl).put("ttsModel", a.ttsModel).put("ttsVoice", a.ttsVoice)
        if (includeKeys) {
            assistantJson
                .put("apiKey", a.apiKey)
                .put("ttsApiKey", a.ttsApiKey)
                .put("chatGpt", a.chatGpt)
                .put("slots", assistant.exportSlots())
        }

        val liveJson = JSONObject()
            .put("manual", l.manual).put("quality", l.quality.name).put("bitrate", l.bitrate).put("frameRate", l.frameRate)
            .put("overlays", JSONArray(l.overlays.map { it.name })).put("replaySeconds", l.replaySeconds)
            .put("listen", l.listen).put("rtmpUrl", l.rtmpUrl).put("rtmpCodec", l.rtmpCodec.name).put("glassesHost", l.glassesHost)
        if (includeKeys) liveJson.put("rtmpKey", l.rtmpKey)

        val integrationsJson = JSONArray(
            mcp.servers.value.map {
                if (includeKeys) {
                    it.toJson()
                } else {
                    // The servers without their keys and sign-ins, which stay on this phone.
                    JSONObject().put("name", it.name).put("url", it.url).put("enabled", it.enabled).put("auth", it.auth.name)
                        .put("header", it.header).put("off", JSONArray(it.off.sorted())).put("clientId", it.clientId).put("redirect", it.redirect)
                }
            },
        )

        val root = JSONObject()
            .put("visor", 1)
            .put("saved", Instant.now().toString())
            .put("serial", connection.state.value.target?.serial ?: connection.state.value.info?.serial ?: "")
            .put("read", state.readAt?.let { Instant.ofEpochMilli(it).toString() } ?: "")
            // Unread this run, the glasses' values are not known: their part stays out.
            .putOpt("glasses", glasses.takeIf { state.readAt != null })
            .put("updates", updates.enabled.value)
            .put("assistant", assistantJson)
            .put("notifications", JSONObject().put("enabled", notify.enabled.value).put("onlyLocked", notify.onlyLocked.value).put("muted", JSONArray(apps.muted.value.sorted())))
            .put("import", import.mode.value.name)
            .put("albums", JSONObject().put("photos", albums.photos.value).put("videos", albums.videos.value).put("together", albums.together.value))
            .put("live", liveJson)
            .put("pins", JSONArray(pins.ids.value))
            .put("integrations", integrationsJson)

        if (includeKeys) root.put("credentials", buildCredentials())
        return root
    }

    private fun buildCredentials(): JSONObject {
        val b64 = Base64.getEncoder()
        val creds = JSONObject()

        val identityList = JSONArray()
        for (bundle in identities.all()) {
            identityList.put(
                JSONObject()
                    .put("private_key_pem", b64.encodeToString(bundle.privateKeyPem))
                    .put("glasses_address", bundle.glassesAddress)
                    .put("serial", bundle.serial)
                    .put("created_at", bundle.createdAt)
                    .put("provenance", bundle.provenance)
                    .put("state", bundle.state)
                    .apply { bundle.devicePublicKey?.let { put("device_public_key", b64.encodeToString(it)) } },
            )
        }
        creds.put("identities", identityList)
        identities.currentSerial()?.let { creds.put("currentSerial", it) }

        metaLogin.exportLogin()?.let { (frla, deviceId) ->
            creds.put("metaLogin", JSONObject().put("frla", frla).put("deviceId", deviceId))
        }

        val bandList = JSONArray()
        for (band in bands.all.value) {
            bandList.put(
                JSONObject()
                    .put("serial", band.serial)
                    .put("address", band.address)
                    .put("glasses", band.glasses ?: "")
                    .put("public_key", band.publicKey?.let { b64.encodeToString(it) } ?: "")
                    .put("key", band.key?.let { b64.encodeToString(it) } ?: ""),
            )
        }
        if (bandList.length() > 0) creds.put("bands", bandList)

        toolkit.exportState()?.let { creds.put("toolkit", it) }
        chatGpt.exportState()?.let { creds.put("chatGptPlan", it) }

        if (spotify.clientId.isNotEmpty() || spotify.clientSecret.isNotEmpty()) {
            creds.put("spotify", JSONObject().put("clientId", spotify.clientId).put("clientSecret", spotify.clientSecret))
        }

        creds.put(
            "developer",
            JSONObject()
                .put("on", developer.on.value)
                .put("token", developer.token.value)
                .put("tokenRequired", developer.tokenRequired.value),
        )

        val clients = networkClients.all.value
        if (networkClients.on.value || networkClients.lan.value || clients.isNotEmpty()) {
            val clientList = JSONArray()
            for (c in clients) {
                clientList.put(
                    JSONObject()
                        .put("name", c.name)
                        .put("hash", c.tokenHash)
                        .put("scopes", JSONArray(c.scopes.map { it.id }))
                        .put("webhook", c.webhook ?: "")
                        .put("topics", JSONArray(c.topics)),
                )
            }
            creds.put(
                "networkClients",
                JSONObject()
                    .put("on", networkClients.on.value)
                    .put("lan", networkClients.lan.value)
                    .put("clients", clientList),
            )
        }

        return creds
    }

    suspend fun save(uri: Uri) = withContext(Dispatchers.IO) {
        (context.contentResolver.openOutputStream(uri, "wt") ?: error("no stream")).use { it.write(build(includeKeys = false).toString(2).toByteArray()) }
        val now = System.currentTimeMillis()
        prefs.edit().putLong(LAST, now).apply()
        _last.value = now
    }

    /** Saves a full backup including keys and credentials, encrypted with [passphrase]. Clears [passphrase] before returning. */
    suspend fun saveEncrypted(uri: Uri, passphrase: CharArray) {
        try {
            val payload = withContext(Dispatchers.IO) { build(includeKeys = true) }
            val envelope = withContext(Dispatchers.Default) { BackupCrypto.encrypt(payload, passphrase) }
            withContext(Dispatchers.IO) {
                (context.contentResolver.openOutputStream(uri, "wt") ?: error("no stream")).use {
                    it.write(envelope.toString(2).toByteArray(Charsets.UTF_8))
                }
                val now = System.currentTimeMillis()
                prefs.edit().putLong(LAST, now).apply()
                _last.value = now
            }
            log.add("Backup: saved encrypted backup with keys")
        } finally {
            passphrase.fill('\u0000')
        }
    }

    suspend fun read(uri: Uri): BackupFile? = withContext(Dispatchers.IO) {
        val json = JSONObject((context.contentResolver.openInputStream(uri) ?: error("no stream")).use { it.readBytes().decodeToString() })
        if (!json.has("visor")) return@withContext null
        if (BackupCrypto.isEncrypted(json)) {
            val savedAt = runCatching { Instant.parse(json.optString("saved")).toEpochMilli() }.getOrNull()
            BackupFile.Encrypted(json, savedAt)
        } else {
            BackupFile.Plain(json)
        }
    }

    /** Decrypts an encrypted backup envelope with [passphrase]. Clears [passphrase] before returning. */
    suspend fun decrypt(envelope: JSONObject, passphrase: CharArray): JSONObject = withContext(Dispatchers.Default) {
        try {
            BackupCrypto.decrypt(envelope, passphrase)
        } finally {
            passphrase.fill('\u0000')
        }
    }

    /** Restores [backup] in Visor's own scope, so leaving the page does not stop it; one restore at a time. */
    fun startRestore(backup: JSONObject, encrypted: Boolean = false) {
        if (!_restoring.compareAndSet(null, 0 to 0)) return
        _restored.value = null
        scope.launch {
            try {
                _restored.value = Result.success(restore(backup, encrypted))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _restored.value = Result.failure(error)
            } finally {
                _restoring.value = null
            }
        }
    }

    /** Puts Visor's part back at once, and the glasses' part one write at a time; the glasses' part needs them connected. */
    private suspend fun restore(backup: JSONObject, encrypted: Boolean): Restored {
        backup.optJSONObject("assistant")?.let { o ->
            assistant.update {
                it.copy(
                    enabled = o.optBoolean("enabled", it.enabled),
                    provider = runCatching { Provider.valueOf(o.getString("provider")) }.getOrDefault(it.provider),
                    apiKey = if (encrypted && o.has("apiKey")) o.optString("apiKey", it.apiKey) else it.apiKey,
                    model = o.optString("model", it.model),
                    linesModel = o.optString("linesModel", it.linesModel),
                    baseUrl = o.optString("baseUrl", it.baseUrl),
                    webSearch = o.optBoolean("webSearch", it.webSearch),
                    vision = o.optBoolean("vision", it.vision),
                    voicePhotos = o.optBoolean("voicePhotos", it.voicePhotos),
                    // Older backups carry only the glassesVoice boolean.
                    tts = TtsPath.entries.firstOrNull { p -> p.name == o.optString("tts") }
                        ?: if (!o.has("glassesVoice")) it.tts else if (o.getBoolean("glassesVoice")) TtsPath.GLASSES else TtsPath.PHONE,
                    ttsBaseUrl = o.optString("ttsBaseUrl", it.ttsBaseUrl),
                    ttsApiKey = if (encrypted && o.has("ttsApiKey")) o.optString("ttsApiKey", it.ttsApiKey) else it.ttsApiKey,
                    ttsModel = o.optString("ttsModel", it.ttsModel),
                    ttsVoice = o.optString("ttsVoice", it.ttsVoice),
                    quietAfterActions = o.optBoolean("quietAfterActions", it.quietAfterActions),
                    speechLanguage = o.optString("speechLanguage", it.speechLanguage),
                    instructions = o.optString("instructions", it.instructions),
                    memoryMinutes = o.optInt("memoryMinutes", it.memoryMinutes),
                    answerWaitSeconds = o.optInt("answerWaitSeconds", it.answerWaitSeconds),
                    chatGpt = if (encrypted && o.has("chatGpt")) o.optString("chatGpt", it.chatGpt) else it.chatGpt,
                )
            }
            if (encrypted) o.optJSONObject("slots")?.let(assistant::importSlots)
        }
        backup.optJSONArray("integrations")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val url = o.optString("url").takeIf { it.isNotBlank() } ?: continue
                val existing = mcp.servers.value.firstOrNull { it.url == url }
                if (existing != null && !encrypted) continue
                val name = o.optString("name").ifBlank { url }
                val enabled = o.optBoolean("enabled", true)
                val auth = runCatching { AuthKind.valueOf(o.optString("auth")) }.getOrDefault(AuthKind.NONE)
                val header = o.optString("header")
                val off = o.optJSONArray("off")?.strings()?.toSet().orEmpty()
                val clientId = o.optString("clientId")
                val redirect = o.optString("redirect")
                if (!encrypted) {
                    mcp.add(McpServer(name = name, url = url, enabled = enabled, auth = auth, header = header, off = off, clientId = clientId, redirect = redirect))
                } else {
                    val secret = o.optString("secret")
                    val transport = runCatching { Transport.valueOf(o.optString("transport")) }.getOrDefault(Transport.AUTO)
                    val clientSecret = o.optString("clientSecret")
                    val oauth = o.optJSONObject("oauth")?.let(OAuthGrant::from)
                    if (existing != null) {
                        mcp.update(existing.id) { cur ->
                            cur.copy(
                                name = name, enabled = enabled, auth = auth, secret = secret, header = header, off = off,
                                transport = transport, clientId = clientId, clientSecret = clientSecret, redirect = redirect,
                                oauth = if (auth == AuthKind.OAUTH) (oauth ?: cur.oauth) else oauth,
                            )
                        }
                    } else {
                        mcp.add(
                            McpServer(
                                id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                                name = name, url = url, enabled = enabled, auth = auth, secret = secret, header = header, off = off,
                                transport = transport, clientId = clientId, clientSecret = clientSecret, redirect = redirect, oauth = oauth,
                            ),
                        )
                    }
                }
            }
        }
        backup.optJSONObject("notifications")?.let { o ->
            notify.set(o.optBoolean("enabled", true))
            notify.setOnlyLocked(o.optBoolean("onlyLocked", false))
            o.optJSONArray("muted")?.let { a -> apps.restore(a.strings().toSet()) }
        }
        backup.optString("import").takeIf { it.isNotEmpty() }?.let { m -> runCatching { AutoImport.valueOf(m) }.getOrNull()?.let(import::set) }
        backup.optJSONObject("albums")?.let { o ->
            o.optString("photos").takeIf { it.isNotEmpty() }?.let(albums::setPhotos)
            o.optString("videos").takeIf { it.isNotEmpty() }?.let(albums::setVideos)
            if (o.has("together")) albums.setTogether(o.optBoolean("together"))
        }
        backup.optJSONObject("live")?.let { o ->
            live.update {
                it.copy(
                    manual = o.optBoolean("manual", it.manual),
                    quality = runCatching { StreamQuality.valueOf(o.getString("quality")) }.getOrDefault(it.quality),
                    bitrate = o.optInt("bitrate", it.bitrate),
                    frameRate = o.optInt("frameRate", it.frameRate),
                    overlays = o.optJSONArray("overlays")?.let { a -> (0 until a.length()).mapNotNull { i -> runCatching { LiveOverlay.valueOf(a.getString(i)) }.getOrNull() }.toSet() } ?: it.overlays,
                    replaySeconds = o.optInt("replaySeconds", it.replaySeconds),
                    listen = o.optBoolean("listen", it.listen),
                    rtmpUrl = o.optString("rtmpUrl", it.rtmpUrl),
                    rtmpKey = if (encrypted && o.has("rtmpKey")) o.optString("rtmpKey", it.rtmpKey) else it.rtmpKey,
                    rtmpCodec = runCatching { BroadcastCodec.valueOf(o.getString("rtmpCodec")) }.getOrDefault(it.rtmpCodec),
                    glassesHost = o.optBoolean("glassesHost", it.glassesHost),
                )
            }
        }
        backup.optJSONArray("pins")?.let { a -> pins.set(a.strings()) }
        if (backup.has("updates") && backup.optBoolean("updates") != updates.enabled.value) updates.setEnabled(backup.optBoolean("updates"))

        // Only decrypted backups may restore keys and accounts; plaintext files never overwrite credentials.
        val (identitiesRestored, activeIdentityChanged) = if (encrypted) {
            restoreCredentials(backup.optJSONObject("credentials"))
        } else {
            0 to false
        }

        val glasses = backup.optJSONObject("glasses")
        var notWritten = 0
        var voiceChecked = true
        if (glasses != null) {
            // The voice last: a phone that cannot speak it, or glasses that keep theirs, stop the restore with a reason.
            val keys = glasses.keys().asSequence().filter { settings.catalogue[it]?.writable == true }.sortedBy { it == VOICE_KEY }.toList()
            for ((i, key) in keys.withIndex()) {
                _restoring.value = i to keys.size
                // Away, nothing more reaches the glasses.
                if (connection.state.value.link != LinkState.READY) {
                    notWritten += keys.size - i
                    break
                }
                val want = glasses.getString(key)
                if (same(settings.catalogue[key]?.kind, settings.value(key), want)) continue
                if (key == VOICE_KEY) {
                    voiceChecked = languages.checkPhone(want)
                    languages.switchVoice(want)
                } else {
                    if (!settings.write(key, want)) notWritten++
                    delay(WRITE_GAP_MS)
                }
            }
            _restoring.value = keys.size to keys.size
        }

        // Start or reload the glasses session after restoring identities so the new keys take effect without dropping an unchanged live link mid-write.
        if (identitiesRestored > 0) {
            val chosen = withContext(Dispatchers.IO) { runCatching { identities.chosen() }.getOrNull() }
            if (chosen != null && (!connection.running.value || connection.state.value.link != LinkState.READY || activeIdentityChanged)) {
                GlassesService.reload(context, GlassesTarget(GlassesLink.Bluetooth(chosen.glassesAddress), chosen.serial))
            }
        }

        return Restored(notWritten, voiceChecked, credentialsRestored = encrypted, identitiesRestored = identitiesRestored)
    }

    /** Restores keystore-sealed identities, logins, bands, toolkit keys and app tokens; returns (identities restored, active key changed). */
    private suspend fun restoreCredentials(creds: JSONObject?): Pair<Int, Boolean> = withContext(Dispatchers.IO) {
        if (creds == null) return@withContext 0 to false
        val b64 = Base64.getDecoder()

        val beforeSerial = connection.state.value.target?.serial ?: runCatching { identities.currentSerial() }.getOrNull()
        val beforeBundle = beforeSerial?.let { s -> runCatching { identities.load(s) }.getOrNull() }

        val parsedBundles = mutableListOf<IdentityBundle>()
        creds.optJSONArray("identities")?.let { list ->
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                val serial = o.optString("serial").takeIf { it.isNotBlank() } ?: continue
                val address = o.optString("glasses_address").takeIf { it.isNotBlank() } ?: continue
                val pemB64 = o.optString("private_key_pem").takeIf { it.isNotBlank() } ?: continue
                val pem = b64.decode(pemB64)
                // Verify the PEM parses as a valid EC owner key before writing it to the store.
                readPrivateKeyPem(pem)
                val deviceKey = o.optString("device_public_key").takeIf { it.isNotBlank() }?.let { b64.decode(it) }
                parsedBundles += IdentityBundle(
                    privateKeyPem = pem,
                    glassesAddress = address,
                    serial = serial,
                    createdAt = o.optString("created_at"),
                    provenance = o.optString("provenance"),
                    state = o.optString("state"),
                    devicePublicKey = deviceKey,
                )
            }
        }
        val currentSerial = creds.optString("currentSerial").takeIf { it.isNotBlank() }
        if (parsedBundles.isNotEmpty()) {
            identities.importAll(parsedBundles, currentSerial)
            pairs.refresh()
        }
        val afterBundle = runCatching { identities.chosen() }.getOrNull()
        val activeChanged = parsedBundles.isNotEmpty() && (beforeBundle == null || beforeBundle != afterBundle)

        creds.optJSONObject("metaLogin")?.let { m ->
            val frla = m.optString("frla")
            if (frla.isNotBlank()) metaLogin.restoreLogin(frla, m.optString("deviceId").takeIf { it.isNotBlank() })
        }

        creds.optJSONArray("bands")?.let { list ->
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                val serial = o.optString("serial").takeIf { it.isNotBlank() } ?: continue
                val address = o.optString("address").takeIf { it.isNotBlank() } ?: continue
                bands.save(
                    BandRecord(
                        serial = serial,
                        address = address,
                        publicKey = o.optString("public_key").takeIf { it.isNotBlank() }?.let { b64.decode(it) },
                        glasses = o.optString("glasses").takeIf { it.isNotBlank() },
                        key = o.optString("key").takeIf { it.isNotBlank() }?.let { b64.decode(it) },
                    ),
                )
            }
        }

        creds.optJSONObject("toolkit")?.let { toolkit.importState(it) }
        creds.optJSONObject("chatGptPlan")?.let { chatGpt.importState(it) }

        creds.optJSONObject("spotify")?.let { s ->
            if (s.has("clientId")) spotify.clientId = s.optString("clientId")
            if (s.has("clientSecret")) spotify.clientSecret = s.optString("clientSecret")
        }

        creds.optJSONObject("developer")?.let { d ->
            val token = d.optString("token")
            if (token.isNotBlank()) developer.restoreToken(token)
            if (d.has("tokenRequired")) developer.requireToken(d.optBoolean("tokenRequired"))
            if (d.has("on")) developer.set(d.optBoolean("on"))
        }

        creds.optJSONObject("networkClients")?.let { n ->
            val clients = mutableListOf<NetworkClient>()
            n.optJSONArray("clients")?.let { list ->
                for (i in 0 until list.length()) {
                    val c = list.optJSONObject(i) ?: continue
                    val name = c.optString("name").takeIf { it.isNotBlank() } ?: continue
                    val hash = c.optString("hash").takeIf { it.isNotBlank() } ?: continue
                    val scopes = c.optJSONArray("scopes")?.strings()?.mapTo(LinkedHashSet(), ::Scope).orEmpty()
                    val topics = c.optJSONArray("topics")?.strings().orEmpty()
                    clients += NetworkClient(name, scopes, hash, c.optString("webhook").ifEmpty { null }, topics)
                }
            }
            networkClients.restore(n.optBoolean("on"), n.optBoolean("lan"), clients)
        }

        log.add("Backup: restored credentials (${parsedBundles.size} pairs)")
        parsedBundles.size to activeChanged
    }

    private companion object {
        const val LAST = "last"
        const val WRITE_GAP_MS = 250L
    }
}

@HiltViewModel
class BackupModel @Inject constructor(val backups: Backups, val connection: GlassesConnection, val settings: GlassesSettings) : ViewModel()

/** A backup waiting for the person's confirmation before restore starts. */
private data class PendingRestore(val data: JSONObject, val encrypted: Boolean)

/** Save every setting to a file, or put a saved file back. */
@Composable
fun BackupScreen(
    onboarding: Boolean = false,
    onContinue: () -> Unit = {},
    model: BackupModel = hiltViewModel(),
) {
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val glasses by model.connection.state.collectAsStateWithLifecycle()
    val settings by model.settings.state.collectAsStateWithLifecycle()
    val last by model.backups.last.collectAsStateWithLifecycle()
    val restoring by model.backups.restoring.collectAsStateWithLifecycle()
    val restored by model.backups.restored.collectAsStateWithLifecycle()
    // Off every time this page opens so keys are never exported by accident.
    var includeKeys by remember { mutableStateOf(false) }
    var showKeysWarning by remember { mutableStateOf(false) }
    var askingCreatePassphrase by remember { mutableStateOf(false) }
    var pendingSavePassphrase by remember { mutableStateOf<CharArray?>(null) }
    var locked by remember { mutableStateOf<BackupFile.Encrypted?>(null) }
    var pending by remember { mutableStateOf<PendingRestore?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val ready = glasses.link == LinkState.READY
    val busy = restoring != null
    val steps = restoring?.takeIf { it.second > 0 }?.let { (done, total) -> stringResource(R.string.backup_restoring, done, total) }
    // The last restore's outcome, until this page says something newer.
    val outcome = restored?.takeIf { !busy && problem == null }
    val done = outcome?.getOrNull()

    DisposableEffect(Unit) {
        onDispose {
            pendingSavePassphrase?.fill('\u0000')
            pendingSavePassphrase = null
        }
    }

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val passphrase = pendingSavePassphrase
        pendingSavePassphrase = null
        if (uri == null) {
            passphrase?.fill('\u0000')
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            saving = true
            try {
                if (passphrase != null) {
                    model.backups.saveEncrypted(uri, passphrase)
                    includeKeys = false
                } else {
                    model.backups.save(uri)
                }
                problem = null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                problem = resources.getString(R.string.backup_save_failed)
            } finally {
                passphrase?.fill('\u0000')
                saving = false
            }
        }
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val file = try {
                model.backups.read(uri)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                null
            }
            when (file) {
                is BackupFile.Plain -> {
                    problem = null
                    pending = PendingRestore(file.data, encrypted = false)
                }
                is BackupFile.Encrypted -> {
                    problem = null
                    locked = file
                }
                null -> problem = resources.getString(R.string.backup_not_ours)
            }
        }
    }

    SettingsPage(stringResource(R.string.backup_title), light = 0.35f) {
        item(key = "save") {
            Column {
                Rows(
                    {
                        ToggleRow(
                            stringResource(R.string.backup_include_keys),
                            on = includeKeys,
                            onChange = { on ->
                                if (on) showKeysWarning = true else includeKeys = false
                            },
                            sub = stringResource(if (includeKeys) R.string.backup_include_keys_on else R.string.backup_include_keys_off),
                            locked = saving,
                        )
                    },
                    {
                        ValueRow(
                            stringResource(R.string.backup_save),
                            when {
                                includeKeys -> stringResource(R.string.backup_encrypted)
                                settings.readAt == null -> stringResource(R.string.backup_visor_only)
                                else -> null
                            },
                            onClick = if (saving) null else ({
                                if (includeKeys) {
                                    askingCreatePassphrase = true
                                } else {
                                    create.launch("visor-settings-${LocalDate.now()}.json")
                                }
                            }),
                            busy = saving,
                            sub = last?.let { stringResource(R.string.backup_sum_last, dateWords(it)) } ?: stringResource(R.string.backup_sum_never),
                        )
                    },
                    {
                        ValueRow(
                            stringResource(R.string.backup_restore), null,
                            onClick = if (busy) null else ({ openFile.launch(arrayOf("application/json", "text/plain", "*/*")) }),
                            sub = when {
                                busy -> steps
                                done?.credentialsRestored == true -> stringResource(R.string.backup_restored_with_keys)
                                done?.notWritten == 0 -> stringResource(R.string.backup_restored)
                                else -> null
                            },
                            busy = busy,
                        )
                    },
                )
                if (includeKeys) {
                    Footnote(stringResource(R.string.backup_keys_warning_inline), color = Visor.colors.signal)
                }
            }
        }
        item(key = "state") {
            Column {
                problem?.let { Footnote(it, color = Visor.colors.signal) }
                if (done != null && done.notWritten > 0) Footnote(pluralStringResource(R.plurals.backup_settings_not_restored, done.notWritten, done.notWritten), color = Visor.colors.signal)
                if (done != null && !done.voiceChecked) Footnote(stringResource(R.string.language_recognition_unknown), color = Visor.colors.signal)
                if (outcome?.isFailure == true) Footnote(stringResource(R.string.backup_restore_failed), color = Visor.colors.signal)
                if (done != null && done.identitiesRestored > 0 && !ready) {
                    Footnote(stringResource(R.string.backup_restored_pair_hint))
                }
                if (onboarding && done != null && done.identitiesRestored > 0) {
                    Spacer(Modifier.height(16.dp))
                    WidePill(stringResource(R.string.onboard_continue), onContinue, Modifier.fillMaxWidth().padding(horizontal = SIDE))
                }
            }
        }
    }

    if (showKeysWarning) {
        KeysWarningSheet(
            onConfirm = {
                showKeysWarning = false
                includeKeys = true
            },
            onDismiss = { showKeysWarning = false },
        )
    }

    if (askingCreatePassphrase) {
        PassphraseCreateSheet(
            onConfirm = { passphrase ->
                askingCreatePassphrase = false
                pendingSavePassphrase?.fill('\u0000')
                pendingSavePassphrase = passphrase
                create.launch("visor-backup-${LocalDate.now()}.json")
            },
            onDismiss = { askingCreatePassphrase = false },
        )
    }

    locked?.let { file ->
        PassphraseUnlockSheet(
            savedAt = file.savedAt,
            onUnlock = { passphrase -> model.backups.decrypt(file.envelope, passphrase) },
            onUnlocked = { data ->
                locked = null
                pending = PendingRestore(data, encrypted = true)
            },
            onDismiss = { locked = null },
        )
    }

    pending?.let { item ->
        val backup = item.data
        val count = backup.optJSONObject("glasses")?.length() ?: 0
        val pairsCount = if (item.encrypted) backup.optJSONObject("credentials")?.optJSONArray("identities")?.length() ?: 0 else 0
        val savedAt = runCatching { Instant.parse(backup.optString("saved")).toEpochMilli() }.getOrNull()
        val text = if (!item.encrypted) {
            when {
                count == 0 -> null
                ready -> stringResource(R.string.backup_restore_text, count)
                else -> stringResource(R.string.backup_restore_away, count)
            }
        } else {
            val parts = mutableListOf<String>()
            parts += if (pairsCount > 0) {
                pluralStringResource(R.plurals.backup_restore_keys_pairs, pairsCount, pairsCount)
            } else {
                stringResource(R.string.backup_restore_keys_only)
            }
            if (count > 0) {
                parts += if (ready) stringResource(R.string.backup_restore_text, count) else stringResource(R.string.backup_restore_away, count)
            }
            parts.joinToString("\n\n")
        }
        ConfirmSheet(
            savedAt?.let { stringResource(R.string.backup_restore_title, dateWords(it)) } ?: stringResource(R.string.backup_restore),
            text,
            stringResource(R.string.backup_restore),
            onConfirm = {
                problem = null
                model.backups.startRestore(backup, encrypted = item.encrypted)
            },
            onDismiss = { pending = null },
        )
    }
}

/** Warning interstitial shown before enabling credential export. */
@Composable
private fun KeysWarningSheet(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    VisorSheet(onDismiss) {
        SheetBody {
            SheetTitle(stringResource(R.string.backup_keys_warn_title), stringResource(R.string.backup_keys_warn_intro))
            Column(Modifier.padding(horizontal = SIDE).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.backup_keys_warn_items), style = Visor.type.body, color = Visor.colors.textPrimary)
                Text(stringResource(R.string.backup_keys_warn_risk), style = Visor.type.body, color = Visor.colors.signal)
            }
        }
        SheetAnswers(
            answer = stringResource(R.string.backup_keys_warn_confirm),
            onAnswer = onConfirm,
            back = stringResource(R.string.cancel),
            onBack = onDismiss,
        )
    }
}

/** Prompts for a new passphrase and confirmation before encrypting a backup with credentials. */
@Composable
private fun PassphraseCreateSheet(onConfirm: (CharArray) -> Unit, onDismiss: () -> Unit) {
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    val tooShort = passphrase.isNotEmpty() && passphrase.length < BackupCrypto.MIN_PASSPHRASE_LENGTH
    val mismatch = confirm.isNotEmpty() && passphrase != confirm
    val valid = passphrase.length >= BackupCrypto.MIN_PASSPHRASE_LENGTH && passphrase == confirm

    VisorSheet(onDismiss) {
        SheetBody {
            SheetTitle(stringResource(R.string.backup_encrypt_title), stringResource(R.string.backup_encrypt_text))
            SheetField(
                value = passphrase,
                onValue = { passphrase = it },
                hint = stringResource(R.string.backup_passphrase_hint, BackupCrypto.MIN_PASSPHRASE_LENGTH),
                secret = !reveal,
                keyboard = KeyboardType.Password,
                focus = true,
            )
            Spacer(Modifier.height(10.dp))
            SheetField(
                value = confirm,
                onValue = { confirm = it },
                hint = stringResource(R.string.backup_passphrase_confirm_hint),
                secret = !reveal,
                keyboard = KeyboardType.Password,
            )
            if (tooShort) {
                Footnote(stringResource(R.string.backup_passphrase_short, BackupCrypto.MIN_PASSPHRASE_LENGTH), color = Visor.colors.signal)
            } else if (mismatch) {
                Footnote(stringResource(R.string.backup_passphrase_mismatch), color = Visor.colors.signal)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = SIDE, vertical = 10.dp)) {
                ActionPill(
                    stringResource(if (reveal) R.string.backup_hide_passphrase else R.string.backup_show_passphrase),
                    onClick = { reveal = !reveal },
                    quiet = true,
                )
            }
        }
        SheetAnswers(
            answer = stringResource(R.string.settings_save),
            onAnswer = { onConfirm(passphrase.toCharArray()) },
            back = stringResource(R.string.cancel),
            onBack = onDismiss,
            enabled = valid,
        )
    }
}

/** Prompts for the passphrase to unlock an encrypted backup file. */
@Composable
private fun PassphraseUnlockSheet(
    savedAt: Long?,
    onUnlock: suspend (CharArray) -> JSONObject,
    onUnlocked: (JSONObject) -> Unit,
    onDismiss: () -> Unit,
) {
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var passphrase by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var unlocking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    VisorSheet(onDismiss = { if (!unlocking) onDismiss() }) {
        SheetBody {
            SheetTitle(
                stringResource(R.string.backup_decrypt_title),
                savedAt?.let { stringResource(R.string.backup_decrypt_text_dated, dateWords(it)) } ?: stringResource(R.string.backup_decrypt_text),
            )
            SheetField(
                value = passphrase,
                onValue = {
                    passphrase = it
                    error = null
                },
                hint = stringResource(R.string.backup_passphrase_enter_hint),
                secret = !reveal,
                keyboard = KeyboardType.Password,
                focus = true,
            )
            error?.let { Footnote(it, color = Visor.colors.signal) }
            Row(Modifier.fillMaxWidth().padding(horizontal = SIDE, vertical = 10.dp)) {
                ActionPill(
                    stringResource(if (reveal) R.string.backup_hide_passphrase else R.string.backup_show_passphrase),
                    onClick = { reveal = !reveal },
                    quiet = true,
                    enabled = !unlocking,
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = SIDE).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WidePill(stringResource(R.string.cancel), onDismiss, Modifier.weight(1f), quiet = true, enabled = !unlocking)
            WidePill(
                stringResource(R.string.backup_unlock),
                onClick = {
                    scope.launch {
                        unlocking = true
                        error = null
                        try {
                            val data = onUnlock(passphrase.toCharArray())
                            onUnlocked(data)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (wrong: WrongPassphraseException) {
                            error = resources.getString(R.string.backup_wrong_passphrase)
                        } catch (invalid: Exception) {
                            error = resources.getString(R.string.backup_not_ours)
                        } finally {
                            unlocking = false
                        }
                    }
                },
                Modifier.weight(1f),
                enabled = passphrase.isNotEmpty(),
                busy = unlocking,
            )
        }
    }
}
