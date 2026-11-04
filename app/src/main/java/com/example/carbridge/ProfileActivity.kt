package com.example.carbridge

import android.app.Activity
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.provider.Settings
import android.view.WindowInsets
import android.view.WindowInsetsController
import com.headswap.desk.component.api.ProfileContract
import com.headswap.desk.component.api.ProfileSession
import java.util.concurrent.Executors

/** Owns the short-lived SDK connection. USB, displays and app tasks stay in HeadSwap. */
internal class ProfileActivity : Activity(), ProfileScreen.Actions {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var store: ProfileStore
    internal lateinit var screen: ProfileScreen
        private set
    private var session: ProfileSession? = null
    private lateinit var localMedia: ProfileMediaSource
    private var mediaViaLocal = false
    @Volatile private var generation = 0
    @Volatile private var layoutRevision = 0
    @Volatile private var choiceRevision = 0
    private var started = false
    private var ready = false
    private var target = false
    private var modal = false
    private var dockHidden = false
    private var attempts = 0
    private val retry = Runnable { publishLayout() }
    private val poll = Runnable { readMedia() }
    private val tick = object : Runnable {
        override fun run() {
            if (!started) return
            screen.updateStatus()
            main.postDelayed(this, 3000)
        }
    }
    private val hideDock = Runnable {
        if (started && ready && target && !modal && store.autoHide) {
            dockHidden = true; screen.showDock(false); changed()
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.setDecorFitsSystemWindows(false)
        store = ProfileStore(this)
        localMedia = ProfileMediaSource(this)
        screen = ProfileScreen(this, store, this)
        setContentView(screen.root)
        session = ProfileSession.from(intent)
        // Recreated views start at Home rather than claiming a task that may no longer exist.
    }

    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        generation++; choiceRevision++
        session = ProfileSession.from(intent)
        target = false; modal = false
        screen.closeModal(); screen.setTarget(false)
        if (started) startSession()
    }

    override fun onStart() {
        super.onStart()
        started = true
        window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        startSession()
    }

    private fun startSession() {
        val run = generation
        worker.execute {
            val apps = loadApps()
            main.post { if (started && run == generation) screen.showApps(apps) }
        }
        screen.updateStatus()
        main.removeCallbacks(tick); main.post(tick)
        touchDock()
        changed()
    }

    private fun loadApps(): List<ProfileApp> = runCatching {
        packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != packageName && it.activityInfo.enabled && it.activityInfo.exported && it.activityInfo.permission.isNullOrEmpty() }
            .distinctBy { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
            .map { ProfileApp(ComponentName(it.activityInfo.packageName, it.activityInfo.name),
                it.loadLabel(packageManager).toString(), runCatching { it.loadIcon(packageManager).constantState }.getOrNull()) }
            .sortedBy { it.label.lowercase() }
    }.getOrDefault(emptyList())

    override fun onStop() {
        started = false; ready = false; generation++; choiceRevision++
        main.removeCallbacksAndMessages(null)
        screen.clearMedia()
        worker.execute { localMedia.clear() }
        super.onStop()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        screen.remeasure()
        changed()
    }

