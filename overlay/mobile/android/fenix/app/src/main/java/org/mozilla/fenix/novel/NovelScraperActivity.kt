/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.novel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.StrictMode
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mozilla.components.ui.icons.R as iconsR

/** One screen of the novel scraper; the activity shows one at a time and keeps a stack for the back button. */
abstract class NovelScreen(protected val activity: NovelScraperActivity) {
    protected val ui: NovelUi = NovelUi(activity)
    var view: View? = null

    abstract val title: String

    abstract fun build(): View

    /** Called every time the screen comes (back) to the front. */
    open fun onShown() = Unit

    open fun buildMenu(menu: android.view.Menu) = Unit
}

/**
 * The novel scraper: reads a novel from a website and saves it as an EPUB file. It is opened from the main menu of the
 * browser ("Download novel", which brings the page that is open), from Settings, or by sharing a page to it.
 */
class NovelScraperActivity : AppCompatActivity() {
    val prefs: NovelPrefs by lazy { NovelPrefs(this) }
    val plugins: NovelPluginStore by lazy { NovelPluginStore(this) }
    val engine: NovelEngine by lazy { NovelEngine(NovelFetcher(this), plugins, prefs) }
    val fetcher: NovelFetcher by lazy { NovelFetcher(this) }
    val library: NovelLibrary by lazy { NovelLibrary(this) }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var container: FrameLayout
    private lateinit var mainScreen: ScraperScreen
    private val stack = ArrayList<NovelScreen>()

    /** The StrictMode policy of the main thread before this screen was opened (see onCreate). */
    private var originalThreadPolicy: StrictMode.ThreadPolicy? = null

    private var pendingUri: ((Uri?) -> Unit)? = null
    private var pendingMany: ((List<Uri>) -> Unit)? = null

    private val epubPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/epub+zip")) { uri ->
        finishPick(uri)
    }
    private val markdownPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        finishPick(uri)
    }
    private val jsonPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        finishPick(uri)
    }
    private val openPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val done = pendingMany
        pendingMany = null
        done?.invoke(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Debug builds of Fenix stop the app at any read or write of a file or of the settings on the main thread
        // (StrictMode with penaltyDeath). The screens of the scraper read their settings, plugins and library on the main
        // thread, so the check is switched off while the scraper is open and restored when it is closed.
        originalThreadPolicy = StrictMode.getThreadPolicy()
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.LAX)
        super.onCreate(savedInstanceState)
        toolbar = MaterialToolbar(this).apply {
            setNavigationIcon(iconsR.drawable.mozac_ic_back_24)
            setNavigationContentDescription("Back")
            setNavigationOnClickListener { goBack() }
        }
        container = FrameLayout(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toolbar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(container, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = goBack()
            },
        )
        mainScreen = ScraperScreen(this)
        if (stack.isEmpty()) show(mainScreen)
        handleIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        originalThreadPolicy?.let { StrictMode.setThreadPolicy(it) }
        originalThreadPolicy = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        NovelJob.listener = { onJobChanged() }
        onJobChanged()
    }

    override fun onStop() {
        super.onStop()
        NovelJob.listener = null
    }

    private fun handleIntent(intent: Intent?) {
        val address = when (intent?.action) {
            Intent.ACTION_SEND -> firstUrl(intent?.getStringExtra(Intent.EXTRA_TEXT))
            else -> intent?.getStringExtra(EXTRA_URL)
        }
        if (!address.isNullOrBlank()) loadInScraper(address)
    }

    /** Shows the main screen and reads the novel page at [address]. */
    fun loadInScraper(address: String) {
        popToRoot()
        mainScreen.load(address)
    }

    private fun firstUrl(text: String?): String? = text?.let { Regex("https?://\\S+").find(it)?.value }

    // region Screens

    fun show(screen: NovelScreen) {
        stack.add(screen)
        render(screen)
    }

    private fun render(screen: NovelScreen) {
        container.removeAllViews()
        val view = screen.view ?: screen.build().also { screen.view = it }
        container.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        toolbar.title = screen.title
        toolbar.menu.clear()
        screen.buildMenu(toolbar.menu)
        screen.onShown()
    }

    fun goBack() {
        if (stack.size > 1) {
            stack.removeAt(stack.size - 1)
            render(stack[stack.size - 1])
        } else {
            finish()
        }
    }

    private fun popToRoot() {
        while (stack.size > 1) stack.removeAt(stack.size - 1)
        render(stack[0])
    }

    fun showMain() = popToRoot()

    private fun onJobChanged() {
        if (NovelJob.status.running) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        mainScreen.showProgress()
    }

    // endregion

    // region Pickers and messages

    fun pickEpub(name: String, done: (Uri?) -> Unit) {
        pendingUri = done
        epubPicker.launch(name)
    }

    fun pickMarkdown(name: String, done: (Uri?) -> Unit) {
        pendingUri = done
        markdownPicker.launch(name)
    }

    fun pickJson(name: String, done: (Uri?) -> Unit) {
        pendingUri = done
        jsonPicker.launch(name)
    }

    fun pickFiles(done: (List<Uri>) -> Unit) {
        pendingMany = done
        openPicker.launch(arrayOf("*/*"))
    }

    private fun finishPick(uri: Uri?) {
        val done = pendingUri
        pendingUri = null
        done?.invoke(uri)
    }

    /**
     * Starts work on the main thread's scope that may suspend. A problem that nobody caught is shown in a message, so it does
     * not stop the whole browser.
     */
    fun launchSafely(block: suspend CoroutineScope.() -> Unit) {
        val handler = CoroutineExceptionHandler { _, error ->
            runOnUiThread {
                message("Something went wrong", (error.javaClass.simpleName + ": " + (error.message ?: "")).trim())
            }
        }
        lifecycleScope.launch(handler, block = block)
    }

    fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    fun message(title: String, text: String) {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this).setTitle(title).setMessage(text).setPositiveButton(android.R.string.ok, null).show()
    }

    // endregion

    companion object {
        private const val EXTRA_URL = "novel_url"

        /** An intent that opens the scraper with the page [url] (null: no page). */
        fun intentFor(context: Context, url: String?): Intent =
            Intent(context, NovelScraperActivity::class.java).apply { if (!url.isNullOrBlank()) putExtra(EXTRA_URL, url) }
    }
}
