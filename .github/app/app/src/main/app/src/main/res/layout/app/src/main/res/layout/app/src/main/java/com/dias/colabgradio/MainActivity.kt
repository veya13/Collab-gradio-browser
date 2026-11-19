package com.dias.colabgradio

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dias.colabgradio.databinding.ActivityMainBinding

data class BrowserTab(
    val id: Long,
    var title: String,
    var url: String,
    val webView: WebView
)

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "colab_gradio_prefs"
        private const val KEY_GRADIO_URL = "saved_gradio_url"
        private const val COLAB_URL = "https://colab.research.google.com/"
        private const val DEFAULT_GRADIO_URL = "https://gradio.app/"
    }

    private lateinit var binding: ActivityMainBinding
    private val tabs = mutableListOf<BrowserTab>()
    private var activeTabIndex = -1
    private var tabIdCounter = 1L
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback
        filePathCallback = null
        if (callback == null) return@registerForActivityResult

        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val clipData = result.data?.clipData
            val singleUri = result.data?.data
            val uris = when {
                clipData != null -> (0 until clipData.itemCount).map { clipData.getItemAt(it).uri }.toTypedArray()
                singleUri != null -> arrayOf(singleUri)
                else -> WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            }
            callback.onReceiveValue(uris)
        } else {
            callback.onReceiveValue(null)
        }
    }

    private val tabAdapter = TabAdapter(
        onSelect = { index -> switchToTab(index) },
        onClose = { index -> closeTab(index) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(binding.webViewContainer.getChildAt(0) as? WebView, true)
        }

        setupTabStrip()
        setupControls()

        val savedGradio = getSavedGradioUrl()
        createNewTab(COLAB_URL, "Google Colab", switchImmediately = true)
        createNewTab(savedGradio, "Gradio UI", switchImmediately = false)
    }

    private fun setupTabStrip() {
        binding.rvTabs.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvTabs.adapter = tabAdapter
    }

    private fun setupControls() {
        binding.btnNewTab.setOnClickListener {
            createNewTab("https://www.google.com", "Tab Baru", switchImmediately = true)
        }

        binding.btnGo.setOnClickListener {
            navigateCurrentTabToInput()
        }

        binding.etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                navigateCurrentTabToInput()
                true
            } else {
                false
            }
        }

        binding.btnSaveGradio.setOnClickListener {
            val currentUrl = currentTab()?.webView?.url ?: binding.etUrl.text.toString().trim()
            if (currentUrl.startsWith("http")) {
                saveGradioUrl(currentUrl)
                Toast.makeText(this, "Link Gradio disimpan: $currentUrl", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "URL tidak valid untuk disimpan", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnColab.setOnClickListener {
            val colabIdx = tabs.indexOfFirst { it.url.contains("colab.research.google.com") }
            if (colabIdx >= 0) {
                switchToTab(colabIdx)
            } else {
                createNewTab(COLAB_URL, "Google Colab", switchImmediately = true)
            }
        }

        binding.btnGradio.setOnClickListener {
            val gradioUrl = getSavedGradioUrl()
            val gradioIdx = tabs.indexOfFirst { it.url.startsWith(gradioUrl.take(24)) }
            if (gradioIdx >= 0) {
                switchToTab(gradioIdx)
            } else {
                createNewTab(gradioUrl, "Gradio UI", switchImmediately = true)
            }
        }

        binding.btnBack.setOnClickListener {
            val wv = currentTab()?.webView
            if (wv != null && wv.canGoBack()) wv.goBack()
        }

        binding.btnForward.setOnClickListener {
            val wv = currentTab()?.webView
            if (wv != null && wv.canGoForward()) wv.goForward()
        }

        binding.btnReload.setOnClickListener {
            currentTab()?.webView?.reload()
        }
    }

    private fun navigateCurrentTabToInput() {
        var raw = binding.etUrl.text.toString().trim()
        if (raw.isEmpty()) return
        if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
            raw = if (raw.contains(".") && !raw.contains(" ")) "https://$raw"
            else "https://www.google.com/search?q=${Uri.encode(raw)}"
        }
        currentTab()?.let { tab ->
            tab.url = raw
            tab.webView.loadUrl(raw)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(): WebView {
        return WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#0F1115"))

            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                setSupportMultipleWindows(true)
                javaScriptCanOpenWindowsAutomatically = true
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = true
                displayZoomControls = false
                allowFileAccess = true
                allowContentAccess = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                userAgentString = userAgentString.replace("; wv", "")
            }

            setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                try {
                    val request = DownloadManager.Request(Uri.parse(url)).apply {
                        setMimeType(mimeType)
                        addRequestHeader("User-Agent", userAgent)
                        addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                        val fileName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
                        setTitle(fileName)
                        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                    }
                    val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    dm.enqueue(request)
                    Toast.makeText(this@MainActivity, "Mengunduh file ke Downloads...", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    openInChromeCustomTabs(url)
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val uri = request?.url ?: return false
                    val urlStr = uri.toString()

                    // Arahkan login Google OAuth ke Chrome Custom Tabs agar tidak diblokir WebView
                    if (urlStr.contains("accounts.google.com/o/oauth2") ||
                        urlStr.contains("accounts.google.com/signin/oauth")
                    ) {
                        openInChromeCustomTabs(urlStr)
                        return true
                    }

                    if (urlStr.startsWith("http://") || urlStr.startsWith("https://")) {
                        return false
                    }

                    return try {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                        true
                    } catch (e: Exception) {
                        true
                    }
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (view == currentTab()?.webView && url != null) {
                        binding.etUrl.setText(url)
                        currentTab()?.url = url
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    val tabIndex = tabs.indexOfFirst { it.webView == view }
                    if (tabIndex >= 0) {
                        val pageTitle = view?.title?.takeIf { it.isNotBlank() } ?: (url ?: "Tab")
                        tabs[tabIndex].title = pageTitle
                        if (url != null) tabs[tabIndex].url = url
                        tabAdapter.notifyItemChanged(tabIndex)
                    }
                    if (view == currentTab()?.webView && url != null) {
                        binding.etUrl.setText(url)
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (view == currentTab()?.webView) {
                        if (newProgress in 1..99) {
                            binding.progressBar.visibility = View.VISIBLE
                            binding.progressBar.progress = newProgress
                        } else {
                            binding.progressBar.visibility = View.GONE
                        }
                    }
                }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    super.onReceivedTitle(view, title)
                    val tabIndex = tabs.indexOfFirst { it.webView == view }
                    if (tabIndex >= 0 && !title.isNullOrBlank()) {
                        tabs[tabIndex].title = title
                        tabAdapter.notifyItemChanged(tabIndex)
                    }
                }

                // Dukungan buka tab baru (window.open / target="_blank")
                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message?
                ): Boolean {
                    val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                    val newTab = createNewTab("about:blank", "Tab Baru", switchImmediately = true)
                    transport.webView = newTab.webView
                    resultMsg.sendToTarget()
                    return true
                }

                // Dukungan upload foto/video/file ke Gradio atau Colab
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>,
                    fileChooserParams: FileChooserParams
                ): Boolean {
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = filePathCallback

                    return try {
                        val intent = fileChooserParams.createIntent()
                        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        fileChooserLauncher.launch(intent)
                        true
                    } catch (e: Exception) {
                        val fallbackIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                            type = "*/*"
                            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                            addCategory(Intent.CATEGORY_OPENABLE)
                        }
                        fileChooserLauncher.launch(Intent.createChooser(fallbackIntent, "Pilih File"))
                        true
                    }
                }
            }
        }
    }

    private fun createNewTab(url: String, initialTitle: String, switchImmediately: Boolean): BrowserTab {
        val webView = buildWebView()
        webView.loadUrl(url)
        val tab = BrowserTab(
            id = tabIdCounter++,
            title = initialTitle,
            url = url,
            webView = webView
        )
        tabs.add(tab)
        tabAdapter.submitList(tabs.toList(), activeTabIndex)

        if (switchImmediately || activeTabIndex == -1) {
            switchToTab(tabs.lastIndex)
        }
        return tab
    }

    private fun switchToTab(index: Int) {
        if (index !in tabs.indices) return
        activeTabIndex = index
        val selectedTab = tabs[index]

        binding.webViewContainer.removeAllViews()
        (selectedTab.webView.parent as? ViewGroup)?.removeView(selectedTab.webView)
        binding.webViewContainer.addView(selectedTab.webView)

        binding.etUrl.setText(selectedTab.webView.url ?: selectedTab.url)
        tabAdapter.submitList(tabs.toList(), activeTabIndex)
    }

    private fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val removed = tabs.removeAt(index)
        removed.webView.destroy()

        if (tabs.isEmpty()) {
            activeTabIndex = -1
            createNewTab(COLAB_URL, "Google Colab", switchImmediately = true)
            return
        }

        val nextIndex = when {
            index < activeTabIndex -> activeTabIndex - 1
            index == activeTabIndex -> index.coerceAtMost(tabs.lastIndex)
            else -> activeTabIndex
        }
        switchToTab(nextIndex)
    }

    private fun currentTab(): BrowserTab? = tabs.getOrNull(activeTabIndex)

    private fun openInChromeCustomTabs(url: String) {
        try {
            val customTabsIntent = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
            customTabsIntent.launchUrl(this, Uri.parse(url))
        } catch (e: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    private fun saveGradioUrl(url: String) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_GRADIO_URL, url)
            .apply()
    }

    private fun getSavedGradioUrl(): String {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_GRADIO_URL, DEFAULT_GRADIO_URL) ?: DEFAULT_GRADIO_URL
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val wv = currentTab()?.webView
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        tabs.forEach { it.webView.destroy() }
        super.onDestroy()
    }
}

