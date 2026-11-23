package com.pocketlinux.automation

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * Confinement-hardened atomic JSON diagnostics writer and structured single-line logcat emitter
 * for PocketLinux E2E automation (`com.pocketlinux.automation`).
 */
object DiagnosticsStore {

    const val LOG_TAG = "PocketLinuxE2E"
    const val DIAGNOSTICS_DIR_NAME = "diagnostics"
    const val LAST_ACTION_FILE_NAME = "last_action.json"
    private const val MAX_LOGCAT_JSON_CHARS = 3200

    private val writeSeq = AtomicLong(0L)
    private val writeLock = Any()

    fun diagnosticsDir(filesDir: File): File {
        val dir = File(filesDir, DIAGNOSTICS_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Strictly sanitizes any caller-supplied `out_file` name so it is confined to
     * `<filesDir>/diagnostics/<sanitized>.json` with zero directory traversal (`..`, `/`, `\`).
     */
    fun sanitizeOutFileName(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var base = raw.trim()
            .replace('/', '_')
            .replace('\\', '_')
            .replace(Regex("\\.\\.+"), "_")
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('.', '_', '-')
        if (base.isEmpty()) {
            base = "e2e_action"
        }
        if (base.length > 64) {
            base = base.take(64).trimEnd('.', '_', '-')
        }
        if (!base.endsWith(".json")) {
            base = "$base.json"
        }
        return base
    }

    fun resolveConfinedFile(filesDir: File, rawOutFile: String?): File? {
        val sanitized = sanitizeOutFileName(rawOutFile) ?: return null
        val diagDir = diagnosticsDir(filesDir)
        val candidate = File(diagDir, sanitized)
        val diagCanonical = diagDir.canonicalFile
        val candidateCanonical = candidate.canonicalFile
        if (candidateCanonical.parentFile != diagCanonical) {
            return File(diagDir, "e2e_action.json")
        }
        return candidate
    }

    fun writeActionResponse(
        context: Context,
        action: String,
        reqId: String,
        rawOutFile: String?,
        payload: JSONObject
    ) {
        writeActionResponse(
            filesDir = context.filesDir,
            action = action,
            reqId = reqId,
            rawOutFile = rawOutFile,
            payload = payload,
            emitLogcat = true
        )
    }

    fun writeActionResponse(
        filesDir: File,
        action: String,
        reqId: String,
        rawOutFile: String?,
        payload: JSONObject,
        emitLogcat: Boolean = false
    ) {
        if (reqId.isNotBlank() && !payload.has("reqId")) {
            payload.put("reqId", reqId)
        }
        val compactJson = payload.toString()
        val diagDir = diagnosticsDir(filesDir)

        synchronized(writeLock) {
            val targetOut = resolveConfinedFile(filesDir, rawOutFile)
            if (targetOut != null && targetOut.name != LAST_ACTION_FILE_NAME) {
                writeTextAtomically(diagDir, targetOut, compactJson)
            }
            val lastActionFile = File(diagDir, LAST_ACTION_FILE_NAME)
            writeTextAtomically(diagDir, lastActionFile, compactJson)
        }

        if (emitLogcat) {
            val effectiveReqId = reqId.ifBlank { "no_req_id" }
            val logPayload = buildSafeLogcatJson(payload, compactJson)
            runCatching {
                Log.i(LOG_TAG, "[$LOG_TAG] E2E_JSON:$action:$effectiveReqId:$logPayload")
            }
        }
    }

    internal fun buildSafeLogcatJson(payload: JSONObject, fullJson: String): String {
        if (fullJson.length <= MAX_LOGCAT_JSON_CHARS) {
            return fullJson
        }
        return try {
            val copy = JSONObject(fullJson)
            if (copy.has("stdout")) {
                val out = copy.optString("stdout", "")
                if (out.length > 1024) {
                    copy.put("stdout", out.take(1024))
                }
            }
            if (copy.has("stderr")) {
                val err = copy.optString("stderr", "")
                if (err.length > 512) {
                    copy.put("stderr", err.take(512))
                }
            }
            copy.toString()
        } catch (_: Throwable) {
            fullJson.take(MAX_LOGCAT_JSON_CHARS)
        }
    }

    private fun writeTextAtomically(dir: File, targetFile: File, content: String) {
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val seq = writeSeq.incrementAndGet()
        val tmpFile = File(dir, ".tmp_${targetFile.name}_${seq}.tmp")
        try {
            FileOutputStream(tmpFile).use { fos ->
                fos.write(content.toByteArray(Charsets.UTF_8))
                fos.flush()
                runCatching { fos.fd.sync() }
            }
            if (!tmpFile.renameTo(targetFile)) {
                targetFile.writeText(content, Charsets.UTF_8)
                tmpFile.delete()
            }
        } catch (_: Throwable) {
            runCatching { targetFile.writeText(content, Charsets.UTF_8) }
            runCatching { tmpFile.delete() }
        }
    }
}
