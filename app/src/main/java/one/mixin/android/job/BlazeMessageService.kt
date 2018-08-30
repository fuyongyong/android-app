package one.mixin.android.job

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.arch.persistence.room.InvalidationTracker
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.support.v4.app.NotificationCompat
import android.support.v4.content.ContextCompat
import android.util.Log
import com.birbit.android.jobqueue.network.NetworkEventProvider
import com.birbit.android.jobqueue.network.NetworkUtil
import com.birbit.android.jobqueue.timer.SystemTimer
import com.google.gson.Gson
import dagger.android.AndroidInjection
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.newSingleThreadContext
import one.mixin.android.Constants
import one.mixin.android.MixinApplication
import one.mixin.android.R
import one.mixin.android.db.FloodMessageDao
import one.mixin.android.db.JobDao
import one.mixin.android.db.MixinDatabase
import one.mixin.android.db.findAckJobsDeferred
import one.mixin.android.db.findFloodMessageDeferred
import one.mixin.android.extension.networkConnected
import one.mixin.android.extension.supportsOreo
import one.mixin.android.receiver.ExitBroadcastReceiver
import one.mixin.android.ui.home.MainActivity
import one.mixin.android.util.ErrorHandler
import one.mixin.android.util.GsonHelper
import one.mixin.android.vo.LinkState
import one.mixin.android.websocket.BlazeAckMessage
import one.mixin.android.websocket.BlazeMessage
import one.mixin.android.websocket.BlazeMessageData
import one.mixin.android.websocket.ChatWebSocket
import one.mixin.android.websocket.createAckListParamBlazeMessage
import org.jetbrains.anko.notificationManager
import org.jetbrains.anko.runOnUiThread
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

class BlazeMessageService : Service(), NetworkEventProvider.Listener {

    companion object {
        val TAG = BlazeMessageService::class.java.simpleName
        const val CHANNEL_NODE = "channel_node"
        const val FOREGROUND_ID = 666666
        const val ACTION_TO_BACKGROUND = "action_to_background"
        const val ACTION_ACTIVITY_RESUME = "action_activity_resume"
        const val ACTION_ACTIVITY_PAUSE = "action_activity_pause"

        fun startService(ctx: Context, action: String? = null) {
            val intent = Intent(ctx, BlazeMessageService::class.java).apply {
                this.action = action
            }
            ContextCompat.startForegroundService(ctx, intent)
        }

        fun stopService(ctx: Context) {
            val intent = Intent(ctx, BlazeMessageService::class.java)
            ctx.stopService(intent)
        }
    }

    private var retrievalThread: MessageRetrievalThread? = null
    private val timer = SystemTimer()
    private val isWait = AtomicBoolean(false)
    private var activeActivities = 0

    @Inject
    lateinit var networkUtil: JobNetworkUtil
    @Inject
    lateinit var database: MixinDatabase
    @Inject
    lateinit var webSocket: ChatWebSocket
    @Inject
    lateinit var floodMessageDao: FloodMessageDao
    @Inject
    lateinit var jobDao: JobDao
    @Inject
    lateinit var jobManager: MixinJobManager
    @Inject
    lateinit var linkState: LinkState

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        AndroidInjection.inject(this)
        super.onCreate()
        retrievalThread = MessageRetrievalThread()
        retrievalThread!!.start()
        startAckJob()
        startFloodJob()
        networkUtil.setListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        detectNotify()
        if (intent == null) return START_STICKY