    override fun home() {
        choiceRevision++
        target = false; modal = false
        screen.closeModal(); screen.setTarget(false)
        touchDock(); changed()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() { back() }

    override fun back() {
        if (modal) { dismissModal(); return }
        if (!target) { home(); return }
        command { it.sendDashboardAction(ProfileContract.DASH_BACK) }
    }

    override fun launch(app: ProfileApp) {
        if (!started || !ready || session?.canLaunchApps() != true) {
            screen.error(if (session == null) "Выберите CarBridge 0.52 в профилях HeadSwap" else "Сеанс ещё не готов. Нажмите «Домой» для повторной проверки")
            return
        }
        val current = session ?: return
        val run = generation
        val choice = ++choiceRevision
        screen.message("Запрос на открытие: ${app.label}")
        worker.execute {
            if (generation != run || choiceRevision != choice) return@execute
            val queued = current.requestLaunch(app.component)
            main.post {
                if (!started || run != generation || choice != choiceRevision) return@post
                if (queued) {
                    store.record(app.component)
                    modal = false; target = true
                    screen.closeModal(); screen.setTarget(true); screen.refreshRecent()
                    screen.message("Открытие передано HeadSwap. Если появится запрос переноса — подтвердите его на ГУ")
                    touchDock(); changed()
                } else screen.error("HeadSwap отклонил запуск. Выберите другое приложение или нажмите «Домой»")
            }
        }
    }

    override fun music() {
        store.music = !store.music
        screen.arrange(); touchDock(); changed()
    }
    override fun musicSide() { store.musicRight = !store.musicRight; screen.arrange(); changed() }
    override fun phone() { command { it.sendDashboardAction(ProfileContract.DASH_PHONE_TOGGLE) } }
    override fun media(command: Int) {
        val preferLocal = mediaViaLocal
        command {
            if (preferLocal) localMedia.command(command)
            else (it.canUseMedia() && it.sendMediaCommand(command)) || localMedia.command(command)
        }
    }
    override fun stop(app: ProfileApp) {
        dismissModal()
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.component.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle())
            screen.error("На телефоне открыта страница «${app.label}». Нажмите «Остановить» и подтвердите остановку")
        }.onFailure { screen.error("Не удалось открыть системную страницу приложения") }
    }
    override fun catalog() {
        command(opensTarget = true) { it.openCatalog() }
    }
    override fun openModal(kind: String) {
        choiceRevision++; modal = true
        touchDock(); screen.openModal(kind); changed()
    }
    override fun dismissModal() {
        choiceRevision++; modal = false
        screen.closeModal(); touchDock(); changed()
    }
    override fun geometryChanged() { if (started) changed() }
    override fun preferencesChanged() { touchDock(); screen.arrange(); changed() }
    override fun touchDock() {
        main.removeCallbacks(hideDock)
        if (dockHidden) { dockHidden = false; screen.showDock(true); changed() }
        if (started && target && !modal && store.autoHide) main.postDelayed(hideDock, 6000)
    }

    private fun changed() {
        ready = false; layoutRevision++; attempts = 0
        main.removeCallbacks(retry); main.removeCallbacks(poll)
        if (started) main.post(retry)
    }

    private fun publishLayout() {
        if (!started) return
        val current = session
        if (current == null || !current.canUseProfileChrome()) {
            screen.connected(false, false)
            screen.message("Откройте HeadSwap 0.5.0 → Профиль → CarBridge 0.52. Здесь доступен просмотр интерфейса")
            screen.clearMedia()
            return
        }
        if (screen.root.height == 0 || screen.dock.height == 0) return
        val run = generation; val version = layoutRevision
        val showMusic = store.music; val right = store.musicRight
        val showTarget = target && !modal
        val dock = if (dockHidden) screen.revealHeight else screen.dock.height
        worker.execute {
            if (generation != run || layoutRevision != version) return@execute
            val accepted = current.setDashboardLayout(showMusic, right, showTarget, dock)
            main.post {
                if (!started || run != generation || version != layoutRevision) return@post
                ready = accepted
                screen.connected(accepted, current.canUseMedia())
                if (accepted) {
                    screen.message(if (target) "Окно приложения управляется HeadSwap" else "Нажмите значок приложения. Удерживайте — добавить в избранное")
                    readMedia()
                } else if (++attempts < 8) main.postDelayed(retry, 650)
                else screen.message("Сеанс HeadSwap недоступен. Нажмите «Домой» или переподключите ГУ")
            }
        }
    }

    private fun command(opensTarget: Boolean = false, operation: (ProfileSession) -> Boolean) {
        val current = session
        if (!started || !ready || current == null) {
            screen.error("Команда доступна после подключения профиля к HeadSwap")
            return
        }
        val run = generation
        // Media/phone commands must not invalidate a user's pending window selection.
        val choice = if (opensTarget) ++choiceRevision else choiceRevision
        worker.execute {
            if (generation != run || (opensTarget && choiceRevision != choice)) return@execute
            val accepted = operation(current)
            main.post {
                if (!started || run != generation || (opensTarget && choice != choiceRevision)) return@post
                if (!accepted) screen.error("HeadSwap не принял команду: проверьте сеанс и разрешения профиля")
                else if (opensTarget) {
                    modal = false; target = true
                    screen.closeModal(); screen.setTarget(true); touchDock(); changed()
                }
            }
        }
    }

    private fun readMedia() {
        main.removeCallbacks(poll)
        val current = session ?: return
        if (!started || !ready || !store.music) {
            screen.clearMedia(); return
        }
        val run = generation; val version = layoutRevision
        worker.execute {
            if (generation != run || layoutRevision != version) return@execute
            val host = if (current.canUseMedia()) ProfileTrack.fromHost(current.nowPlaying) else null
            val localAccess = localMedia.allowed()
            val local = if (host == null && localAccess) localMedia.snapshot() else null
            val track = host ?: local
            main.post {
                if (!started || run != generation || version != layoutRevision || !store.music) return@post
                mediaViaLocal = host == null && local != null
                screen.mediaState(track, current.canUseMedia(), localAccess, mediaViaLocal)
                main.postDelayed(poll, 2000)
            }
        }
    }
}
