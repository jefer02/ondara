package app.ondara.core.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.ondara.core.domain.AccountRepository
import app.ondara.core.domain.SettingsRepository
import app.ondara.core.model.AccountsState
import app.ondara.core.model.SpotifyAccount
import app.ondara.core.model.YouTubeAccount
import app.ondara.source.innertube.InnerTubeClient
import app.ondara.source.spotify.PkceAuthProvider
import app.ondara.source.spotify.SpotifyAuthProvider
import app.ondara.source.spotify.SpotifyClient
import app.ondara.source.spotify.SpotifyTokenStore
import app.ondara.source.spotify.WebCookieAuthProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Almacén cifrado de secretos (cookies de sesión y tokens): un mapa clave → valor serializado en
 * JSON y cifrado con **AES-256-GCM**, con la clave guardada en el **Android Keystore** (no exportable).
 *
 * Si el Keystore no está disponible (tests en JVM), los secretos viven solo en memoria: nunca se
 * escribe nada en claro en disco. Lecturas O(1) tras la primera carga; cada escritura reescribe el archivo.
 */
@Singleton
class SecretStore @Inject constructor(@ApplicationContext context: Context) : SpotifyTokenStore {
    private val file = File(context.filesDir, "secrets/vault.bin")
    private val mutex = Mutex()
    private var cache: MutableMap<String, String>? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    override suspend fun load(key: String): String? = mutex.withLock { entries()[key] }

    override suspend fun save(key: String, value: String?) = mutex.withLock {
        val map = entries()
        if (value == null) map.remove(key) else map[key] = value
        persist(map)
    }

    private suspend fun entries(): MutableMap<String, String> = cache ?: withContext(Dispatchers.IO) {
        val loaded = runCatching {
            val key = key() ?: return@runCatching null
            if (!file.exists()) return@runCatching null
            val bytes = file.readBytes()
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, IV_SIZE)) }
            json.decodeFromString(serializer, cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE).decodeToString())
        }.getOrNull()
        HashMap(loaded.orEmpty()).also { cache = it }
    }

    private suspend fun persist(map: Map<String, String>) = withContext(Dispatchers.IO) {
        runCatching {
            val key = key() ?: return@runCatching
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
            val body = cipher.doFinal(json.encodeToString(serializer, map).toByteArray())
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeBytes(cipher.iv + body)
            if (!tmp.renameTo(file)) {
                file.writeBytes(tmp.readBytes())
                tmp.delete()
            }
        }
        Unit
    }

    private fun key(): SecretKey? = runCatching {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }.getOrNull()

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "ondara_secrets_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}

@Serializable
internal data class StoredYouTubeAccount(val account: YouTubeAccount, val cookie: String)

@Serializable
internal data class AccountsVault(
    val youtube: List<StoredYouTubeAccount> = emptyList(),
    val activeId: String? = null,
    val spotify: SpotifyAccount? = null,
)

/**
 * Estado en memoria de las cuentas, respaldado por [SecretStore]. Es la pieza que lee el cliente de
 * InnerTube en cada petición ([activeCookie]) y no depende de él, lo que evita un ciclo de inyección.
 */
@Singleton
class AccountStore @Inject constructor(private val secrets: SecretStore, @ApplicationScope private val scope: CoroutineScope) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _vault = MutableStateFlow(AccountsVault())
    private val _state = MutableStateFlow(AccountsState())
    val state: StateFlow<AccountsState> = _state.asStateFlow()
    private val loaded = MutableStateFlow(false)

    /** Cookie de la cuenta de YouTube activa, o vacío. Lectura O(n) sobre las cuentas (≤ unas pocas). */
    val activeCookie: String
        get() = _vault.value.let { v -> v.youtube.firstOrNull { it.account.id == v.activeId }?.cookie.orEmpty() }

    init {
        scope.launch {
            secrets.load(KEY)?.let { text -> runCatching { json.decodeFromString(AccountsVault.serializer(), text) }.getOrNull() }?.let(::publish)
            loaded.value = true
        }
    }

    internal suspend fun awaitLoaded() {
        loaded.first { it }
    }

    internal val vault: AccountsVault get() = _vault.value

    internal suspend fun update(transform: (AccountsVault) -> AccountsVault) {
        awaitLoaded()
        val next = transform(_vault.value)
        publish(next)
        secrets.save(KEY, json.encodeToString(AccountsVault.serializer(), next))
    }

    private fun publish(vault: AccountsVault) {
        _vault.value = vault
        _state.value = AccountsState(vault.youtube.map { it.account }, vault.activeId, vault.spotify)
    }

    private companion object {
        const val KEY = "accounts"
    }
}

