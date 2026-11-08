package party.shulya.freewrite

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors

/**
 * Синхронизация открытой доски с сервером: пока экран доски открыт — правки уходят через
 * несколько секунд после изменения, чужие подтягиваются раз в 20 секунд (и сразу по «Обновить»).
 *
 * Сеть — в фоне, доска меняется только в главном потоке (и не посреди жеста — тогда чуть позже).
 * Картинки выгружаются и скачиваются отдельной очередью, чтобы большие чертежи не задерживали надписи.
 */
class RemoteSync(context: Context, private val transport: SyncTransport, private val host: Host) {

    interface Host {
        fun board(): Board
        /** Можно ли сейчас менять доску (нет жеста, письма, открытого выделения в работе). */
        fun canApply(): Boolean
        /** Сохранить доску с отметками правок — сразу. */
        fun saveBoard()
        fun onRemoteApplied(changed: Int)
        fun onImagesArrived()
        fun onSyncState(state: State)
    }

    class State(val text: String, val error: Boolean = false, val busy: Boolean = false)

    private val main = Handler(Looper.getMainLooper())
    private val net = Executors.newSingleThreadExecutor()
    private val img = Executors.newSingleThreadExecutor()
    private val imageDir = File(context.filesDir, "note_images").apply { mkdirs() }
    private val device = BoardSync.device(context)

    private var started = false
    /** Доска сейчас выкладывается на сервер (второй раз не начинать). */
    var publishing = false
        private set
    private var running = false
    private var again = false
    private var imagesRunning = false
    private var lastOk = 0L
    @Volatile private var closed = false

    private val poll = object : Runnable {
        override fun run() {
            syncNow()
            if (started) main.postDelayed(this, POLL_MS)
        }
    }
    private val kickRunnable = Runnable { syncNow() }

    /** Экран доски открыт — синхронизировать сразу и потом по расписанию. */
    fun start() {
        if (started || !host.board().sync.isShared) return
        started = true
        main.removeCallbacks(poll)
        main.post(poll)
    }

    fun stop() {
        started = false
        main.removeCallbacks(poll)
        main.removeCallbacks(kickRunnable)
    }

    fun close() {
        stop()
        closed = true
        net.shutdown()
        img.shutdown()
    }

    /** Доска изменилась — отправить через несколько секунд (серия правок уйдёт одним разом). */
    fun kick(delayMs: Long = KICK_MS) {
        if (!host.board().sync.isShared) return
        main.removeCallbacks(kickRunnable)
        main.postDelayed(kickRunnable, delayMs)
    }

    /** Отправить своё и получить чужое — сейчас. */
    fun syncNow() {
        if (closed) return
        val b = host.board()
        if (!b.sync.isShared) return
        if (running) { again = true; return }
        running = true
        host.saveBoard()
        val board = host.board()
        val s = board.sync
        val changes = Remote.pending(board)
        val boardId = board.id
        val key = s.remoteKey
        val since = s.remoteSeq
        if (changes.isNotEmpty()) host.onSyncState(State("отправляю…", busy = true))
        net.execute {
            // Отметка последней дошедшей пачки: если связь оборвётся посреди большой выгрузки,
            // в следующий раз продолжим с этого места, а не с начала.
            var pushedTo: String? = null
            try {
                Remote.push(transport, boardId, key, device, changes) { pushedTo = it }
                val pulled = Remote.pull(transport, boardId, key, since)
                val done = pushedTo
                main.post { finish(boardId, done, pulled, null) }
            } catch (t: Throwable) {
                val done = pushedTo
                main.post { finish(boardId, done, null, t) }
            }
        }
    }

    /** Уход с экрана доски: отправить несохранённое на сервер сразу, не дожидаясь паузы. */
    fun flush() {
        main.removeCallbacks(kickRunnable)
        if (!host.board().sync.isShared || closed) return
        val b = host.board()
        if (Remote.pending(b).isNotEmpty() || running) syncNow()
    }

    private fun finish(boardId: String, pushedTo: String?, pulled: Remote.Pulled?, error: Throwable?) {
        if (closed) return
        val b = host.board()
        if (b.id != boardId) { running = false; return }
        val s = b.sync
        if (pushedTo != null && pushedTo > s.pushedRev) s.pushedRev = pushedTo
        if (error != null) {
            running = false
            host.saveBoard()
            host.onSyncState(errorState(error))
            return
        }
        pulled!!
        if (pulled.seq < s.remoteSeq) {
            // Доску на сервере пересоздали — начать с нуля: всё своё отправить, всё чужое получить.
            s.remoteSeq = 0
            s.pushedRev = ""
            running = false
            host.saveBoard()
            kick(500)
            return
        }
        applyWhenIdle(pulled, 0)
    }

