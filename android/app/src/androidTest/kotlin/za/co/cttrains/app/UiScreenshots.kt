package za.co.cttrains.app

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry

/** UTP may uninstall the app after tests. Shell-owned screenshots survive that cleanup. */
internal object UiScreenshots {
    /** CI emulators can raise "System UI isn't responding" over the app; close it so taps reach the app. */
    fun closeSystemDialogs() {
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")).use { it.readBytes() }
    }
    fun publishAppFile(packageName: String,name: String) {
        require(Regex("[a-z0-9-]+").matches(name))
        require(Regex("[a-zA-Z0-9.]+").matches(packageName))
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("mkdir -p /data/local/tmp/cttrains-ui-screenshots")).use { it.readBytes() }
        if(android.os.Build.VERSION.SDK_INT>=31) {
            val bytes=ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("run-as $packageName cat files/$name.png")).use { it.readBytes() }
            val pipes=automation.executeShellCommandRw("dd of=/data/local/tmp/cttrains-ui-screenshots/$name.png")
            ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { it.write(bytes) }
            ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes() }
        }
    }
    fun save(name: String) {
        require(Regex("[a-z0-9-]+",RegexOption.IGNORE_CASE).matches(name))
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        fun command(text: String) { ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(text)).use { it.readBytes() } }
        command("mkdir -p /data/local/tmp/cttrains-ui-screenshots")
        command("screencap -p /data/local/tmp/cttrains-ui-screenshots/$name.png")
    }
}
