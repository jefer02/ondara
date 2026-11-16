package app.ondara.core.data

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DataTest {
    @Test
    fun `backup crypto round trips and rejects wrong passwords`() {
        val plain = """{"version":1,"hola":"mundo ñ"}""".toByteArray()
        val encrypted = BackupCrypto.encrypt(plain, "s3creta")
        assertTrue(BackupCrypto.isEncrypted(encrypted))
        assertFalse(BackupCrypto.isEncrypted(plain))
        assertFalse(String(encrypted, Charsets.ISO_8859_1).contains("mundo"))
        assertArrayEquals(plain, BackupCrypto.decrypt(encrypted, "s3creta"))
        assertThrows<IllegalArgumentException> { BackupCrypto.decrypt(encrypted, "otra") }
        // Sal e IV aleatorios: dos cifrados del mismo texto difieren.
        assertFalse(encrypted.contentEquals(BackupCrypto.encrypt(plain, "s3creta")))
    }

    @Test
    fun `playlist ids are extracted from urls and raw ids`() {
        assertEquals("PLRTT1eLEoNHc", ImportRepositoryImpl.playlistIdFrom("https://music.youtube.com/playlist?list=PLRTT1eLEoNHc&si=x"))
        assertEquals("OLAK5uy_abc-1", ImportRepositoryImpl.playlistIdFrom("https://www.youtube.com/watch?v=x&list=OLAK5uy_abc-1"))
        assertEquals("PLabc", ImportRepositoryImpl.playlistIdFrom(" VLPLabc "))
        assertNull(ImportRepositoryImpl.playlistIdFrom("https://example.com/nada"))
    }
}
