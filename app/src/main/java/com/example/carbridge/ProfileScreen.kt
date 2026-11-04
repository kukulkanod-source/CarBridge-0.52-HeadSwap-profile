package com.example.carbridge

import android.app.Activity
import android.app.ActivityOptions
import android.app.AlertDialog
import android.app.Notification
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.headswap.desk.component.api.ProfileContract
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** CarBridge's Activity-owned chrome. No overlays or independent virtual display. */
internal class ProfileScreen(private val activity: Activity, private val store: ProfileStore, private val actions: Actions) {
    interface Actions {
        fun home(); fun back(); fun launch(app: ProfileApp); fun music(); fun musicSide()
        fun phone(); fun media(command: Int); fun stop(app: ProfileApp); fun catalog(); fun openModal(kind: String)
        fun dismissModal(); fun geometryChanged(); fun preferencesChanged(); fun touchDock()
    }
    private val c get() = activity
    private val green = Color.rgb(65, 210, 125)
    private val blue = Color.rgb(137, 198, 255)
    private val panelColor = Color.rgb(22, 32, 46)
    private var density = c.resources.displayMetrics.density
    private val home = LinearLayout(c)
    private val grid = GridView(c)
    private val status = label("Загрузка приложений…", 13f)
    private val recent = LinearLayout(c)
    private val indicators = LinearLayout(c)
    private val clock = label("", 13f)
    private val mediaPane = SwipeColumn(c, { store.musicRight }) { actions.music() }
    private val art = ImageView(c)
    private val trackTitle = label("Запустите музыку", 17f)
    private val trackArtist = label("", 13f)
    private val mediaAccess = label("", 12f)
    private val play = textButton("▶", "Воспроизвести / пауза") { actions.media(ProfileContract.MEDIA_PLAY_PAUSE) }
    private val mediaSetup = textButton("Разрешить музыку", "Настроить доступ к музыке", 13f) { musicAccess() }
    private val transport = LinearLayout(c)
    private val musicHandle = View(c)
    private val favoritesHandle = View(c)
    private val modalLayer = FrameLayout(c)
    private val reveal = View(c)
    val dock = LinearLayout(c)
    val root = FrameLayout(c)
    val revealHeight get() = px(8)
    private var apps = emptyList<ProfileApp>()
    private var target = false
    private var modalKind: String? = null
    private var editing = false
    private var connected = false
    private var mediaAllowed = false
    private var dockVisible = true
    private var lastWidth = 0
    private var lastHeight = 0
    private var favoritePane: LinearLayout? = null
    private var shadeBody: LinearLayout? = null
    private var notificationSignature = ""
    private var menuApp: ProfileApp? = null