        when {
            intent.action == ACTION_TO_BACKGROUND -> {
                stopForeground(true)
                return START_STICKY
            }
            intent.action == ACTION_ACTIVITY_RESUME -> incrementActivity()
            intent.action == ACTION_ACTIVITY_PAUSE -> decrementActivity()
        }
        setForegroundIfNecessary()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        if (retrievalThread != null) {
            retrievalThread!!.stopThread()
        }
        stopAckJob()
        stopFloodJob()
    }

    override fun onNetworkChange(networkStatus: Int) {
        if (networkStatus != NetworkUtil.DISCONNECTED) {
            detectNotify()
            runAckJob()
            runFloodJob()
        }
    }

    private fun incrementActivity() {
        activeActivities++
        detectNotify()
    }

    private fun decrementActivity() {
        activeActivities--
        detectNotify()
    }

    private fun detectNotify() {
        synchronized(this) {
            try {
                if (isWait.get()) {
                    isWait.set(false)
                    timer.notifyObject(this)
                }
            } catch (e: Exception) {
            }
        }
    }

    @SuppressLint("NewApi")
    private fun setForegroundIfNecessary() {
        val exitIntent = Intent(this, ExitBroadcastReceiver::class.java).apply {
            action = ACTION_TO_BACKGROUND
        }
        val exitPendingIntent = PendingIntent.getBroadcast(this, 0, exitIntent, 0)

        val builder = NotificationCompat.Builder(this, CHANNEL_NODE)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.background_connection_enabled))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setWhen(0)
            .setDefaults(0)
            .setSound(null)
            .setDefaults(0)
            .setOnlyAlertOnce(true)
            .setSmallIcon(R.drawable.ic_msg_default)
            .addAction(R.drawable.ic_close_black_24dp, getString(R.string.exit), exitPendingIntent)

        val pendingIntent = PendingIntent.getActivity(this, 0, MainActivity.getSingleIntent(this), 0)
        builder.setContentIntent(pendingIntent)

        supportsOreo {
            val channel = NotificationChannel(CHANNEL_NODE,
                MixinApplication.get().getString(R.string.notification_node), NotificationManager.IMPORTANCE_LOW)
            channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            channel.setSound(null, null)
            channel.setShowBadge(false)
            notificationManager.createNotificationChannel(channel)
        }
        startForeground(FOREGROUND_ID, builder.build())
    }

    @Synchronized
    private fun waitForConnectionNecessary() {
        try {
            while (!this.networkConnected()) {
                MixinApplication.appContext.runOnUiThread {
                    linkState.state = LinkState.OFFLINE
                }
                if (!isWait.get()) {
                    isWait.set(true)
                    timer.waitOnObject(this)
                }
            }
        } catch (e: InterruptedException) {
            throw AssertionError(e)
        }
    }

    fun shutdown() {
        webSocket.disconnect()
    }

    private val ackThread by lazy {
        newSingleThreadContext(Constants.ACK_THREAD)
    }

    private fun startAckJob() {
        database.invalidationTracker.addObserver(ackObserver)
    }

    private fun stopAckJob() {
        database.invalidationTracker.removeObserver(ackObserver)
        ackJob?.let {
            if (it.isActive) {
                it.cancel()
            }
        }
    }

    private val ackObserver by lazy {
        object : InvalidationTracker.Observer("jobs") {
            override fun onInvalidated(tables: MutableSet<String>) {
                runAckJob()
            }
        }
    }

    private fun runAckJob() {
        if (ackJob?.isActive == true || !networkConnected()) {
            return
        }
        ackJob = launch(ackThread) {
            ackJobBlock()
        }
    }

    private var ackJob: Job? = null

    private suspend fun ackJobBlock() {
        jobDao.findAckJobsDeferred().await()?.let { list ->
            if (list.isNotEmpty()) {
                list.map { GsonHelper.customGson.fromJson(it.blazeMessage, BlazeAckMessage::class.java) }.let {
                    try {
                        deliver(createAckListParamBlazeMessage(it)).let {
                            jobDao.deleteList(list)
                        }
                    } catch (e: Exception) {
                        runAckJob()
                    }
                }
            }
        }
    }

    private val floodThread by lazy {
        newSingleThreadContext(Constants.FLOOD_THREAD)
    }

    private val messageDecrypt = DecryptMessage()

    private fun startFloodJob() {
        database.invalidationTracker.addObserver(floodObserver)
    }

    private fun stopFloodJob() {
        database.invalidationTracker.removeObserver(floodObserver)
        floodJob?.let {
            if (it.isActive) {
                it.cancel()
            }
        }
    }

    private val floodObserver by lazy {
        object : InvalidationTracker.Observer("flood_messages") {
            override fun onInvalidated(tables: MutableSet<String>) {
                runFloodJob()
            }
        }
    }

    private fun runFloodJob() {
        if (floodJob?.isActive == true) {
            return
        }
        floodJob = launch(floodThread) {
            floodJobBlock()
        }
    }

    private var floodJob: Job? = null

    private suspend fun floodJobBlock() {
        floodMessageDao.findFloodMessageDeferred().await()?.let { message ->
            try {
                messageDecrypt.onRun(Gson().fromJson(message.data, BlazeMessageData::class.java))
                floodMessageDao.delete(message)
            } catch (e: SocketException) {

            } catch (e: Exception) {
                runFloodJob()
            }
        }
    }

    private inner class MessageRetrievalThread internal constructor() :
        Thread("MessageRetrieval"), Thread.UncaughtExceptionHandler {

        fun stopThread() {
            stopThread.set(true)
        }

        override fun uncaughtException(t: Thread?, e: Throwable?) {
            Log.w(TAG, "MessageRetrieval Uncaught exception!")
            Log.w(TAG, e)
        }

        private val stopThread = AtomicBoolean(false)

        init {
            uncaughtExceptionHandler = this
        }

        override fun run() {
            while (!stopThread.get()) {
                waitForConnectionNecessary()
                webSocket.connect()

                try {
                    while (networkConnected() && !stopThread.get()) {
                        sleep(5000)
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Blaze Message service", e)
                } finally {
                    Log.e(TAG, "Shutting down ...")
                    shutdown()
                }
            }
        }
    }

    private fun deliver(blazeMessage: BlazeMessage): Boolean {
        val bm = webSocket.sendMessage(blazeMessage)
        if (bm == null) {
            Thread.sleep(Constants.SLEEP_MILLIS)
            return deliver(blazeMessage)
        } else if (bm.error != null) {
            if (bm.error.code == ErrorHandler.FORBIDDEN) {
                return true
            } else {
                Thread.sleep(Constants.SLEEP_MILLIS)
                return deliver(blazeMessage)
            }
        }
        return true
    }
}