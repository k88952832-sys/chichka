package dev.aero.menu

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * Copies the bundled injector binary and target .so out of assets, then runs the
 * injector as root against a running process. All of this is unverified against a
 * real device: read [InjectResult.log] after calling [inject] before trusting it.
 */
object RootInjector {

    const val VERSION = "0.4"

    data class InjectResult(val success: Boolean, val log: String)

    // Names/paths to try as the su binary. Each is invoked through /system/bin/sh -c
    // rather than as ProcessBuilder's argv[0] directly: ProcessBuilder resolves a bare
    // command name by searching PATH itself, in user space, and throws before ever
    // calling execve if it doesn't find a real file there. Root frameworks that hook
    // execve (KernelSU included) only get a chance to act once execve is actually
    // called, so routing through a shell's own exec resolution is what lets that hook
    // fire; calling "su" via ProcessBuilder directly can fail even when su works fine
    // from an actual shell.
    private val SU_CANDIDATES = listOf(
        "su",
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/debug_ramdisk/su",
    )

    private fun copyAsset(context: Context, assetName: String, outFile: File) {
        context.assets.open(assetName).use { input ->
            FileOutputStream(outFile).use { output -> input.copyTo(output) }
        }
    }

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** Runs `command` as `<suBinary> -c <command>`, itself invoked via `sh -c` so that
     * su's own name resolution happens inside the shell, not inside ProcessBuilder. */
    private fun runWith(suBinary: String, command: String): Pair<Int, String> {
        val inner = "$suBinary -c ${shellQuote(command)}"
        val process = ProcessBuilder("/system/bin/sh", "-c", inner)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        return exit to output
    }

    /** Finds a working su binary, trying [SU_CANDIDATES] in order. A candidate only
     * counts as working if `id` actually ran under it with exit code 0; a candidate
     * that spawns but is refused (wrong binary, permission denied) is not reused for
     * later commands. */
    private fun resolveSu(log: StringBuilder): String? {
        for (candidate in SU_CANDIDATES) {
            try {
                val (exit, out) = runWith(candidate, "id")
                log.append("su candidate '$candidate' -> exit=$exit out='${out.trim()}'\n")
                if (exit == 0) return candidate
            } catch (e: Exception) {
                log.append("su candidate '$candidate' -> failed to run (${e.javaClass.simpleName}: ${e.message})\n")
            }
        }
        return null
    }

    /**
     * @param packageName the target game's package, e.g. "com.axlebolt.standoff2"
     */
    fun inject(context: Context, packageName: String): InjectResult {
        val log = StringBuilder()
        log.append("Aero injector v$VERSION\n")
        try {
            val suBinary = resolveSu(log)
            if (suBinary == null) {
                log.append("No working su binary found among: ${SU_CANDIDATES.joinToString()}\n")
                log.append("Check in a terminal app on the phone (Termux) what 'which su' or " +
                        "'command -v su' reports, and tell Aero that exact path.\n")
                return InjectResult(false, log.toString())
            }
            log.append("Using su candidate: $suBinary\n")

            val injectorFile = File(context.filesDir, "injector")
            val libFile = File(context.filesDir, "liblemmingrmt.so")
            copyAsset(context, "injector", injectorFile)
            copyAsset(context, "liblemmingrmt.so", libFile)

            val (pidExit, pidOut) = runWith(suBinary, "pidof $packageName")
            val pid = pidOut.trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull()
            log.append("pidof $packageName -> exit=$pidExit out='${pidOut.trim()}'\n")
            if (pid == null) {
                log.append("Could not find a running process for $packageName. Start the game first.\n")
                return InjectResult(false, log.toString())
            }

            val chmodCmd = "chmod 755 ${injectorFile.absolutePath} ${libFile.absolutePath}"
            val (chmodExit, chmodOut) = runWith(suBinary, chmodCmd)
            log.append("chmod -> exit=$chmodExit out='$chmodOut'\n")
            if (chmodExit != 0) {
                return InjectResult(false, log.toString())
            }

            val injectCmd = "${injectorFile.absolutePath} $pid ${libFile.absolutePath}"
            val (injectExit, injectOut) = runWith(suBinary, injectCmd)
            log.append("inject -> exit=$injectExit\n$injectOut\n")
            return InjectResult(injectExit == 0, log.toString())
        } catch (e: Exception) {
            log.append("Exception: ${e.javaClass.simpleName}: ${e.message}\n")
            return InjectResult(false, log.toString())
        }
    }
}
