package app.ondara.core.data

import app.ondara.core.database.FavoriteEntity
import app.ondara.core.database.HistoryEntity
import app.ondara.core.database.LibraryDao
import app.ondara.core.database.PlaylistEntity
import app.ondara.core.database.toEntity
import app.ondara.core.domain.BackupRepository
import app.ondara.core.domain.CsvParser
import app.ondara.core.domain.ImportRepository
import app.ondara.core.domain.ImportResult
import app.ondara.core.domain.LibraryRepository
import app.ondara.core.domain.SettingsRepository
import app.ondara.core.model.MusicItem
import app.ondara.core.model.SearchFilter
import app.ondara.core.model.Song
import app.ondara.core.model.UserSettings
import app.ondara.source.innertube.InnerTubeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImportRepositoryImpl @Inject constructor(
    private val innerTube: InnerTubeClient,
    private val library: LibraryRepository,
) : ImportRepository {
    override suspend fun importYouTubePlaylist(urlOrId: String): ImportResult = withContext(Dispatchers.IO) {
        val id = playlistIdFrom(urlOrId) ?: throw IllegalArgumentException("No se reconoce el enlace de la playlist")
        val page = innerTube.playlist(id, maxSongs = 2000)
        require(page.songs.isNotEmpty()) { "La playlist está vacía o es privada (inicia sesión para importarla)" }
        val localId = library.createPlaylist(page.playlist.title.ifBlank { "Importada" }, page.songs)
        ImportResult(localId, page.songs.size, page.songs.size)
    }

    /**
     * Busca cada fila en el catálogo con hasta 4 peticiones en paralelo.
     * O(n) búsquedas de red para n filas; el orden original se conserva.
     */
    override suspend fun importCsv(name: String, csv: String, onProgress: (Int, Int) -> Unit): ImportResult = withContext(Dispatchers.IO) {
        val tracks = CsvParser.tracks(csv)
        require(tracks.isNotEmpty()) { "El CSV no contiene canciones" }
        val gate = Semaphore(4)
        val done = AtomicInteger()
        val matches: List<Song?> = coroutineScope {
            tracks.map { track ->
                async {
                    gate.withPermit {
                        val result = runCatching { innerTube.search("${track.title} ${track.artist}".trim(), SearchFilter.SONGS) }.getOrNull()
                        onProgress(done.incrementAndGet(), tracks.size)
                        result?.items?.firstNotNullOfOrNull { (it as? MusicItem.SongItem)?.song }
                    }
                }
            }.awaitAll()
        }
        val songs = matches.filterNotNull().distinctBy { it.id }
        val id = library.createPlaylist(name, songs)
        ImportResult(id, songs.size, tracks.size)
    }

    companion object {
        private val LIST_PARAM = Regex("""[?&]list=([A-Za-z0-9_-]+)""")
        private val RAW_ID = Regex("""^(VL)?(PL|RD|OLAK|LM|UU|FL)[A-Za-z0-9_-]+$""")

        fun playlistIdFrom(input: String): String? {
            val text = input.trim()
            LIST_PARAM.find(text)?.let { return it.groupValues[1] }
            return text.takeIf(RAW_ID::matches)?.removePrefix("VL")
        }
    }
}

@Serializable
internal data class BackupPlaylist(val id: String, val name: String, val createdAt: Long, val songIds: List<String>)

@Serializable
internal data class BackupFavorite(val songId: String, val addedAt: Long)

@Serializable
internal data class BackupPlay(val songId: String, val playedAt: Long)

@Serializable
internal data class BackupData(
    val version: Int = 1,
    val settings: UserSettings,
    val songs: List<Song>,
    val favorites: List<BackupFavorite>,
    val playlists: List<BackupPlaylist>,
    val history: List<BackupPlay>,
)

/** Cifrado opcional del backup: PBKDF2-HMAC-SHA256 (120 000 iteraciones) + AES-256-GCM. */
internal object BackupCrypto {
    private val MAGIC = "ONDARA1".toByteArray()
    private const val ITERATIONS = 120_000
    private const val SALT = 16
    private const val IV = 12

    fun isEncrypted(bytes: ByteArray) = bytes.size > MAGIC.size && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    fun encrypt(plain: ByteArray, password: String): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT).also(random::nextBytes)
        val iv = ByteArray(IV).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv)) }
        return MAGIC + salt + iv + cipher.doFinal(plain)
    }

    fun decrypt(bytes: ByteArray, password: String): ByteArray {
        val salt = bytes.copyOfRange(MAGIC.size, MAGIC.size + SALT)
        val iv = bytes.copyOfRange(MAGIC.size + SALT, MAGIC.size + SALT + IV)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv)) }
        return try {
            cipher.doFinal(bytes, MAGIC.size + SALT + IV, bytes.size - MAGIC.size - SALT - IV)
        } catch (e: java.security.GeneralSecurityException) {
            throw IllegalArgumentException("Contraseña incorrecta o archivo dañado", e)
        }
    }

    private fun key(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)
        return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
    }
}

@Singleton
class BackupRepositoryImpl @Inject constructor(
    private val dao: LibraryDao,
    private val settings: SettingsRepository,
) : BackupRepository {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun export(password: String?): ByteArray = withContext(Dispatchers.IO) {
        val data = BackupData(
            // La cookie de sesión nunca sale del dispositivo.
            settings = settings.settings.first().copy(youtubeCookie = ""),
            songs = dao.librarySongs().map { it.toModel() },
            favorites = dao.allFavorites().map { BackupFavorite(it.songId, it.addedAt) },
            playlists = dao.allPlaylists().map { p -> BackupPlaylist(p.id, p.name, p.createdAt, dao.playlistRows(p.id).map { it.songId }) },
            history = dao.allHistory().map { BackupPlay(it.songId, it.playedAt) },
        )
        val plain = json.encodeToString(BackupData.serializer(), data).toByteArray()
        if (password.isNullOrEmpty()) plain else BackupCrypto.encrypt(plain, password)
    }

    override suspend fun import(bytes: ByteArray, password: String?): Unit = withContext(Dispatchers.IO) {
        val plain = if (BackupCrypto.isEncrypted(bytes)) {
            require(!password.isNullOrEmpty()) { "El backup está cifrado: escribe la contraseña" }
            BackupCrypto.decrypt(bytes, password)
        } else {
            bytes
        }
        val data = try {
            json.decodeFromString(BackupData.serializer(), plain.decodeToString())
        } catch (e: Exception) {
            throw IllegalArgumentException("El archivo no es un backup de Ondara", e)
        }
        dao.upsertSongs(data.songs.map { it.toEntity() })
        data.favorites.forEach { dao.addFavorite(FavoriteEntity(it.songId, it.addedAt)) }
        data.playlists.forEach { p ->
            dao.upsertPlaylist(PlaylistEntity(p.id, p.name, p.createdAt))
            dao.replacePlaylistSongs(p.id, p.songIds)
        }
        dao.clearHistory()
        dao.addHistoryAll(data.history.map { HistoryEntity(songId = it.songId, playedAt = it.playedAt) })
        val current = settings.settings.first()
        settings.update { data.settings.copy(youtubeCookie = current.youtubeCookie) }
    }
}