/** Elige la estrategia de autenticación de Spotify según la cuenta conectada (patrón Strategy). */
@Singleton
class SpotifySession @Inject constructor(
    private val store: AccountStore,
    val pkce: PkceAuthProvider,
    val webCookie: WebCookieAuthProvider,
) {
    val provider: SpotifyAuthProvider get() = if (store.vault.spotify?.experimental == true) webCookie else pkce
}

@Singleton
class AccountRepositoryImpl @Inject constructor(
    private val store: AccountStore,
    private val innerTube: InnerTubeClient,
    private val session: SpotifySession,
    private val spotify: SpotifyClient,
    private val settings: SettingsRepository,
    private val spotifyData: SpotifyLocalData,
    @ApplicationScope scope: CoroutineScope,
) : AccountRepository {
    override val accounts: StateFlow<AccountsState> = store.state

    init {
        // Migración v1 → v2: la cookie vivía en claro en los ajustes; pasa al almacén cifrado.
        scope.launch {
            store.awaitLoaded()
            val legacy = settings.settings.first().youtubeCookie
            if (legacy.isNotBlank()) {
                runCatching { addYouTubeAccount(legacy) }.onFailure { register(legacy, YouTubeAccount(LEGACY_ID, "Cuenta de YouTube")) }
                settings.update { it.copy(youtubeCookie = "") }
            }
        }
    }

    override suspend fun addYouTubeAccount(cookie: String): YouTubeAccount = withContext(Dispatchers.IO) {
        require("SAPISID=" in cookie) { "No se detectó una sesión de YouTube Music" }
        val info = runCatching { innerTube.accountInfo(cookie) }.getOrNull()
            ?: throw IllegalArgumentException("YouTube no reconoció la sesión; vuelve a iniciar sesión")
        val id = (info.handle ?: info.name).lowercase().filter { it.isLetterOrDigit() || it == '@' || it == '.' || it == '_' }.ifEmpty { LEGACY_ID }
        YouTubeAccount(id, info.name, info.handle, info.avatarUrl).also { register(cookie, it) }
    }

    private suspend fun register(cookie: String, account: YouTubeAccount) = store.update { vault ->
        vault.copy(youtube = vault.youtube.filterNot { it.account.id == account.id } + StoredYouTubeAccount(account, cookie), activeId = account.id)
    }

    override suspend fun switchYouTubeAccount(id: String) = store.update { vault ->
        if (vault.youtube.any { it.account.id == id }) vault.copy(activeId = id) else vault
    }

    override suspend fun removeYouTubeAccount(id: String) {
        store.update { vault ->
            val rest = vault.youtube.filterNot { it.account.id == id }
            vault.copy(youtube = rest, activeId = if (vault.activeId == id) rest.firstOrNull()?.account?.id else vault.activeId)
        }
        // Sin ninguna sesión, las playlists reflejadas de YouTube dejan de tener sentido.
        if (store.vault.youtube.isEmpty()) spotifyData.clearYouTubeMirror()
    }

    override suspend fun spotifyAuthorizationUrl(): String = session.pkce.beginAuthorization()

    override suspend fun completeSpotifyAuthorization(callbackUri: String): SpotifyAccount = withContext(Dispatchers.IO) {
        session.pkce.completeAuthorization(callbackUri)
        store.update { it.copy(spotify = SpotifyAccount(PENDING_ID, "Spotify", experimental = false)) }
        describeSpotify(experimental = false)
    }

    override suspend fun connectSpotifyWithCookie(spDc: String): SpotifyAccount = withContext(Dispatchers.IO) {
        session.webCookie.signInWithCookie(spDc)
        store.update { it.copy(spotify = SpotifyAccount(PENDING_ID, "Spotify", experimental = true)) }
        describeSpotify(experimental = true)
    }

    /** Completa nombre y avatar; si `me` falla la cuenta queda conectada con un nombre genérico. */
    private suspend fun describeSpotify(experimental: Boolean): SpotifyAccount {
        val me = runCatching { spotify.me() }.getOrNull()
        val account = SpotifyAccount(me?.id ?: PENDING_ID, me?.displayName?.ifBlank { null } ?: "Spotify", me?.imageUrl, experimental)
        store.update { it.copy(spotify = account) }
        return account
    }

    override suspend fun disconnectSpotify() {
        runCatching { session.pkce.signOut() }
        runCatching { session.webCookie.signOut() }
        store.update { it.copy(spotify = null) }
        spotifyData.clearSpotify()
    }

    private companion object {
        const val LEGACY_ID = "youtube"
        const val PENDING_ID = "spotify"
    }
}
