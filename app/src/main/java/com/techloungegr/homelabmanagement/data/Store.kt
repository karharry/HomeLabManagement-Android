package com.techloungegr.homelabmanagement.data

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.techloungegr.homelabmanagement.model.Machine
import com.techloungegr.homelabmanagement.model.jsonParser
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Machine list, preferences, pinned host keys / certificates, and secrets.
 * Secrets (passwords, API keys) are AES-GCM encrypted with a key held in the Android Keystore, so they
 * never sit on disk in clear text and can't be extracted from a backup.
 */
object Store {
    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var secretPrefs: SharedPreferences
    private lateinit var machinesFile: File
    private val lock = Any()
    private const val KEY_ALIAS = "homelab-secrets-v1"

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        secretPrefs = appContext.getSharedPreferences("secrets", Context.MODE_PRIVATE)
        machinesFile = File(appContext.filesDir, "machines.json")
    }

    // ---- machines ----
    fun loadMachines(): List<Machine> = synchronized(lock) {
        try {
            if (!machinesFile.exists()) emptyList()
            else jsonParser.decodeFromString(ListSerializer(Machine.serializer()), machinesFile.readText())
        } catch (_: Exception) { emptyList() }
    }

    fun saveMachines(list: List<Machine>) = synchronized(lock) {
        val tmp = File(machinesFile.path + ".tmp")
        tmp.writeText(jsonParser.encodeToString(ListSerializer(Machine.serializer()), list))
        tmp.renameTo(machinesFile)
    }

    // ---- prefs ----
    fun pref(key: String, default: String = ""): String = prefs.getString(key, default) ?: default
    fun setPref(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    fun boolPref(key: String, default: Boolean = false) = prefs.getBoolean(key, default)
    fun setBoolPref(key: String, v: Boolean) { prefs.edit().putBoolean(key, v).apply() }

    // ---- pinned SSH host keys / TLS certificates (trust on first use) ----
    fun pinned(key: String): String? = prefs.getString("pin.$key", null)
    fun pin(key: String, fingerprint: String) { prefs.edit().putString("pin.$key", fingerprint).apply() }
    fun forgetPin(key: String) { prefs.edit().remove("pin.$key").apply() }

    // ---- secrets ----
    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build()
        )
        return gen.generateKey()
    }

    fun secret(key: String): String = synchronized(lock) {
        val raw = secretPrefs.getString(key, null) ?: return ""
        try {
            val bytes = Base64.decode(raw, Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, 12)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(c.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
        } catch (_: Exception) { "" }
    }

    fun setSecret(key: String, value: String) = synchronized(lock) {
        if (value.isEmpty()) { secretPrefs.edit().remove(key).apply(); return }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secretKey())
        val out = c.iv + c.doFinal(value.toByteArray(Charsets.UTF_8))
        secretPrefs.edit().putString(key, Base64.encodeToString(out, Base64.NO_WRAP)).apply()
    }

    fun forgetSecrets(machineId: String) {
        for (s in listOf("secret", "ssh", "fan", "protonuser", "proton", "pihole")) setSecret("$s.$machineId", "")
    }

    fun secretOf(id: String) = secret("secret.$id")
    fun sshPasswordOf(id: String) = secret("ssh.$id")
    fun fanPasswordOf(id: String) = secret("fan.$id")
    fun piholePasswordOf(id: String) = secret("pihole.$id")

    // ---- history (one sample per machine per minute, kept 7 days) ----
    private val historyDb: HistoryDb by lazy { HistoryDb(appContext) }
    private val lastSample = HashMap<String, Long>()

    fun recordSample(machineId: String, cpu: Double?, mem: Double?, net: Double?) {
        val now = System.currentTimeMillis()
        synchronized(lastSample) {
            if (now - (lastSample[machineId] ?: 0) < 60_000) return
            lastSample[machineId] = now
        }
        try {
            historyDb.writableDatabase.execSQL("INSERT INTO samples VALUES (?,?,?,?,?)", arrayOf<Any?>(machineId, now, cpu, mem, net))
        } catch (_: Exception) {}
    }

    fun historySeries(machineId: String, seconds: Long, buckets: Int = 120): Triple<List<Float>, List<Float>, List<Float>> {
        val now = System.currentTimeMillis(); val since = now - seconds * 1000; val width = seconds * 1000.0 / buckets
        val cpu = ArrayList<Float>(); val mem = ArrayList<Float>(); val net = ArrayList<Float>()
        try {
            historyDb.readableDatabase.rawQuery(
                "SELECT CAST((ts - ?) / ? AS INTEGER) b, AVG(cpu), AVG(mem), AVG(net) FROM samples WHERE machine = ? AND ts >= ? GROUP BY b ORDER BY b",
                arrayOf(since.toString(), width.toString(), machineId, since.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    if (!c.isNull(1)) cpu.add(c.getFloat(1))
                    if (!c.isNull(2)) mem.add(c.getFloat(2) * 100f)
                    if (!c.isNull(3)) net.add(c.getFloat(3))
                }
            }
        } catch (_: Exception) {}
        return Triple(cpu, mem, net)
    }

    private class HistoryDb(c: Context) : SQLiteOpenHelper(c, "history.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE samples (machine TEXT, ts INTEGER, cpu REAL, mem REAL, net REAL)")
            db.execSQL("CREATE INDEX samples_idx ON samples (machine, ts)")
        }
        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}
        override fun onOpen(db: SQLiteDatabase) {
            db.execSQL("DELETE FROM samples WHERE ts < ?", arrayOf(System.currentTimeMillis() - 7 * 86400_000L))
        }
    }
}