    private fun applyWhenIdle(pulled: Remote.Pulled, tries: Int) {
        if (closed) return
        if (pulled.rows.isNotEmpty() && !host.canApply() && tries < 40) {
            main.postDelayed({ applyWhenIdle(pulled, tries + 1) }, 500)
            return
        }
        val b = host.board()
        val s = b.sync
        val n = if (pulled.rows.isEmpty()) 0 else Remote.apply(b, pulled.rows)
        s.remoteSeq = maxOf(s.remoteSeq, pulled.next)
        host.saveBoard()
        if (n > 0) host.onRemoteApplied(n)
        lastOk = System.currentTimeMillis()
        host.onSyncState(State("✓ " + Ui.time(lastOk)))
        running = false
        syncImages()
        if (again) { again = false; kick(300) }
    }

    private fun errorState(t: Throwable): State = when {
        t is SyncException && t.code == "no_board" -> State("доски нет на сервере", error = true)
        t is SyncException && t.code == "bad_key" -> State("нет доступа к доске", error = true)
        t is SyncException && t.code == "busy" -> State("сервер занят — повторю", error = true)
        t is SyncException && t.code == "full" -> State(t.message ?: "на сервере нет места", error = true)
        t is SyncException -> State(t.message ?: "ошибка сервера", error = true)
        else -> State("нет связи с сервером", error = true)
    }

    // ---------------------------------------------------------------- картинки

    /** Выгрузить свои новые картинки и скачать недостающие чужие. */
    fun syncImages() {
        if (closed || imagesRunning) return
        val b = host.board()
        val s = b.sync
        if (!s.isShared) return
        val all = b.images.map { it.file }.distinct()
        val toUpload = all.filter { it !in s.uploadedImages && File(imageDir, it).isFile }
        val toDownload = all.filter { !File(imageDir, it).isFile }
        if (toUpload.isEmpty() && toDownload.isEmpty()) return
        imagesRunning = true
        val boardId = b.id
        val key = s.remoteKey
        img.execute {
            val uploaded = HashSet<String>()
            var downloaded = 0
            var error: Throwable? = null
            try {
                if (toUpload.isNotEmpty()) {
                    val have = Remote.imagesOnServer(transport, boardId, key, toUpload)
                    uploaded += have
                    for (f in toUpload) {
                        if (f in have || closed) continue
                        main.post { host.onSyncState(State("отправляю картинку…", busy = true)) }
                        Remote.uploadImage(transport, boardId, key, File(imageDir, f))
                        uploaded += f
                    }
                }
                for (f in toDownload) {
                    if (closed) break
                    if (!BoardShare.isSafeImageName(f)) continue
                    main.post { host.onSyncState(State("получаю картинку…", busy = true)) }
                    try {
                        Remote.downloadImage(transport, boardId, key, f, File(imageDir, f))
                        downloaded++
                    } catch (e: SyncException) {
                        if (e.code != "no_image") throw e // картинку ещё не выгрузили — в другой раз
                    }
                }
            } catch (t: Throwable) {
                error = t
            }
            main.post {
                imagesRunning = false
                if (closed) return@post
                val cur = host.board()
                if (cur.id == boardId) {
                    cur.sync.uploadedImages += uploaded
                    cur.sync.peerImages += uploaded
                    host.saveBoard()
                }
                if (downloaded > 0) host.onImagesArrived()
                host.onSyncState(if (error != null) errorState(error) else State("✓ " + Ui.time(System.currentTimeMillis())))
            }
        }
    }

    // ---------------------------------------------------------------- выложить доску

    /** Сделать доску общей: выложить на сервер, получить код-приглашение (done в главном потоке). */
    fun publish(done: (invite: String?, error: String?) -> Unit) {
        if (publishing) return
        publishing = true
        host.saveBoard()
        val b = host.board()
        Remote.ensureRevs(b, device)
        val boardId = b.id
        val title = b.title
        host.onSyncState(State("выкладываю…", busy = true))
        net.execute {
            val r = runCatching { Remote.create(transport, boardId, title, device) }
            main.post {
                publishing = false
                if (closed) return@post
                r.onSuccess { (key, invite) ->
                    val cur = host.board()
                    if (cur.id != boardId) return@onSuccess
                    cur.sync.remoteKey = key
                    cur.sync.remoteInvite = invite
                    cur.sync.remoteSeq = 0
                    cur.sync.pushedRev = ""
                    host.saveBoard()
                    done(invite, null)
                    start()
                    syncNow()
                }.onFailure { t ->
                    val msg = if (t is SyncException && t.code == "exists")
                        "Эта доска уже общая. Попросите у коллеги код и подключитесь по нему."
                    else errorState(t).text
                    host.onSyncState(State(msg, error = true))
                    done(null, msg)
                }
            }
        }
    }

    companion object {
        const val POLL_MS = 20_000L
        const val KICK_MS = 3_000L

        /** Подмена сервера в проверках на компьютере. */
        @JvmStatic var testTransport: SyncTransport? = null

        /** Сервер этой сборки (адрес подставляется при сборке APK); null — сервер не настроен. */
        fun transport(): SyncTransport? = testTransport
            ?: BuildConfig.SYNC_URL.takeIf { it.startsWith("https://") }?.let { HttpTransport(it) }
    }
}
