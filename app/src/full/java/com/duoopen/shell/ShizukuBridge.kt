package com.duoopen.shell

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log
import android.view.SurfaceControl
import com.duoopen.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

/**
 * App-side handle on Shizuku and on [DuoShellService]. Optional: everything
 * degrades to the accessibility screenshot and the public hinge sensor when
 * Shizuku isn't installed, running or authorised.
 */
object ShizukuBridge {
    sealed class State(val summary: String) {
        object NotInstalled : State("Shizuku isn't installed.")
        object NotRunning : State("Shizuku is installed but not running. Start it (wireless debugging or a computer), then come back.")
        object NeedsPermission : State("Shizuku is running. Authorise eyeFold to use it.")
        object Denied : State("Shizuku access was declined. Enable eyeFold under Shizuku → Authorised applications.")
        class Ready(val uid: Int, val capture: String?) : State(
            if (capture == null || capture.startsWith("unavailable")) "Shizuku ready (uid $uid); display capture ${capture ?: "unknown"}."
            else "Shizuku ready (uid $uid); fast display capture via $capture.",
        )
    }

    private const val TAG = "DuoShizuku"
    private const val REQUEST_CODE = 7311
    private const val POKE_FIRST_MS = 5_000L
    private const val POKE_MAX_MS = 60_000L
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val REQUEST_BINDER = "rikka.shizuku.intent.action.REQUEST_BINDER"

