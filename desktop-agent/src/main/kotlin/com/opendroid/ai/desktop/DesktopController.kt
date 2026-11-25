package com.opendroid.ai.desktop

import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The platform capabilities the agent exposes. Every method here performs a real action on the
 * desktop through the JDK's AWT stack; none of them is a stub.
 *
 * The interface exists so the network layer can be tested without a display: the real
 * [DesktopController] implements it, and a test can substitute a deterministic one. The protocol,
 * pairing, authentication, input dispatch and file transfer stay real in those tests.
 */
interface DesktopControllerContract {
    fun captureScreen(maxWidth: Int, quality: Int, monitor: Int = 0): CapturedFrame
    fun monitorCount(): Int
    fun screenSize(): java.awt.Dimension
    fun mouseMove(x: Int, y: Int, monitor: Int = 0, screenWidth: Int = 0, screenHeight: Int = 0)
    fun mouseDown(button: Int)
    fun mouseUp(button: Int)
    fun mouseWheel(amount: Int)
    fun keyDown(keyCode: Int)
    fun keyUp(keyCode: Int)
    fun typeText(text: String): Int
    fun listApplications(): List<AppEntryView>
    fun launchApplication(id: String): LaunchOutcome
    fun readClipboard(): String?
    fun writeClipboard(text: String)
}

