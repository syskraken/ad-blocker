package dev.franklin.adblocker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var stats: TextView
    private lateinit var listInfo: TextView
    private lateinit var recent: TextView
    private lateinit var allowlist: EditText
    private lateinit var toggle: Button
    private lateinit var update: Button

    private val ui = Handler(Looper.getMainLooper())
    private val background = Executors.newSingleThreadExecutor()

    private val refresh = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 1000)
        }
    }

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpn()
        } else {
            toast(getString(R.string.permission_declined))
        }
    }

    private val notificationConsent =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* the service runs either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        stats = findViewById(R.id.stats)
        listInfo = findViewById(R.id.list_info)
        recent = findViewById(R.id.recent)
        allowlist = findViewById(R.id.allowlist)
        toggle = findViewById(R.id.toggle)
        update = findViewById(R.id.update)

        allowlist.setText(Prefs.allowlistText(this))

        toggle.setOnClickListener {
            if (AdVpnService.isRunning) stopVpn() else requestVpn()
        }

        update.setOnClickListener { updateBlocklists() }

        findViewById<Button>(R.id.save_allowlist).setOnClickListener {
            Prefs.setAllowlistText(this, allowlist.text.toString())
            BlockList.refreshAllowlist(this)
            toast(getString(R.string.allowlist_saved))
        }

        // Loading the bundled list off the main thread keeps first launch smooth.
        background.execute {
            BlockList.load(applicationContext)
            ui.post { render() }
        }

        requestNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        ui.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(refresh)
    }

    override fun onDestroy() {
        background.shutdownNow()
        super.onDestroy()
    }

    private fun requestVpn() {
        val consent: Intent? = VpnService.prepare(this)
        if (consent != null) vpnConsent.launch(consent) else startVpn()
    }

    private fun startVpn() {
        val intent = Intent(this, AdVpnService::class.java).setAction(AdVpnService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
        ui.postDelayed({ render() }, 400)
    }

    private fun stopVpn() {
        val intent = Intent(this, AdVpnService::class.java).setAction(AdVpnService.ACTION_STOP)
        startService(intent)
        ui.postDelayed({ render() }, 400)
    }

    private fun updateBlocklists() {
        update.isEnabled = false
        update.text = getString(R.string.updating)
        background.execute {
            val message = try {
                val count = BlockList.update(applicationContext)
                getString(R.string.update_done, count)
            } catch (e: Exception) {
                getString(R.string.update_failed, e.message ?: e.javaClass.simpleName)
            }
            ui.post {
                update.isEnabled = true
                update.text = getString(R.string.update_lists)
                toast(message)
                render()
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationConsent.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun render() {
        val running = AdVpnService.isRunning

        status.text = getString(if (running) R.string.status_on else R.string.status_off)
        status.setTextColor(
            ContextCompat.getColor(this, if (running) R.color.status_on else R.color.status_off),
        )
        toggle.text = getString(if (running) R.string.turn_off else R.string.turn_on)

        val queries = AdVpnService.queryCount.get()
        val blocked = AdVpnService.blockedCount.get()
        val percent = if (queries > 0) blocked * 100.0 / queries else 0.0
        stats.text = getString(R.string.stats_format, queries, blocked, percent)

        val last = Prefs.lastUpdate(this)
        val when_ = if (last == 0L) {
            getString(R.string.never_updated)
        } else {
            DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
        }
        listInfo.text = getString(R.string.list_info_format, BlockList.size(), when_)

        val blockedHosts = AdVpnService.recentlyBlocked()
        recent.text = if (blockedHosts.isEmpty()) {
            getString(R.string.nothing_blocked_yet)
        } else {
            blockedHosts.joinToString("\n")
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
