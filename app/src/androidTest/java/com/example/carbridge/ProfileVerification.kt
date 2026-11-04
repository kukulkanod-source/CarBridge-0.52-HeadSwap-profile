package com.example.carbridge

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import com.headswap.desk.component.api.IDesktopPanelSession
import com.headswap.desk.component.api.ProfileContract
import com.headswap.desk.component.api.ProfileNowPlaying
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Emulator-only fake host. Never packaged in the deliverable APK. */
class ProfileVerification : Instrumentation() {
    private val passed = mutableListOf<String>()
    private var active: ProfileActivity? = null
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        try {
            verify()
            result.putString("stream", "\nPASS (${passed.size}):\n" + passed.joinToString("\n"))
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "\nFAIL after ${passed.size}: ${error.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            active?.let { ui { it.finish() } }
        }
    }

    private fun verify() {
        val prefs = targetContext.getSharedPreferences("carbridge_profile", 0)
        prefs.edit().clear().commit()
        val standalone = launch(null)
        waitFor("standalone has visible app list") { views(standalone.screen.root).any { it.contentDescription?.startsWith("Открыть ") == true } }
        standalone.apply {
            ui { home() }
            click("Избранное")
        }
        capture("standalone-favorites")
        ui { standalone.finish() }; waitForIdleSync()

        val host = FakeHost()
        val activity = launch(host)
        waitFor("layout retries recover after two rejected first frames") { host.acceptedLayouts > 0 }
        check(host.calls >= 3) { "Expected bounded layout retry" }
        check(host.layout?.height == activity.screen.dock.height) { "Published dock differs from rendered dock" }
        pass("Dock reservation matches actual pixels")
        waitFor("catalog loads public launcher components") { views(activity.screen.root).any { it.contentDescription?.startsWith("Открыть ") == true } }
        capture("home-1280x720")

        ui { activity.music() }
        waitFor("music uses host metadata") { views(activity.screen.root).filterIsInstance<TextView>().any { it.text.toString() == "Тестовый трек" } }
        capture("music-1280x720")
        click("Воспроизвести / пауза")
        waitFor("transport command goes through SDK") { host.mediaCommands.contains(ProfileContract.MEDIA_PLAY_PAUSE) }
        ui { activity.musicSide() }
        waitFor("music switches side in host geometry") { host.layout?.right == true }

        val app = ProfileApp(ComponentName("com.android.settings", "com.android.settings.Settings"), "Настройки", null)
        ui { activity.launch(app) }
        waitFor("launch sends exact component and reveals target") { host.launches.lastOrNull() == app.component && host.layout?.target == true }
        val favoritesBeforeLongPress = ProfileStore(targetContext).favorites()
        val dockApp = views(activity.screen.root).first { it is android.widget.ImageView && it.contentDescription == "Открыть Настройки" }
        ui { dockApp.performLongClick() }
        waitFor("dock long press opens app menu") { views(activity.screen.root).any { it.contentDescription == "Остановить приложение в Android" } }
        check(ProfileStore(targetContext).favorites() == favoritesBeforeLongPress) { "Long press silently changed favorites" }
        ui { activity.dismissModal() }
        waitFor("closing app menu restores target") { host.layout?.target == true }
        ui { activity.openModal("favorites") }
        waitFor("favorites hides target without ending its task") { host.layout?.target == false }
        click("Выбрать избранные приложения")
        waitFor("favorites editor loads choices") { views(activity.screen.root).any { it is CheckBox } }
        val selected = views(activity.screen.root).filterIsInstance<CheckBox>().first()
        ui { selected.performClick() }
        val favoriteBefore = ProfileStore(targetContext).favorites()
        check(favoriteBefore.isNotEmpty()) { "Selection not saved" }
        click("Выбрать избранные приложения")
        capture("favorites-1280x720")
        ui { activity.back() }
        waitFor("closing favorites restores native app area") { host.layout?.target == true }
        ui { activity.back() }
        waitFor("Back goes to host") { host.actions.contains(ProfileContract.DASH_BACK) }
        ui { activity.home() }
        waitFor("Home hides target") { host.layout?.target == false }

        host.rejectLaunch = true
        val acceptedCount = host.acceptedLayouts
        ui { activity.launch(app) }
        waitFor("rejected launch is reported") { views(activity.screen.root).filterIsInstance<TextView>().any { it.text.contains("отклонил запуск") } }
        check(host.layout?.target == false) { "Rejected request revealed target" }
        check(host.acceptedLayouts == acceptedCount) { "Rejected launch changed geometry" }
        pass("Rejected launch leaves local catalog accessible")
        host.rejectLaunch = false

        ui { activity.openModal("shade") }
        waitFor("notification denial has setup path") { views(activity.screen.root).any { it.contentDescription == "Разрешение на уведомления" } }
        capture("notifications-1280x720")
        ui { activity.dismissModal() }

        // Change the SDK envelope in the same Activity; late old-session results cannot win.
        val denied = FakeHost(initialRejections = 0)
        ui { activity.onNewIntent(envelope(denied, media = false)) }
        waitFor("replacement session publishes new layout") { denied.acceptedLayouts > 0 }
        waitFor("media access denial clears old track") {
            views(activity.screen.root).filterIsInstance<TextView>().none { it.text.toString() == "Тестовый трек" }
        }
        check(denied.mediaReads == 0) { "Read media without consent" }
        pass("Media stays unread without negotiated consent")
        check(ProfileStore(targetContext).favorites() == favoriteBefore) { "Favorites lost on reconnect" }
        pass("Favorites survive session replacement")
        val oldCalls = host.calls
        Thread.sleep(2300)
        check(host.calls == oldCalls) { "Old session still receiving calls" }
        pass("Replaced session stops receiving SDK calls")
        check(!host.calledOnMain && !denied.calledOnMain) { "Binder operation on UI thread" }
        pass("All host operations run off UI thread")

        // Bound retries after an expired session, with no repeated launches.
        val expired = FakeHost(initialRejections = Int.MAX_VALUE)
        ui { activity.onNewIntent(envelope(expired)) }
        waitFor("expired session exposes retry path", 8500) { expired.calls == 8 }
        Thread.sleep(900)
        check(expired.calls == 8 && expired.launches.isEmpty()) { "Unbounded retry or automatic launch" }
        pass("Expired session stops after eight layout attempts")

        // Phone/portrait preview remains a local UI, with no projection code.
        ui { activity.onNewIntent(Intent(targetContext, ProfileActivity::class.java)) }
        waitFor("standalone reset reports missing host") { views(activity.screen.root).filterIsInstance<TextView>().any { it.text.contains("Откройте HeadSwap 0.5.0") } }
        ui { activity.openModal("settings") }
        capture("settings-1280x720")
    }

    private fun launch(host: FakeHost?): ProfileActivity {
        val intent = if (host == null) Intent(targetContext, ProfileActivity::class.java) else envelope(host)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return (startActivitySync(intent) as ProfileActivity).also { active = it; waitForIdleSync() }
    }
    private fun envelope(host: FakeHost, media: Boolean = true) = Intent(ProfileContract.ACTION).apply {
        component = ComponentName(targetContext, ProfileActivity::class.java)
        putExtra(ProfileContract.EXTRA_SESSION, Bundle().apply {
            putInt(ProfileContract.API_KEY, 1)
            putInt(ProfileContract.CAPABILITIES_KEY, ProfileContract.CAP_PROFILE_CHROME or ProfileContract.CAP_LAUNCH_APP or
                ProfileContract.CAP_OPEN_CATALOG or if (media) ProfileContract.CAP_MEDIA else 0)
            putBinder(ProfileContract.BINDER_KEY, host)
        })
    }
    private fun click(description: String) {
        val view = views(active!!.screen.root).firstOrNull { it.contentDescription == description && it.isShown }
            ?: error("Missing clickable $description")
        ui { view.performClick() }
    }
    private fun ui(block: () -> Unit) { runOnMainSync(block); waitForIdleSync() }
    private fun views(root: View): List<View> {
        val all = mutableListOf<View>()
        runOnMainSync {
            fun visit(v: View) { all.add(v); if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i)) }
            visit(root)
        }
        return all
    }
    private fun waitFor(name: String, timeout: Long = 5500, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < until) { waitForIdleSync(); if (condition()) { pass(name); return }; Thread.sleep(60) }
        error("Timeout: $name")
    }
    private fun pass(name: String) { passed.add(name) }
    private fun capture(name: String) {
        ui {
            val view = active!!.screen.root
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val dir = File(targetContext.filesDir, "qa").apply { mkdirs() }
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private data class Layout(val music: Boolean, val right: Boolean, val target: Boolean, val height: Int)
    private class FakeHost(private val initialRejections: Int = 2) : IDesktopPanelSession.Stub() {
        @Volatile var calls = 0
        @Volatile var acceptedLayouts = 0
        @Volatile var mediaReads = 0
        @Volatile var calledOnMain = false
        @Volatile var layout: Layout? = null
        @Volatile var rejectLaunch = false
        val launches = CopyOnWriteArrayList<ComponentName>()
        val mediaCommands = CopyOnWriteArrayList<Int>()
        val actions = CopyOnWriteArrayList<Int>()
        private fun offMain() { if (Looper.myLooper() == Looper.getMainLooper()) calledOnMain = true }
        override fun setDashboardLayout(music: Boolean, right: Boolean, target: Boolean, height: Int): Boolean {
            offMain(); calls++
            if (calls <= initialRejections) return false
            layout = Layout(music, right, target, height); acceptedLayouts++; return true
        }
        override fun requestLaunch(pkg: String, name: String): Boolean { offMain(); if (rejectLaunch) return false; launches.add(ComponentName(pkg, name)); return true }
        override fun requestCatalog(): Boolean { offMain(); return true }
        override fun getNowPlaying(): Bundle { offMain(); mediaReads++; return ProfileNowPlaying.encode("Тестовый трек", "CarBridge QA", true, null) }
        override fun sendMediaCommand(command: Int): Boolean { offMain(); mediaCommands.add(command); return true }
        override fun sendDashboardAction(action: Int): Boolean { offMain(); actions.add(action); return true }
    }
}