class DesktopController(
    private val robot: Robot = Robot(),
) : DesktopControllerContract {

    /** The virtual desktop size. On a multi-monitor machine this spans every screen. */
    override fun screenSize(): Dimension = Toolkit.getDefaultToolkit().screenSize

    /**
     * The desktop clipboard's text, or null when it holds no text (an image, a file list) or the
     * platform refuses access. A headless session has no clipboard owner, so this returns null
     * rather than throwing.
     */
    override fun readClipboard(): String? = runCatching {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        if (!clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) null
        else clipboard.getData(DataFlavor.stringFlavor) as? String
    }.getOrNull()

    override fun writeClipboard(text: String) {
        runCatching {
            Toolkit.getDefaultToolkit().systemClipboard
                .setContents(StringSelection(text), null)
        }
    }

    /** Number of attached screens, so the client can offer a monitor choice. */
    override fun monitorCount(): Int = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.size

    /**
     * Capture the screen, scaled to [maxWidth] and re-encoded as JPEG at [quality] (1-100).
     * Scaling happens before encoding, so a small client never receives a full-size image.
     */
    override fun captureScreen(maxWidth: Int, quality: Int, monitor: Int): CapturedFrame {
        val devices = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
        val bounds = if (monitor in devices.indices) devices[monitor].defaultConfiguration.bounds
        else devices.first().defaultConfiguration.bounds
        val width = bounds.width
        val height = bounds.height
        val targetWidth = if (maxWidth in 1 until width) maxWidth else width
        val targetHeight = (height.toDouble() * targetWidth / width).toInt().coerceAtLeast(1)

        val raw = robot.createScreenCapture(bounds)
        val scaled = if (targetWidth == width) raw else scale(raw, targetWidth, targetHeight)
        val bytes = encodeJpeg(scaled, quality.coerceIn(1, 100))
        return CapturedFrame(targetWidth, targetHeight, "JPEG", System.currentTimeMillis(), bytes)
    }

    private fun scale(image: BufferedImage, width: Int, height: Int): BufferedImage {
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = out.createGraphics()
        try {
            graphics.drawImage(image, 0, 0, width, height, null)
        } finally {
            graphics.dispose()
        }
        return out
    }

    private fun encodeJpeg(image: BufferedImage, quality: Int): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val params = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = quality / 100f
        }
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { stream ->
            writer.output = stream
            writer.write(null, IIOImage(image, null, null), params)
        }
        writer.dispose()
        return out.toByteArray()
    }

    // ---- Input injection ----

    override fun mouseMove(x: Int, y: Int, monitor: Int, screenWidth: Int, screenHeight: Int) {
        val devices = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
        val bounds = devices.getOrNull(monitor)?.defaultConfiguration?.bounds ?: devices.first().defaultConfiguration.bounds
        // Stream coordinates may be downscaled; map them back to native pixels before injection.
        val nativeX = if (screenWidth > 0) x.toLong() * bounds.width / screenWidth else x.toLong()
        val nativeY = if (screenHeight > 0) y.toLong() * bounds.height / screenHeight else y.toLong()
        // A secondary display can have a negative origin in virtual desktop coordinates.
        robot.mouseMove(
            bounds.x + nativeX.toInt().coerceIn(0, bounds.width - 1),
            bounds.y + nativeY.toInt().coerceIn(0, bounds.height - 1),
        )
    }

    override fun mouseDown(button: Int) = robot.mousePress(maskFor(button))

    override fun mouseUp(button: Int) = robot.mouseRelease(maskFor(button))

    override fun mouseWheel(amount: Int) = robot.mouseWheel(amount)

    /**
     * Maps the protocol's button number to AWT's mask.
     *
     * The protocol follows the X11 convention (1 = left, 2 = middle, 3 = right), which is what
     * every other remote-desktop client sends. AWT's own numbering differs: BUTTON2 is the middle
     * button and BUTTON3 the right one.
     */
    private fun maskFor(button: Int): Int = when (button) {
        1 -> InputEvent.BUTTON1_DOWN_MASK
        2 -> InputEvent.BUTTON2_DOWN_MASK
        3 -> InputEvent.BUTTON3_DOWN_MASK
        else -> InputEvent.BUTTON1_DOWN_MASK
    }

    override fun keyDown(keyCode: Int) = robot.keyPress(keyCode)

    override fun keyUp(keyCode: Int) = robot.keyRelease(keyCode)

    /**
     * Types text through a real clipboard paste so Unicode, accents and line breaks survive key-layout
     * differences. The desktop's previous clipboard contents are restored after the paste is handled.
     */
    override fun typeText(text: String): Int {
        if (text.isEmpty()) return 0
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val previous = runCatching { clipboard.getContents(null) }.getOrNull()
        val modifier = if (System.getProperty("os.name", "").lowercase().contains("mac")) {
            KeyEvent.VK_META
        } else {
            KeyEvent.VK_CONTROL
        }
        return try {
            clipboard.setContents(StringSelection(text), null)
            robot.keyPress(modifier)
            try {
                robot.keyPress(KeyEvent.VK_V)
                robot.keyRelease(KeyEvent.VK_V)
            } finally {
                robot.keyRelease(modifier)
            }
            robot.waitForIdle()
            robot.delay(150)
            text.length
        } catch (_: Exception) {
            0
        } finally {
            runCatching { clipboard.setContents(previous ?: StringSelection(""), null) }
        }
    }

    // ---- Applications ----

    /**
     * Lists the applications that can be launched. The set is discovered from the platform's own
     * locations (Start Menu, .desktop files, .app bundles) so it reflects what is really installed.
     */
    override fun listApplications(): List<AppEntryView> {
        val found = linkedMapOf<String, AppEntryView>()
        for (candidate in AppDiscovery.discover()) {
            found.putIfAbsent(candidate.id, AppEntryView(candidate.id, candidate.name, false))
        }
        return found.values.toList()
    }

    /** Launch a discovered application by id. An unknown id is refused, never guessed. */
    override fun launchApplication(id: String): LaunchOutcome {
        val candidate = AppDiscovery.discover().firstOrNull { it.id == id }
            ?: return LaunchOutcome(false, "Unknown application id '$id'")
        return try {
            val process = ProcessBuilder(candidate.command).start()
            LaunchOutcome(true, null, process.pid())
        } catch (e: Exception) {
            LaunchOutcome(false, e.message ?: "Launch failed")
        }
    }

}

