package dev.palanca.launcher.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.DataConnectionConfigurationFactory
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import java.net.Inet4Address

/**
 * Servidor FTP para pasar archivos entre el PC y la consola por Wi‑Fi. Va en un servicio en primer
 * plano (con notificación) para que Android no lo cierre mientras se copia. Usuario «heaven» y una
 * contraseña nueva cada vez que se enciende; sin acceso anónimo.
 */
class FtpService : Service() {

    data class State(val running: Boolean = false, val address: String = "", val password: String = "", val root: String = "", val error: String? = null)

    companion object {
        const val PORT = 2121
        const val USER = "heaven"
        val state = MutableStateFlow(State())
        private var server: FtpServer? = null

        fun start(ctx: Context, root: String) {
            val i = Intent(ctx, FtpService::class.java).putExtra("root", root)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, FtpService::class.java)) }

        /** Dirección IPv4 de la consola en la red Wi‑Fi (o Tailscale si no hay otra). */
        fun ip(ctx: Context): String? = runCatching { ipOrThrow(ctx) }.getOrNull()

        private fun ipOrThrow(ctx: Context): String? {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val addrs = cm.allNetworks.mapNotNull { cm.getLinkProperties(it) }.flatMap { it.linkAddresses }.map { it.address }.filterIsInstance<Inet4Address>()
            return (addrs.firstOrNull { !it.hostAddress!!.startsWith("100.") } ?: addrs.firstOrNull())?.hostAddress
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val root = intent?.getStringExtra("root") ?: android.os.Environment.getExternalStorageDirectory().path
        val password = (100000..999999).random().toString()
        val address = "ftp://${ip(this) ?: "?"}:$PORT"
        startForeground(1, notification("Servidor FTP encendido · $address · contraseña $password"),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        runCatching {
            server?.stop()
            val factory = FtpServerFactory()
            val listener = ListenerFactory().apply {
                port = PORT
                // Puertos pasivos fijos: el Explorador de Windows y FileZilla los necesitan.
                dataConnectionConfiguration = DataConnectionConfigurationFactory().apply { passivePorts = "2122-2130" }.createDataConnectionConfiguration()
            }
            factory.addListener("default", listener.createListener())
            val users = PropertiesUserManagerFactory().createUserManager()
            users.save(BaseUser().apply {
                name = USER; this.password = password; homeDirectory = root
                authorities = listOf(WritePermission())
                maxIdleTime = 0
            })
            factory.userManager = users
            server = factory.createServer().also { it.start() }
            state.value = State(true, address, password, root)
        }.onFailure {
            state.value = State(error = it.message ?: "No se pudo encender")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { server?.stop() }
        server = null
        state.value = State()
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("ftp", "Servidor FTP", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, "ftp")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Heaven")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }
}