class TabAdapter(
    private val onSelect: (Int) -> Unit,
    private val onClose: (Int) -> Unit
) : RecyclerView.Adapter<TabAdapter.TabViewHolder>() {

    private var items: List<BrowserTab> = emptyList()
    private var selectedIndex: Int = -1

    fun submitList(newItems: List<BrowserTab>, activeIdx: Int) {
        items = newItems
        selectedIndex = activeIdx
        notifyDataSetChanged()
    }

    inner class TabViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val root: LinearLayout = view.findViewById(R.id.tabChipRoot)
        val title: TextView = view.findViewById(R.id.tvTabTitle)
        val closeBtn: ImageButton = view.findViewById(R.id.btnCloseTab)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_tab_chip, parent, false)
        return TabViewHolder(view)
    }

    override fun onBindViewHolder(holder: TabViewHolder, position: Int) {
        val tab = items[position]
        val isSelected = position == selectedIndex
        holder.title.text = tab.title
        holder.root.setBackgroundColor(
            Color.parseColor(if (isSelected) "#2563EB" else "#222733")
        )
        holder.title.setTextColor(Color.WHITE)

        holder.root.setOnClickListener { onSelect(position) }
        holder.btnCloseOrRoot(holder.closeBtn, position)
    }

    private fun TabViewHolder.btnCloseOrRoot(btn: ImageButton, position: Int) {
        btn.setOnClickListener { onClose(position) }
    }

    override fun getItemCount(): Int = items.size
}
