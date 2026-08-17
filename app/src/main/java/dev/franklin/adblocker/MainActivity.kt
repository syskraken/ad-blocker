package dev.franklin.adblocker

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var stats: TextView
    private lateinit var listInfo: TextView
    private lateinit var blockedList: LinearLayout
    private lateinit var blockedEmpty: TextView
    private lateinit var allowlist: EditText
    private lateinit var blocklist: EditText
    private lateinit var toggle: Button
    private lateinit var update: Button
    private lateinit var adultFilter: CheckBox

    private val ui = Handler(Looper.getMainLooper())
    private val background = Executors.newSingleThreadExecutor()

    /** Rebuilding 200 rows every second would be wasteful; only do it on change. */
    private var renderedRevision = -1L

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
        blockedList = findViewById(R.id.blocked_list)
        blockedEmpty = findViewById(R.id.blocked_empty)
        allowlist = findViewById(R.id.allowlist)
        blocklist = findViewById(R.id.blocklist)
        toggle = findViewById(R.id.toggle)
        update = findViewById(R.id.update)
        adultFilter = findViewById(R.id.adult_filter)

        adultFilter.isChecked = Prefs.adultFilterEnabled(this)
        adultFilter.setOnCheckedChangeListener { _, checked ->
            Prefs.setAdultFilterEnabled(this, checked)
            // Enabling downloads the extra lists; disabling deletes them. Either
            // way the change is only real once update() has run.
            updateBlocklists()
        }

        allowlist.setText(Prefs.allowlistText(this))
        blocklist.setText(Prefs.blocklistText(this))

        toggle.setOnClickListener {
            if (AdVpnService.isRunning) stopVpn() else requestVpn()
        }

        update.setOnClickListener { updateBlocklists() }

        findViewById<Button>(R.id.save_allowlist).setOnClickListener {
            Prefs.setAllowlistText(this, allowlist.text.toString())
            BlockList.refreshUserLists(this)
            toast(getString(R.string.allowlist_saved))
        }

        findViewById<Button>(R.id.save_blocklist).setOnClickListener {
            Prefs.setBlocklistText(this, blocklist.text.toString())
            BlockList.refreshUserLists(this)
            toast(getString(R.string.blocklist_saved, BlockList.customSize()))
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
        // Force a rebuild: entries may have been evicted or added while away.
        renderedRevision = -1L
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
        val updatedWhen = if (last == 0L) {
            getString(R.string.never_updated)
        } else {
            DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
        }
        listInfo.text = getString(R.string.list_info_format, BlockList.size(), updatedWhen)

        val revision = BlockLog.revision()
        if (revision != renderedRevision) {
            renderedRevision = revision
            rebuildBlockedList()
        }
    }

    private fun rebuildBlockedList() {
        val entries = BlockLog.snapshot(100)
        blockedEmpty.visibility = if (entries.isEmpty()) TextView.VISIBLE else TextView.GONE

        blockedList.removeAllViews()
        val inflater = LayoutInflater.from(this)
        val now = System.currentTimeMillis()

        for (entry in entries) {
            val row = inflater.inflate(R.layout.item_blocked, blockedList, false)
            row.findViewById<TextView>(R.id.domain).text = entry.host
            row.findViewById<TextView>(R.id.detail).text =
                getString(R.string.blocked_detail, entry.count, relativeTime(entry.lastSeen, now))
            row.setOnClickListener { showBlockedDomain(entry) }
            blockedList.addView(row)
        }
    }

    private fun relativeTime(at: Long, now: Long): CharSequence =
        DateUtils.getRelativeTimeSpanString(at, now, DateUtils.SECOND_IN_MILLIS)

    private fun showBlockedDomain(entry: BlockLog.Entry) {
        val body = getString(
            R.string.blocked_dialog_body,
            entry.count,
            relativeTime(entry.lastSeen, System.currentTimeMillis()),
        )

        AlertDialog.Builder(this)
            .setTitle(entry.host)
            .setMessage(body)
            .setPositiveButton(R.string.allow_domain) { _, _ -> allowDomain(entry.host) }
            .setNeutralButton(R.string.copy_domain) { _, _ -> copyDomain(entry.host) }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    /** Appends to the allowlist, leaving whatever the user already typed intact. */
    private fun allowDomain(host: String) {
        if (BlockList.isAllowed(host)) {
            toast(getString(R.string.domain_allowed, host))
            return
        }

        val existing = Prefs.allowlistText(this).trimEnd()
        val updated = if (existing.isEmpty()) host else "$existing\n$host"

        Prefs.setAllowlistText(this, updated)
        BlockList.refreshUserLists(this)
        allowlist.setText(updated)

        toast(getString(R.string.domain_allowed, host))
    }

    private fun copyDomain(host: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("domain", host))
        toast(getString(R.string.domain_copied, host))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