    init {
        root.setBackgroundColor(Color.BLACK)
        home.orientation = LinearLayout.VERTICAL
        home.setPadding(px(20), px(12), px(20), px(10))
        home.background = shape(Color.rgb(12, 19, 30), 18)
        home.clipToOutline = true
        val heading = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(label("Приложения", 23f), LinearLayout.LayoutParams(0, px(44), 1f))
        heading.addView(textButton("★", "Избранное") { actions.openModal("favorites") }, LinearLayout.LayoutParams(px(48), px(44)))
        heading.addView(icon("settings", "Настройки CarBridge") { actions.openModal("settings") }, LinearLayout.LayoutParams(px(48), px(44)))
        home.addView(heading)
        grid.apply {
            horizontalSpacing = px(12); verticalSpacing = px(12)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setPadding(0, px(6), 0, px(6)); clipToPadding = false
        }
        home.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        status.setTextColor(blue)
        home.addView(status, LinearLayout.LayoutParams(-1, px(34)))
        root.addView(home)

        mediaPane.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(12), px(8), px(12), px(12))
            background = shape(Color.rgb(17, 24, 34), 18); clipToOutline = true
        }
        val mediaHeader = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        mediaHeader.addView(textButton("⇄", "Переместить музыкальную панель") { actions.musicSide() }, LinearLayout.LayoutParams(px(44), px(40)))
        mediaHeader.addView(label("Музыка", 16f).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, px(40), 1f))
        mediaHeader.addView(textButton("×", "Скрыть музыку") { actions.music() }, LinearLayout.LayoutParams(px(40), px(40)))
        mediaPane.addView(mediaHeader, LinearLayout.LayoutParams(-1, px(40)))
        art.apply { scaleType = ImageView.ScaleType.FIT_CENTER; setImageDrawable(HuPanelIcons.drawable(c, "music")) }
        mediaPane.addView(art, LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(px(8), px(4), px(8), px(8)) })
        trackTitle.apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER }
        trackArtist.apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER; setTextColor(Color.LTGRAY) }
        mediaAccess.apply { maxLines = 2; gravity = Gravity.CENTER; setTextColor(blue) }
        mediaPane.addView(trackTitle, LinearLayout.LayoutParams(-1, -2))
        mediaPane.addView(trackArtist, LinearLayout.LayoutParams(-1, -2))
        mediaPane.addView(mediaAccess, LinearLayout.LayoutParams(-1, -2))
        mediaPane.addView(mediaSetup, LinearLayout.LayoutParams(-1, px(40)))
        transport.gravity = Gravity.CENTER
        transport.addView(textButton("|◀", "Предыдущий трек") { actions.media(ProfileContract.MEDIA_PREVIOUS) }, LinearLayout.LayoutParams(0, px(52), 1f))
        transport.addView(play, LinearLayout.LayoutParams(0, px(52), 1f))
        transport.addView(textButton("▶|", "Следующий трек") { actions.media(ProfileContract.MEDIA_NEXT) }, LinearLayout.LayoutParams(0, px(52), 1f))
        mediaPane.addView(transport, LinearLayout.LayoutParams(-1, px(52)))
        root.addView(mediaPane)
        handle(musicHandle, "Музыкальная панель", { store.musicRight }) { actions.music() }
        handle(favoritesHandle, "Избранные приложения", { store.favoritesRight }) { actions.openModal("favorites") }
        root.addView(musicHandle); root.addView(favoritesHandle)

        dock.apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(px(6), px(3), px(6), px(3))
            background = shape(Color.rgb(17, 23, 32), 10); clipToOutline = true
        }
        val controls = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        listOf(
            Triple("back", "Назад", { actions.back() }),
            Triple("home", "Домой", { actions.home() }),
            Triple("apps", "Все приложения", { actions.home() }),
            Triple("music", "Музыка / карта", { actions.music() }),
            Triple("phone_off", "Экран телефона", { actions.phone() })
        ).forEach { (kind, description, action) ->
            controls.addView(icon(kind, description, action), LinearLayout.LayoutParams(0, -1, 1f))
        }
        controls.addView(textButton("★", "Избранное") { actions.openModal("favorites") }, LinearLayout.LayoutParams(0, -1, 1f))
        dock.addView(controls, LinearLayout.LayoutParams(0, -1, 1.1f))
        divider(dock)
        recent.gravity = Gravity.CENTER_VERTICAL
        dock.addView(recent, LinearLayout.LayoutParams(0, -1, 0.85f))
        divider(dock)
        indicators.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        indicators.setOnClickListener { actions.openModal("shade") }
        dock.addView(indicators, LinearLayout.LayoutParams(0, -1, 0.55f))
        clock.apply {
            gravity = Gravity.CENTER; setPadding(px(3), 0, px(3), 0)
            contentDescription = "Часы, заряд и уведомления"
            setOnClickListener { actions.openModal("shade") }
        }
        dock.addView(clock, LinearLayout.LayoutParams(px(96), -1))
        root.addView(dock)
        root.addView(reveal, FrameLayout.LayoutParams(px(88), revealHeight, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        reveal.apply {
            background = shape(blue, 4); visibility = View.GONE; contentDescription = "Показать панель CarBridge"
            setOnTouchListener { _, event -> if (event.actionMasked == MotionEvent.ACTION_DOWN) actions.touchDock(); true }
        }
        root.addView(modalLayer, FrameLayout.LayoutParams(-1, -1).apply { bottomMargin = px(HuDockDimensions.HEIGHT_DP) })
        modalLayer.visibility = View.GONE
        root.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            if (lastWidth != r - l || lastHeight != b - t) {
                lastWidth = r - l; lastHeight = b - t
                arrange(); actions.geometryChanged()
            }
        }
        dock.addOnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob -> if (b - t != ob - ot) actions.geometryChanged() }
        root.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN) actions.touchDock(); false }
        arrange()
    }

    fun remeasure() { density = c.resources.displayMetrics.density; arrange() }

    fun arrange() {
        val width = root.width.takeIf { it > 0 } ?: c.resources.displayMetrics.widthPixels
        val height = root.height.takeIf { it > 0 } ?: c.resources.displayMetrics.heightPixels
        val bottom = if (dockVisible) ProfileGeometry.dockHeight(height, density) else revealHeight
        val topHeight = (height - bottom).coerceAtLeast(1)
        val musicWidth = (width * 0.30f).roundToInt()
        home.layoutParams = FrameLayout.LayoutParams(if (store.music) width - musicWidth else width, topHeight).apply {
            leftMargin = if (store.music && !store.musicRight) musicWidth else 0
        }
        grid.numColumns = ProfileGeometry.columns(if (store.music) width - musicWidth else width, density)
        mediaPane.layoutParams = FrameLayout.LayoutParams(musicWidth, topHeight,
            Gravity.TOP or if (store.musicRight) Gravity.RIGHT else Gravity.LEFT)
        mediaPane.visibility = if (store.music) View.VISIBLE else View.GONE
        dock.layoutParams = FrameLayout.LayoutParams(-1, ProfileGeometry.dockHeight(height, density), Gravity.BOTTOM)
        modalLayer.layoutParams = FrameLayout.LayoutParams(-1, topHeight)
        favoritesHandle.layoutParams = FrameLayout.LayoutParams(px(8), px(52), Gravity.CENTER_VERTICAL or
            if (store.favoritesRight) Gravity.RIGHT else Gravity.LEFT)
        // Place coinciding handles at different heights, keeping both available.
        musicHandle.layoutParams = FrameLayout.LayoutParams(px(8), px(52), Gravity.CENTER_VERTICAL or
            if (store.musicRight) Gravity.RIGHT else Gravity.LEFT).apply {
            if (store.favoritesRight == store.musicRight) topMargin = px(120)
        }
        musicHandle.visibility = if (store.music || (target && modalKind == null)) View.GONE else View.VISIBLE
        // An app's native window covers edge handles; use the dock star while a target is visible.
        favoritesHandle.visibility = if (target && modalKind == null) View.GONE else View.VISIBLE
        favoritePane?.layoutParams = favoritesLayout()
    }

    fun setTarget(value: Boolean) { target = value; arrange() }
    fun showDock(visible: Boolean) {
        dockVisible = visible; dock.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        reveal.visibility = if (visible) View.GONE else View.VISIBLE
        arrange()
    }
    fun message(message: String) { status.text = message; if (modalKind == "settings") shadeBody?.contentDescription = message }
    fun error(message: String) { status.text = message; Toast.makeText(c, message, Toast.LENGTH_LONG).show() }
    fun connected(value: Boolean, media: Boolean) {
        connected = value; mediaAllowed = value && media
        mediaAccess.text = when {
            !value -> "Музыка доступна в сеансе HeadSwap"
            !media -> "Разрешите музыку при выборе профиля в HeadSwap"
            else -> ""
        }
        for (i in 0 until transport.childCount) transport.getChildAt(i).isEnabled = mediaAllowed
    }

    fun showApps(entries: List<ProfileApp>) {
        apps = entries
        val history = store.recent()
        val sorted = entries.sortedWith(compareBy<ProfileApp> {
            history.indexOf(it.component.flattenToString()).let { index -> if (index < 0) Int.MAX_VALUE else index }
        }.thenBy { it.label.lowercase() })
        grid.adapter = object : BaseAdapter() {
            override fun getCount() = sorted.size
            override fun getItem(position: Int) = sorted[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, recycled: View?, parent: ViewGroup?): View {
                val app = sorted[position]
                return LinearLayout(c).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    setPadding(px(6), px(10), px(6), px(8))
                    background = shape(Color.rgb(24, 35, 50), 16)
                    addView(appIcon(app), LinearLayout.LayoutParams(px(58), px(58)))
                    addView(label(app.label, 15f).apply {
                        gravity = Gravity.CENTER; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                        setPadding(0, px(8), 0, 0)
                    }, LinearLayout.LayoutParams(-1, px(48)))
                    contentDescription = "Открыть ${app.label}"
                    setOnClickListener { actions.touchDock(); actions.launch(app) }
                    setOnLongClickListener {
                        val selected = app.component.flattenToString() !in store.favorites()
                        store.favorite(app.component, selected)
                        Toast.makeText(c, if (selected) "Добавлено в избранное: ${app.label}" else "Удалено из избранного: ${app.label}", Toast.LENGTH_SHORT).show()
                        true
                    }
                }
            }
        }
        refreshRecent()
        if (modalKind == "favorites") renderFavorites()
    }

    fun refreshRecent() {
        recent.removeAllViews()
        val entries = store.recent().mapNotNull { id -> apps.firstOrNull { it.component.flattenToString() == id } }.take(5)
        if (entries.isEmpty()) {
            recent.addView(label("CarBridge", 12f).apply { gravity = Gravity.CENTER; setTextColor(blue) }, LinearLayout.LayoutParams(-1, -1))
        }
        entries.forEach { app ->
            recent.addView(appIcon(app).apply {
                contentDescription = "Открыть ${app.label}"; setPadding(px(5), 0, px(5), 0)
                setOnClickListener { actions.touchDock(); actions.launch(app) }
                setOnLongClickListener { menuApp = app; actions.openModal("app-menu"); true }
            }, LinearLayout.LayoutParams(0, -1, 1f))
        }
    }

    fun clearMedia() { renderMedia(null) }
    fun renderMedia(track: ProfileTrack?) {
        trackTitle.text = track?.title ?: "Нет активного трека"
        trackArtist.text = track?.artist.orEmpty()
        if (track?.artwork != null) art.setImageBitmap(track.artwork)
        else art.setImageDrawable(HuPanelIcons.drawable(c, "music"))
        play.text = if (track?.playing == true) "Ⅱ" else "▶"
    }
    fun mediaState(track: ProfileTrack?, hostAccess: Boolean, localAccess: Boolean, local: Boolean) {
        renderMedia(track)
        mediaAllowed = connected && (hostAccess || localAccess)
        for (i in 0 until transport.childCount) transport.getChildAt(i).isEnabled = mediaAllowed && track != null
        mediaSetup.visibility = if (track == null && !localAccess) View.VISIBLE else View.GONE
        mediaAccess.text = when {
            local -> "Медиасессия Android · CarBridge"
            track != null -> ""
            !hostAccess && !localAccess -> "Разрешите музыку в HeadSwap или доступ CarBridge к уведомлениям"
            !localAccess -> "HeadSwap не передал трек. Проверьте доступ к уведомлениям"
            else -> "Запустите воспроизведение в музыкальном приложении"
        }
    }

    fun updateStatus() {
        val battery = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (level >= 0 && scale > 0) "${level * 100 / scale}%" else "—"
        clock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()) + "  " + percent
        indicators.removeAllViews()
        val network = runCatching {
            val manager = c.getSystemService(ConnectivityManager::class.java)
            manager.getNetworkCapabilities(manager.activeNetwork)
        }.getOrNull()
        listOfNotNull(
            if (network?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) "wifi" else null,
            if (network?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) "cellular" else null,
            if (network?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) "vpn" else null
        ).take(2).forEach { name ->
            indicators.addView(ImageView(c).apply { setImageDrawable(HuStatusIcon(name, blue)); contentDescription = name }, LinearLayout.LayoutParams(px(20), px(20)))
        }
        val notifications = ProfileNotifications.snapshot()
        if (notifications.isNotEmpty()) indicators.addView(label("• ${notifications.size}", 12f).apply {
            setTextColor(green); gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(px(34), -1))
        val signature = "${ProfileNotifications.current != null}:" + notifications.joinToString { "${it.key}:${it.postTime}" }
        if (modalKind == "shade" && signature != notificationSignature) renderShade()
        notificationSignature = signature
    }

    fun openModal(kind: String) {
        modalKind = kind; editing = false
        modalLayer.visibility = View.VISIBLE
        when (kind) {
            "favorites" -> renderFavorites()
            "shade" -> renderShade()
            "app-menu" -> renderAppMenu()
            else -> renderSettings()
        }
        arrange()
    }
    fun closeModal() {
        modalKind = null; editing = false; favoritePane = null; shadeBody = null
        modalLayer.removeAllViews(); modalLayer.visibility = View.GONE
        arrange()
    }

    private fun favoritesLayout() = FrameLayout.LayoutParams(
        ((root.width.takeIf { it > 0 } ?: c.resources.displayMetrics.widthPixels) * if (editing) .44f else .44f / 3).roundToInt(), -1,
        Gravity.TOP or if (store.favoritesRight) Gravity.RIGHT else Gravity.LEFT
    ).apply { setMargins(0, px(6), 0, px(6)) }

    private fun renderFavorites() {
        modalLayer.removeAllViews(); favoritePane = null
        backdrop()
        val pane = SwipeColumn(c, { store.favoritesRight }) { actions.dismissModal() }.apply {
            orientation = LinearLayout.VERTICAL; background = shape(panelColor, 14); clipToOutline = true; isClickable = true
        }
        favoritePane = pane
        val header = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(label(if (editing) "Выберите приложения" else "Избранное", 12f).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(px(5), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, px(44), 1f))
        header.addView(textButton("×", "Закрыть избранное") { actions.dismissModal() }, LinearLayout.LayoutParams(px(28), px(44)))
        pane.addView(header)
        pane.addView(textButton(if (editing) "✓ Готово" else "+ Приложения", "Выбрать избранные приложения", 12f) {
            editing = !editing; renderFavorites()
        }, LinearLayout.LayoutParams(-1, px(36)))
        pane.addView(textButton(if (store.favoritesRight) "← Влево" else "Вправо →", "Переместить избранное", 12f) {
            store.favoritesRight = !store.favoritesRight; renderFavorites(); arrange()
        }, LinearLayout.LayoutParams(-1, px(36)))
        val body = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val selected = store.favorites()
        val shown = if (editing) apps else selected.mapNotNull { id -> apps.firstOrNull { it.component.flattenToString() == id } }
        if (shown.isEmpty()) body.addView(label(if (editing) "Приложения не найдены" else "Нажмите «+ Приложения»", 12f).apply {
            setPadding(px(6), px(8), px(6), px(8)); maxLines = 5
        })
        shown.forEach { app ->
            val row = LinearLayout(c).apply {
                orientation = if (editing) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                gravity = Gravity.CENTER; setPadding(px(6), px(4), px(6), px(4))
            }
            row.addView(appIcon(app), LinearLayout.LayoutParams(px(36), px(36)))
            row.addView(label(app.label, if (editing) 16f else 12f).apply {
                gravity = Gravity.CENTER; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            }, if (editing) LinearLayout.LayoutParams(0, px(58), 1f) else LinearLayout.LayoutParams(-1, px(36)))
            if (editing) {
                val check = CheckBox(c).apply {
                    isChecked = app.component.flattenToString() in selected
                    contentDescription = "${app.label}: в избранном"
                    setOnCheckedChangeListener { _, value -> store.favorite(app.component, value) }
                }
                row.addView(check, LinearLayout.LayoutParams(px(48), px(48)))
                row.setOnClickListener { check.isChecked = !check.isChecked }
            } else {
                row.contentDescription = "Открыть ${app.label}"
                row.setOnClickListener { actions.launch(app) }
                row.setOnLongClickListener { store.favorite(app.component, false); renderFavorites(); true }
            }
            body.addView(row)
        }
        pane.addView(ScrollView(c).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        modalLayer.addView(pane, favoritesLayout())
    }

    private fun modalPage(title: String): LinearLayout {
        modalLayer.removeAllViews(); favoritePane = null; backdrop()
        val pane = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; isClickable = true; setPadding(px(16), px(8), px(16), px(12))
            background = shape(panelColor, 18); clipToOutline = true
        }
        val header = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(label(title, 22f), LinearLayout.LayoutParams(0, px(48), 1f))
        header.addView(textButton("×", "Закрыть") { actions.dismissModal() }, LinearLayout.LayoutParams(px(48), px(48)))
        pane.addView(header)
        val body = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        pane.addView(ScrollView(c).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        modalLayer.addView(pane, FrameLayout.LayoutParams(-1, -1).apply { setMargins(px(12), px(8), px(12), px(8)) })
        shadeBody = body
        return body
    }

    private fun renderSettings() {
        val body = modalPage("CarBridge · Настройки")
        body.addView(label("Интерфейс 0.52 · профиль HeadSwap 0.5.0", 15f).apply { setTextColor(blue) })
        body.addView(textButton(if (store.musicRight) "Музыка: справа → перенести влево" else "Музыка: слева → перенести вправо", "Сторона музыки", 16f) {
            actions.musicSide(); renderSettings()
        })
        body.addView(textButton(if (store.favoritesRight) "Избранное: справа → перенести влево" else "Избранное: слева → перенести вправо", "Сторона избранного", 16f) {
            store.favoritesRight = !store.favoritesRight; arrange(); renderSettings()
        })
        body.addView(CheckBox(c).apply {
            text = "Скрывать док через 6 секунд в приложении"; setTextColor(Color.WHITE)
            isChecked = store.autoHide
            setOnCheckedChangeListener { _, value -> store.autoHide = value; actions.preferencesChanged() }
        })
        body.addView(textButton("Настроить доступ к уведомлениям", "Открыть доступ к уведомлениям на телефоне", 16f) { notificationAccess() })
        body.addView(textButton("Настроить доступ к музыке", "Настроить доступ к музыке", 16f) { musicAccess() })
        body.addView(label("Уведомления отображаются только после разрешения Android. История на диск не записывается.", 14f).apply { setTextColor(Color.LTGRAY) })
        body.addView(textButton("Каталог HeadSwap", "Открыть каталог HeadSwap", 16f) { actions.catalog() })
        body.addView(label("Подключение к ГУ, звук, DPI и клавиатура настраиваются в HeadSwap. Для ввода нажмите текстовое поле приложения — откроется штатная клавиатура.", 15f).apply {
            setPadding(0, px(14), 0, px(8)); setTextColor(Color.LTGRAY)
        })
        body.addView(label(if (connected) "Профиль подключён к HeadSwap" else "Откройте HeadSwap → Профиль → CarBridge 0.52", 14f).apply { setTextColor(green) })
    }

    private fun renderAppMenu() {
        val app = menuApp ?: return
        modalLayer.removeAllViews(); favoritePane = null; backdrop()
        val pane = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; isClickable = true
            setPadding(px(12), px(8), px(12), px(8)); background = shape(panelColor, 14)
        }
        pane.addView(label(app.label, 17f).apply { maxLines = 2 })
        pane.addView(textButton("Запустить", "Запустить ${app.label}", 18f) { actions.launch(app) }, LinearLayout.LayoutParams(-1, px(44)))
        pane.addView(textButton("Остановить…", "Остановить приложение в Android", 18f) { actions.stop(app) }, LinearLayout.LayoutParams(-1, px(44)))
        pane.addView(label("Остановка — на системной странице приложения на телефоне", 12f).apply { setTextColor(Color.LTGRAY) })
        val selected = app.component.flattenToString() in store.favorites()
        pane.addView(textButton(if (selected) "Убрать из избранного" else "Добавить в избранное", "Изменить избранное для ${app.label}", 14f) {
            store.favorite(app.component, !selected); renderAppMenu()
        }, LinearLayout.LayoutParams(-1, px(42)))
        pane.addView(textButton("Убрать из панели", "Убрать ${app.label} из панели", 14f) {
            store.removeRecent(app.component); refreshRecent(); actions.dismissModal()
        }, LinearLayout.LayoutParams(-1, px(42)))
        modalLayer.addView(pane, FrameLayout.LayoutParams(px(300).coerceAtMost(root.width), -2,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = px(6) })
    }

    private fun musicAccess() {
        AlertDialog.Builder(c).setTitle("Доступ к музыке")
            .setMessage("HeadSwap читает трек через свой доступ к уведомлениям. Можно разрешить его HeadSwap либо дать отдельный доступ CarBridge для чтения и управления плеером. Настройки откроются на телефоне.")
            .setPositiveButton("HeadSwap") { _, _ -> notificationAccess(ComponentName("com.headswap.desk", "com.headswap.desk.cluster.ClusterNavigationListener")) }
            .setNeutralButton("CarBridge") { _, _ -> notificationAccess() }
            .setNegativeButton("Отмена", null).show()
    }

    private fun renderShade() {
        val body = modalPage("Уведомления")
        val shortcuts = LinearLayout(c).apply { gravity = Gravity.CENTER }
        shortcuts.addView(textButton("Музыка", "Музыкальная панель", 15f) { actions.music() }, LinearLayout.LayoutParams(0, px(48), 1f))
        shortcuts.addView(textButton("Телефон", "Экран телефона", 15f) { actions.phone() }, LinearLayout.LayoutParams(0, px(48), 1f))
        shortcuts.addView(textButton("Настройки", "Настройки профиля", 15f) { actions.openModal("settings") }, LinearLayout.LayoutParams(0, px(48), 1f))
        body.addView(shortcuts)
        if (ProfileNotifications.current == null) {
            body.addView(label("Разрешите CarBridge доступ к уведомлениям в настройках телефона", 16f))
            body.addView(textButton("Настроить доступ на телефоне", "Разрешение на уведомления", 16f) { notificationAccess() })
            return
        }
        val notes = ProfileNotifications.snapshot()
        if (notes.isEmpty()) body.addView(label("Новых уведомлений нет", 16f))
        notes.forEach { note -> body.addView(notificationCard(note), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(8) }) }
    }

    private fun notificationCard(note: StatusBarNotification): View {
        val extras = note.notification.extras
        return LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; setPadding(px(14), px(10), px(14), px(10))
            background = shape(Color.rgb(31, 45, 63), 12)
            val appName = runCatching { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(note.packageName, 0)).toString() }.getOrDefault(note.packageName)
            addView(label(appName, 12f).apply { setTextColor(blue) })
            addView(label(extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(), 17f).apply { maxLines = 2 })
            val content = extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            addView(label(content?.toString().orEmpty(), 15f).apply { maxLines = 8; ellipsize = TextUtils.TruncateAt.END })
            val row = LinearLayout(c)
            row.addView(textButton("Открыть", "Открыть $appName", 14f) {
                // Resolve the public launcher through HeadSwap; never fire an arbitrary notification intent on the phone.
                apps.firstOrNull { it.component.packageName == note.packageName }?.let(actions::launch)
                    ?: error("У приложения нет доступного ярлыка")
            }, LinearLayout.LayoutParams(0, px(40), 1f))
            if (note.isClearable) row.addView(textButton("Убрать", "Убрать уведомление", 14f) {
                runCatching { ProfileNotifications.current?.cancelNotification(note.key) }
                row.postDelayed({ if (modalKind == "shade") renderShade() }, 300)
            }, LinearLayout.LayoutParams(0, px(40), 1f))
            addView(row)
        }
    }

    private fun notificationAccess(component: ComponentName = ComponentName(c, ProfileNotifications::class.java)) {
        runCatching {
            c.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle())
        }.onFailure { error("Откройте на телефоне Настройки → Доступ к уведомлениям → CarBridge") }
    }
    private fun backdrop() {
        modalLayer.addView(View(c).apply {
            setBackgroundColor(0x77000000); contentDescription = "Закрыть панель"
            setOnClickListener { actions.dismissModal() }
        }, FrameLayout.LayoutParams(-1, -1))
    }
    private fun divider(parent: LinearLayout) {
        parent.addView(View(c).apply { setBackgroundColor(Color.rgb(68, 88, 110)) }, LinearLayout.LayoutParams(px(1), -1).apply { setMargins(px(3), px(4), px(3), px(4)) })
    }
    private fun appIcon(app: ProfileApp) = ImageView(c).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        setImageDrawable(app.icon?.newDrawable(c.resources)?.mutate() ?: HuPanelIcons.drawable(c, "apps"))
    }
    private fun icon(kind: String, description: String, action: () -> Unit) = ImageView(c).apply {
        contentDescription = description; scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(px(5), px(2), px(5), px(2)); setImageDrawable(HuPanelIcons.drawable(c, kind))
        setOnClickListener { actions.touchDock(); action() }
    }
    private fun label(value: String, size: Float) = TextView(c).apply {
        text = value; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_PX, size * density)
    }
    private fun textButton(value: String, description: String, size: Float = 22f, action: () -> Unit) = TextView(c).apply {
        text = value; contentDescription = description; gravity = Gravity.CENTER
        setTextColor(blue); setTextSize(TypedValue.COMPLEX_UNIT_PX, size * density)
        setPadding(px(4), px(4), px(4), px(4)); minHeight = px(40)
        setOnClickListener { actions.touchDock(); action() }
    }
    private fun handle(view: View, description: String, right: () -> Boolean, action: () -> Unit) {
        view.background = shape(green, 4); view.alpha = .82f; view.contentDescription = description
        view.setOnClickListener { action() }
        var startX = 0f; var startY = 0f
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX = e.rawX; startY = e.rawY; true }
                MotionEvent.ACTION_UP -> {
                    val dx = e.rawX - startX; val dy = e.rawY - startY
                    if (abs(dx) < px(12) && abs(dy) < px(12)) v.performClick()
                    else if ((if (right()) -dx else dx) > px(20) && abs(dx) > abs(dy)) action()
                    true
                }
                else -> true
            }
        }
    }
    private fun swipe(view: View, right: () -> Boolean, close: () -> Unit, horizontalOnly: Boolean) {
        var x = 0f; var y = 0f
        view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x = e.x; y = e.y; true }
                MotionEvent.ACTION_UP -> {
                    val dx = e.x - x; val dy = e.y - y
                    if ((if (right()) dx else -dx) > px(24) && (!horizontalOnly || abs(dx) > abs(dy) * 1.5f)) close()
                    true
                }
                else -> true
            }
        }
    }
    private fun shape(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = px(radius).toFloat() }
    private fun px(value: Int) = (value * density).roundToInt()

    /** Intercept only an outward horizontal swipe so editing and vertical scrolling keep working. */
    private class SwipeColumn(c: Activity, private val right: () -> Boolean, private val close: () -> Unit) : LinearLayout(c) {
        private var x = 0f; private var y = 0f; private var closing = false
        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x = e.x; y = e.y; closing = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - x
                    if ((if (right()) dx else -dx) > 24 * resources.displayMetrics.density && abs(dx) > abs(e.y - y) * 1.5f) { closing = true; return true }
                }
            }
            return false
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_MOVE) {
                val dx = e.x - x
                if ((if (right()) dx else -dx) > 24 * resources.displayMetrics.density && abs(dx) > abs(e.y - y) * 1.5f) closing = true
            }
            if (e.actionMasked == MotionEvent.ACTION_UP && closing) close()
            if (e.actionMasked == MotionEvent.ACTION_CANCEL) closing = false
            return true
        }
    }
}
