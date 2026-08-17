package dev.franklin.adblocker

import android.Manifest
import android.app.StatusBarManager
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
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
    private lateinit var versionInfo: TextView
    private lateinit var checkUpdates: Button
    private lateinit var adminStatus: TextView
    private lateinit var toggleAdmin: Button
    private lateinit var ownerStatus: TextView
    private lateinit var toggleLockdown: Button

    /** Set once a check finds something newer, so the button can offer it directly. */
    private var availableUpdate: UpdateChecker.Release? = null
    private var renderedUpdateRevision = -1L

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
            if (AdVpnService.isRunning) promptPinThenStop() else requestVpn()
        }

        findViewById<Button>(R.id.change_pin).setOnClickListener { promptChangePin() }
        adminStatus = findViewById(R.id.admin_status)
        toggleAdmin = findViewById(R.id.toggle_admin)
        toggleAdmin.setOnClickListener { toggleUninstallProtection() }
        ownerStatus = findViewById(R.id.owner_status)
        toggleLockdown = findViewById(R.id.toggle_lockdown)
        toggleLockdown.setOnClickListener { toggleLockDown() }

        update.setOnClickListener { updateBlocklists() }

        setUpTileButton()

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

        versionInfo = findViewById(R.id.version_info)
        checkUpdates = findViewById(R.id.check_updates)
        versionInfo.text = getString(R.string.version_format, currentVersion())
        checkUpdates.setOnClickListener {
            val ready = availableUpdate
            if (ready != null) openDownload(ready) else runUpdateCheck(announceResult = true)
        }

        // Loading the bundled list off the main thread keeps first launch smooth.
        background.execute {
            BlockList.load(applicationContext)
            ui.post { render() }
        }

        // A quiet check on launch: it only speaks up if there is something new,
        // rather than interrupting with a dialog every time the app opens.
        runUpdateCheck(announceResult = false)

        requestNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        // Force a rebuild: entries may have been evicted or added while away.
        renderedRevision = -1L
        // Admin state can change in system settings while the app is backgrounded.
        renderAdminState()
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

    /**
     * Android 13 added a way to ask the system to offer the tile in a dialog.
     * Below that the user has to add it by hand, so the button stays hidden and
     * only the instructions are shown.
     */
    private fun setUpTileButton() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        val button = findViewById<Button>(R.id.add_tile)
        button.visibility = Button.VISIBLE
        button.setOnClickListener {
            val statusBar = getSystemService(StatusBarManager::class.java) ?: return@setOnClickListener
            statusBar.requestAddTileService(
                ComponentName(this, QuickTileService::class.java),
                getString(R.string.app_name),
                Icon.createWithResource(this, R.drawable.ic_shield),
                { runnable -> runnable.run() },
                { result ->
                    val added = result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                        result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
                    ui.post { toast(getString(if (added) R.string.tile_added else R.string.tile_not_added)) }
                },
            )
        }
    }

    // --- PIN -----------------------------------------------------------------

    /** Turning blocking on is free; turning it off is what the PIN guards. */
    private fun promptPinThenStop() {
        val input = pinField()
        AlertDialog.Builder(this)
            .setTitle(R.string.pin_prompt_title)
            .setMessage(R.string.pin_prompt_stop)
            .setView(wrap(input))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (Prefs.checkPin(this, input.text.toString())) {
                    stopVpn()
                } else {
                    toast(getString(R.string.pin_wrong))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun promptChangePin() {
        val current = pinField().apply { hint = getString(R.string.current_pin_hint) }
        val replacement = pinField().apply { hint = getString(R.string.new_pin_hint) }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(current)
            addView(replacement)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.change_pin_title)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val entered = replacement.text.toString()
                when {
                    !Prefs.checkPin(this, current.text.toString()) ->
                        toast(getString(R.string.pin_wrong))

                    !Pin.isAcceptable(entered) ->
                        toast(getString(R.string.pin_invalid))

                    else -> {
                        Prefs.setPin(this, entered)
                        toast(getString(R.string.pin_changed))
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pinField(): EditText = EditText(this).apply {
        hint = getString(R.string.pin_hint)
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        setSingleLine()
    }

    private fun wrap(view: android.view.View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val pad = (24 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, 0)
        addView(view)
    }

    // --- uninstall protection -------------------------------------------------

    private fun adminComponent() = ComponentName(this, AdminReceiver::class.java)

    private fun isAdminActive(): Boolean = try {
        getSystemService(DevicePolicyManager::class.java)?.isAdminActive(adminComponent()) == true
    } catch (e: Exception) {
        false
    }

    private fun toggleUninstallProtection() {
        if (isAdminActive()) {
            // Removing protection is gated the same way as stopping blocking.
            val input = pinField()
            AlertDialog.Builder(this)
                .setTitle(R.string.pin_prompt_title)
                .setView(wrap(input))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    if (!Prefs.checkPin(this, input.text.toString())) {
                        toast(getString(R.string.pin_wrong))
                        return@setPositiveButton
                    }
                    try {
                        getSystemService(DevicePolicyManager::class.java)
                            ?.removeActiveAdmin(adminComponent())
                        toast(getString(R.string.protection_removed))
                    } catch (e: Exception) {
                        toast(e.message ?: getString(R.string.admin_unavailable))
                    }
                    renderAdminState()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }

        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent())
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                getString(R.string.admin_explanation),
            )
        try {
            startActivity(intent)
        } catch (e: Exception) {
            toast(getString(R.string.admin_unavailable))
        }
    }

    private fun renderAdminState() {
        val active = isAdminActive()
        adminStatus.text = getString(if (active) R.string.admin_on else R.string.admin_off)
        toggleAdmin.text =
            getString(if (active) R.string.remove_protection else R.string.protect_uninstall)

        val owner = DeviceOwner.isDeviceOwner(this)
        val locked = owner && DeviceOwner.isLockedDown(this)

        toggleLockdown.visibility = if (owner) Button.VISIBLE else Button.GONE
        toggleLockdown.text =
            getString(if (locked) R.string.remove_lockdown else R.string.lock_down)
        ownerStatus.text = getString(
            when {
                locked -> R.string.owner_locked
                owner -> R.string.owner_available
                else -> R.string.owner_absent
            },
        )
    }

    /** Applying is free; lifting the lock-down needs the PIN, like stopping does. */
    private fun toggleLockDown() {
        if (!DeviceOwner.isLockedDown(this)) {
            report(DeviceOwner.lockDown(this))
            renderAdminState()
            return
        }

        val input = pinField()
        AlertDialog.Builder(this)
            .setTitle(R.string.pin_prompt_title)
            .setView(wrap(input))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (!Prefs.checkPin(this, input.text.toString())) {
                    toast(getString(R.string.pin_wrong))
                    return@setPositiveButton
                }
                report(DeviceOwner.release(this))
                renderAdminState()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Shows the per-policy outcome: OEMs honour these inconsistently. */
    private fun report(notes: List<String>) {
        AlertDialog.Builder(this)
            .setMessage(notes.joinToString("\n"))
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun currentVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "0"
    } catch (e: Exception) {
        "0"
    }

    /**
     * @param announceResult when false the check is silent unless an update
     *   exists, so the launch-time check cannot spam toasts on a flaky network.
     */
    private fun runUpdateCheck(announceResult: Boolean) {
        if (announceResult) {
            checkUpdates.isEnabled = false
            checkUpdates.text = getString(R.string.checking_updates)
        }

        val version = currentVersion()
        background.execute {
            val result = UpdateChecker.check(version)
            ui.post {
                checkUpdates.isEnabled = true
                applyUpdateResult(result, announceResult)
            }
        }
    }

    private fun applyUpdateResult(result: UpdateChecker.Result, announceResult: Boolean) {
        when (result) {
            is UpdateChecker.Result.Available -> {
                availableUpdate = result.release
                versionInfo.text = getString(R.string.update_available, result.release.version)
                checkUpdates.text = getString(
                    if (result.release.apkUrl != null) R.string.install_update else R.string.download_update,
                    result.release.version,
                )
            }

            is UpdateChecker.Result.UpToDate -> {
                availableUpdate = null
                versionInfo.text = getString(R.string.version_format, currentVersion())
                checkUpdates.text = getString(R.string.check_updates)
                if (announceResult) toast(getString(R.string.up_to_date))
            }

            is UpdateChecker.Result.NoReleases -> {
                checkUpdates.text = getString(R.string.check_updates)
                if (announceResult) toast(getString(R.string.no_releases))
            }

            is UpdateChecker.Result.Failed -> {
                checkUpdates.text = getString(R.string.check_updates)
                if (announceResult) toast(getString(R.string.update_check_failed, result.reason))
            }
        }
    }

    /**
     * Installs in place when the release carries an APK, falling back to the
     * browser when it does not, or when the user declines the install permission.
     */
    private fun openDownload(release: UpdateChecker.Release) {
        if (release.apkUrl == null) {
            openInBrowser(release.pageUrl)
            return
        }

        if (!Updater.canInstall(this)) {
            promptForInstallPermission(release)
            return
        }

        checkUpdates.isEnabled = false
        background.execute { Updater.downloadAndInstall(applicationContext, release) }
    }

    /**
     * Android will not let an app install anything until the user turns this on
     * per-app, and the setting lives in a screen only they can act on.
     */
    private fun promptForInstallPermission(release: UpdateChecker.Release) {
        AlertDialog.Builder(this)
            .setTitle(R.string.allow_installs_title)
            .setMessage(R.string.allow_installs_body)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                try {
                    startActivity(Updater.unknownSourcesSettings(this))
                } catch (e: Exception) {
                    openInBrowser(release.pageUrl)
                }
            }
            .setNeutralButton(R.string.use_browser) { _, _ ->
                openInBrowser(release.apkUrl ?: release.pageUrl)
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun openInBrowser(url: String) {
        if (url.isBlank()) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            toast(getString(R.string.no_browser))
        }
    }

    /** Reflects download and install progress into the update section. */
    private fun renderUpdateProgress() {
        val revision = Updater.revision()
        if (revision == renderedUpdateRevision) return
        renderedUpdateRevision = revision

        val release = availableUpdate
        when (val state = Updater.status) {
            is Updater.Status.Idle -> Unit

            is Updater.Status.Downloading ->
                checkUpdates.text = getString(R.string.downloading_percent, state.percent)

            is Updater.Status.Verifying ->
                checkUpdates.text = getString(R.string.verifying_download)

            is Updater.Status.Installing ->
                checkUpdates.text = getString(R.string.starting_install)

            is Updater.Status.Success -> {
                checkUpdates.isEnabled = true
                versionInfo.text = getString(R.string.install_succeeded)
                checkUpdates.text = getString(R.string.check_updates)
            }

            is Updater.Status.Failed -> {
                checkUpdates.isEnabled = true
                checkUpdates.text = release?.let {
                    getString(R.string.install_update, it.version)
                } ?: getString(R.string.check_updates)
                toast(getString(R.string.install_failed, state.reason))
                Updater.reset()
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

        renderUpdateProgress()
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