    private val _state = MutableStateFlow<State>(State.NotInstalled)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    var service: IBinder? = null
        private set
    private var binding = false
    private lateinit var appContext: Context

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = binder
            binding = false
            Log.i(TAG, "shell service connected")
            refresh()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            binding = false
            Log.i(TAG, "shell service disconnected")
            refresh()
        }
    }

    private val binderListener = Shizuku.OnBinderReceivedListener { refresh() }
    private val deadListener = Shizuku.OnBinderDeadListener { service = null; binding = false; refresh() }
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        refresh()
        if (result == PackageManager.PERMISSION_GRANTED) bind()
    }

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(binderListener)
            Shizuku.addBinderDeadListener(deadListener)
            Shizuku.addRequestPermissionResultListener(permissionListener)
        }.onFailure { Log.w(TAG, "Shizuku listeners", it) }
        refresh()
    }

    val installed: Boolean
        get() = runCatching { appContext.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0); true }.getOrDefault(false)

    val ready: Boolean get() = state.value is State.Ready && service != null

    fun refresh() {
        val next: State = runCatching {
            when {
                !installed && !Shizuku.pingBinder() -> State.NotInstalled
                !Shizuku.pingBinder() -> State.NotRunning
                Shizuku.isPreV11() -> State.NotRunning
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> {
                    if (service == null) bind()
                    State.Ready(Shizuku.getUid(), ping()?.getString("capture"))
                }
                Shizuku.shouldShowRequestPermissionRationale() -> State.Denied
                else -> State.NeedsPermission
            }
        }.getOrElse { State.NotRunning }
        _state.value = next
        main.post { schedulePoke() }
    }

    /**
     * While true (the overlay service is running), a stopped Shizuku is
     * re-checked with [poke] at growing intervals, so eyeFold reconnects on
     * its own once Shizuku is started again.
     */
    var autoReconnect = false
        set(value) {
            field = value
            main.post { schedulePoke() }
        }

    private val main = Handler(Looper.getMainLooper())
    private var pokeDelayMs = POKE_FIRST_MS
    private val pokeTask = Runnable {
        poke()
        pokeDelayMs = (pokeDelayMs * 2).coerceAtMost(POKE_MAX_MS)
        schedulePoke()
    }

    private fun schedulePoke() {
        val want = autoReconnect && state.value is State.NotRunning && installed
        if (!want) {
            main.removeCallbacks(pokeTask)
            pokeDelayMs = POKE_FIRST_MS
        } else if (!main.hasCallbacks(pokeTask)) {
            main.postDelayed(pokeTask, pokeDelayMs)
        }
    }

    /**
     * A restarted Shizuku server pushes its binder only to the Shizuku app and
     * to apps that come to the foreground, so a running accessibility service
     * never hears back. Ask the Shizuku app for it instead, over the
     * REQUEST_BINDER broadcast its `rish` shell uses; it answers through
     * [binderReply] with the server's binder, or null while the server is
     * stopped. Calls on that binder are still checked against our grant.
     */
    private fun poke() {
        val data = Bundle().apply { putBinder("binder", binderReply) }
        runCatching {
            appContext.sendBroadcast(
                Intent(REQUEST_BINDER).setPackage(SHIZUKU_PACKAGE).putExtra("data", data),
            )
        }.onFailure { Log.w(TAG, "binder request: ${it.message}") }
    }

    private val binderReply = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != 1) return super.onTransact(code, data, reply, flags)
            val fromShizuku = runCatching {
                appContext.packageManager.getPackagesForUid(Binder.getCallingUid())?.contains(SHIZUKU_PACKAGE) == true
            }.getOrDefault(false)
            val binder = data.readStrongBinder()
            if (fromShizuku && binder != null && binder.pingBinder()) {
                Log.i(TAG, "binder received from the Shizuku app")
                main.post { if (!Shizuku.pingBinder()) Shizuku.onBinderReceived(binder, appContext.packageName) }
            }
            return true
        }
    }

    fun requestPermission() {
        runCatching {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(REQUEST_CODE)
            }
        }.onFailure { Log.w(TAG, "requestPermission", it) }
        refresh()
    }

    private fun args() = Shizuku.UserServiceArgs(ComponentName(appContext, DuoShellService::class.java))
        .daemon(false)
        .processNameSuffix("shell")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    fun bind() {
        if (service != null || binding) return
        binding = true
        runCatching { Shizuku.bindUserService(args(), connection) }
            .onFailure { binding = false; Log.w(TAG, "bindUserService", it) }
    }

    fun unbind() {
        runCatching { Shizuku.unbindUserService(args(), connection, true) }
        service = null
    }

    private fun call(code: Int, write: (Parcel) -> Unit = {}): Bundle? {
        val binder = service ?: return null
        val p = Parcel.obtain()
        val r = Parcel.obtain()
        return try {
            p.writeInterfaceToken(ShellProtocol.TOKEN)
            write(p)
            if (!binder.transact(code, p, r, 0)) return null
            r.readException()
            if (r.dataAvail() > 0) r.readBundle(ShizukuBridge::class.java.classLoader) else Bundle()
        } catch (e: Exception) {
            Log.w(TAG, "shell call $code failed: $e")
            null
        } finally {
            p.recycle()
            r.recycle()
        }
    }

    fun ping(): Bundle? = call(ShellProtocol.PING)

    /** Blocking; call off the main thread. Null if unavailable or the frame had secure content. */
    fun capture(displayId: Int, excluded: List<SurfaceControl>, scale: Float): Bitmap? {
        val b = call(ShellProtocol.CAPTURE) { p ->
            p.writeInt(displayId)
            p.writeInt(excluded.size)
            excluded.forEach { p.writeTypedObject(it, 0) }
            p.writeFloat(scale)
        } ?: return null
        if (!b.getBoolean("ok")) {
            Log.w(TAG, "shell capture failed: ${b.getString("error")}")
            return null
        }
        if (b.getBoolean("secure")) return null
        @Suppress("DEPRECATION")
        return b.getParcelable("bitmap")
    }

    /** Receives readings from the shell-side hinge log reader. */
    private class AngleCallback(private val onAngle: (angle: Float, sampleUptimeMs: Long, source: Int) -> Unit) : Binder() {
        init { attachInterface(null, ShellProtocol.CALLBACK_TOKEN) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != ShellProtocol.CB_ANGLE) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(ShellProtocol.CALLBACK_TOKEN)
            val angle = data.readFloat()
            val at = data.readLong()
            val source = data.readInt()
            onAngle(angle, at, source)
            return true
        }
    }

    private var angleCallback: AngleCallback? = null

    /**
     * Starts the hinge log reader. [wallpaperAction] is the wallpaper command
     * the app will ping Samsung's fold wallpaper with (empty: don't parse the
     * wallpaper's log).
     */
    fun startAngles(wallpaperAction: String, onAngle: (angle: Float, sampleUptimeMs: Long, source: Int) -> Unit): Boolean {
        val cb = AngleCallback(onAngle)
        angleCallback = cb
        return call(ShellProtocol.START_ANGLES) { p ->
            p.writeString(wallpaperAction)
            p.writeStrongBinder(cb)
        } != null
    }

    fun stopAngles() {
        call(ShellProtocol.STOP_ANGLES)
        angleCallback = null
    }

    fun angleStatus(): Bundle? = call(ShellProtocol.ANGLE_STATUS)
}