data class CapturedFrame(
    val width: Int,
    val height: Int,
    val format: String,
    val capturedAtMs: Long,
    val data: ByteArray,
)

data class LaunchOutcome(val accepted: Boolean, val reason: String?, val pid: Long = -1)

data class AppEntryView(val id: String, val name: String, val running: Boolean)

/** A discovered launchable application and the command that starts it. */
data class AppCandidate(val id: String, val name: String, val command: List<String>)

/**
 * Finds launchable applications from the platform's own locations. Discovery is read-only and
 * never executes anything; [DesktopController.launchApplication] is the only place a process is
 * started, and only for an id that discovery returned.
 */
object AppDiscovery {

    fun discover(osName: String = System.getProperty("os.name", "").lowercase()): List<AppCandidate> = when {
        osName.contains("win") -> windows()
        osName.contains("mac") || osName.contains("darwin") -> macos()
        else -> linux()
    }

    private fun linux(): List<AppCandidate> {
        val dirs = listOf(
            File("/usr/share/applications"),
            File("/usr/local/share/applications"),
            File(System.getProperty("user.home"), ".local/share/applications"),
        )
        val out = mutableListOf<AppCandidate>()
        for (dir in dirs) {
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".desktop") } ?: continue
            for (file in files) {
                val entry = parseDesktopEntry(file) ?: continue
                out += entry
            }
        }
        return out.sortedBy { it.name }
    }

    private fun parseDesktopEntry(file: File): AppCandidate? = runCatching {
        var name: String? = null
        var exec: String? = null
        var hidden = false
        var noDisplay = false
        file.forEachLine { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("Name=") && name == null -> name = trimmed.removePrefix("Name=").trim()
                trimmed.startsWith("Exec=") && exec == null -> exec = trimmed.removePrefix("Exec=").trim()
                trimmed == "Hidden=true" -> hidden = true
                trimmed == "NoDisplay=true" -> noDisplay = true
            }
        }
        if (hidden || noDisplay || name.isNullOrBlank() || exec.isNullOrBlank()) return@runCatching null
        // Strip field codes (%U, %f, ...) and run the executable directly, never through a shell.
        val parts = exec.split(' ').filter { it.isNotBlank() && !it.startsWith("%") }
        if (parts.isEmpty()) return@runCatching null
        AppCandidate(id = "linux:" + file.name, name = name, command = parts)
    }.getOrNull()

    private fun windows(): List<AppCandidate> {
        val dirs = listOfNotNull(
            System.getenv("ProgramData")?.let { File(it, "Microsoft/Windows/Start Menu/Programs") },
            System.getenv("APPDATA")?.let { File(it, "Microsoft/Windows/Start Menu/Programs") },
        )
        val out = mutableListOf<AppCandidate>()
        for (dir in dirs) {
            dir.walkTopDown().filter { it.isFile && it.extension.equals("lnk", true) }.take(500).forEach { file ->
                out += AppCandidate(
                    id = "windows:" + file.absolutePath,
                    name = file.nameWithoutExtension,
                    // A .lnk is opened through the shell, which is how Windows launches a shortcut.
                    command = listOf("cmd", "/c", "start", "", file.absolutePath),
                )
            }
        }
        return out.sortedBy { it.name }
    }

    private fun macos(): List<AppCandidate> {
        val dirs = listOf(File("/Applications"), File(System.getProperty("user.home"), "Applications"))
        val out = mutableListOf<AppCandidate>()
        for (dir in dirs) {
            val apps = dir.listFiles { f -> f.isDirectory && f.name.endsWith(".app") } ?: continue
            for (app in apps) {
                out += AppCandidate(
                    id = "macos:" + app.absolutePath,
                    name = app.nameWithoutExtension,
                    command = listOf("open", app.absolutePath),
                )
            }
        }
        return out.sortedBy { it.name }
    }
}
