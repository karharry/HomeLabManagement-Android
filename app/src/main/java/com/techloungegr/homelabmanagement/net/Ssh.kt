package com.techloungegr.homelabmanagement.net

import com.techloungegr.homelabmanagement.data.Store
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.ByteArrayOutputStream
import java.security.PublicKey
import java.security.Security
import java.util.Base64
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater

class SshError(message: String) : Exception(message)

data class SshSettings(val host: String, val port: Int, val user: String, val password: String, val useSudo: Boolean = false)

fun shQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"
fun psQuote(s: String) = "'" + s.replace("'", "''") + "'"

private fun ensureBouncyCastle() {
    // Android ships a cut-down "BC" provider that lacks the algorithms SSH needs; swap in the full one.
    if (Security.getProvider("BC")?.javaClass != BouncyCastleProvider::class.java) {
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)
    }
}

fun forgetSshHostKey(host: String, port: Int) = Store.forgetPin("ssh.$host:$port")
fun forgetTlsCert(host: String, port: Int) = Store.forgetPin("tls.$host:$port")

/** One SSH connection reused across calls (trust-on-first-use host key pinning, password auth). */
class SshRunner {
    private var client: SSHClient? = null
    private var connectedTo: SshSettings? = null
    /** sshd allows ~10 sessions per connection; stay well below that. */
    private val gate = Semaphore(4)

    @Synchronized fun close() {
        try { client?.disconnect() } catch (_: Exception) {}
        client = null; connectedTo = null
    }

    @Synchronized private fun ensure(s: SshSettings): SSHClient {
        if (connectedTo != s) close()
        client?.let { if (it.isConnected && it.isAuthenticated) return it else close() }
        ensureBouncyCastle()
        val c = SSHClient()
        c.connectTimeout = 12_000; c.timeout = 120_000
        val pinKey = "ssh.${s.host}:${s.port}"
        c.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String?, port: Int, key: PublicKey?): Boolean {
                val fp = SecurityUtils.getFingerprint(key)
                val pinned = Store.pinned(pinKey)
                if (pinned == null) { Store.pin(pinKey, fp); return true }
                if (pinned != fp) throw SshError("The SSH host key changed since it was first trusted. Use “Forget pinned host key” in the machine's settings if this is expected.")
                return true
            }
            override fun findExistingAlgorithms(hostname: String?, port: Int): MutableList<String> = mutableListOf()
        })
        try {
            c.connect(s.host, s.port)
            c.authPassword(s.user, s.password)
        } catch (e: UserAuthException) {
            try { c.disconnect() } catch (_: Exception) {}
            throw SshError("SSH login was rejected for “${s.user}”. Check the user name and password.")
        } catch (e: SshError) { try { c.disconnect() } catch (_: Exception) {}; throw e
        } catch (e: Exception) {
            try { c.disconnect() } catch (_: Exception) {}
            val root = generateSequence<Throwable>(e) { it.cause }.last()
            if (root is SshError) throw root
            throw SshError("Network error talking to the machine over SSH: ${e.message ?: e.javaClass.simpleName}")
        }
        try { c.connection.keepAlive.keepAliveInterval = 15 } catch (_: Exception) {}
        client = c; connectedTo = s
        return c
    }

    private fun exec(c: SSHClient, command: String, maxBytes: Int): String {
        c.startSession().use { session ->
            val cmd = session.exec(command)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(65536)
            val input = cmd.inputStream
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() >= maxBytes) break
            }
            try { cmd.join(10, TimeUnit.SECONDS) } catch (_: Exception) {}
            return out.toString(Charsets.UTF_8)
        }
    }

    private fun alive(): Boolean = client?.let { it.isConnected && it.isAuthenticated } == true

    private fun <T> guarded(s: SshSettings, op: (SSHClient) -> T): T {
        var last: Exception? = null
        for (attempt in 1..2) {
            try {
                val c = ensure(s)
                gate.acquire()
                try { return op(c) } finally { gate.release() }
            } catch (e: SshError) {
                if (!alive()) close()
                last = e
                if (alive() || e.message?.contains("rejected") == true || e.message?.contains("host key changed") == true) break
            } catch (e: Exception) {
                if (!alive()) close()
                last = SshError("SSH error: ${e.message ?: e.javaClass.simpleName}")
                if (alive()) break   // the connection is fine; this command just failed
            }
        }
        throw (last as? SshError ?: SshError("SSH error: ${last?.message}"))
    }

    /** Runs a POSIX shell script, optionally under sudo (the SSH password is fed to `sudo -S`). */
    fun sh(script: String, s: SshSettings, sudo: Boolean = false, merge: Boolean = false, maxBytes: Int = 4 * 1024 * 1024): String {
        val b64 = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_8))
        val run = "sh -c \"\$(printf %s $b64 | base64 -d)\""
        val command = if (sudo && s.useSudo && s.user != "root") "printf '%s\\n' ${shQuote(s.password)} | sudo -S -p '' $run" else run
        val redirect = if (merge) "2>&1" else "2>/dev/null"
        return guarded(s) { exec(it, "($command) $redirect; true", maxBytes) }
    }

    /** Runs a PowerShell script via a compressed -EncodedCommand so quoting never matters. */
    fun ps(script: String, s: SshSettings, maxBytes: Int = 8 * 1024 * 1024): String =
        guarded(s) { exec(it, psCommand(script), maxBytes) }

    companion object {
        fun psCommand(script: String): String {
            val raw = script.toByteArray(Charsets.UTF_8)
            val d = Deflater(Deflater.DEFAULT_COMPRESSION, true)
            d.setInput(raw); d.finish()
            val bos = ByteArrayOutputStream(); val buf = ByteArray(4096)
            while (!d.finished()) { bos.write(buf, 0, d.deflate(buf)) }
            d.end()
            val b64 = Base64.getEncoder().encodeToString(bos.toByteArray())
            val bootstrap = "\$ProgressPreference='SilentlyContinue';" +
                "\$b=[Convert]::FromBase64String('$b64');" +
                "\$m=New-Object IO.MemoryStream(,\$b);" +
                "\$d=New-Object IO.Compression.DeflateStream(\$m,[IO.Compression.CompressionMode]::Decompress);" +
                "\$r=New-Object IO.StreamReader(\$d,[Text.Encoding]::UTF8);" +
                "try{[Console]::OutputEncoding=[Text.Encoding]::UTF8}catch{};" +
                "try{Invoke-Expression \$r.ReadToEnd()}catch{};exit 0"
            val encoded = Base64.getEncoder().encodeToString(bootstrap.toByteArray(Charsets.UTF_16LE))
            val cmd = "powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand $encoded"
            if (cmd.length >= 8000) throw SshError("Internal error: the script is too large to send (${cmd.length} characters).")
            return cmd
        }
    }
}
