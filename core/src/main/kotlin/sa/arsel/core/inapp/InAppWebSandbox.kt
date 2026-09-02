package sa.arsel.core.inapp

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import sa.arsel.core.log.ArselLog

/**
 * A WebView that draws author-supplied markup, and nothing else.
 *
 * This is the only place the SDK renders content it did not construct, inside the CUSTOMER's own
 * app, so the hardening below is the feature — not the WebView.
 *
 * The rule that matters most: **no `addJavascriptInterface`, ever.** An injected object is
 * reachable by reflection from the page and is the single largest remote-code-execution surface an
 * Android app can hand out. The markup instead talks over an [WebMessagePort] channel, which
 * carries strings only and cannot name a Java method.
 *
 * The second rule: an inline creative is loaded with a NULL base URL, which gives the page an
 * opaque origin. It has no same-origin access to the host app's files, its `content://` providers,
 * or any http(s) origin, and so cannot read anything the app can.
 */
internal object InAppWebSandbox {
    /**
     * @param onMessage receives each well-formed bridge message on the UI thread. Only called when
     *   the author enabled JavaScript — a scriptless creative has nothing to say.
     */
    fun create(
        activity: Activity,
        custom: InAppCustomHtml,
        log: ArselLog,
        onMessage: (JSONObject) -> Unit,
    ): WebView {
        val web = WebView(activity)
        web.setBackgroundColor(Color.TRANSPARENT)
        harden(web, custom.allowJavaScript)
        web.webViewClient = sandboxClient(activity, custom.allowJavaScript, log, onMessage)

        if (custom.source == HTML_SOURCE_URL && !custom.url.isNullOrEmpty()) {
            web.loadUrl(custom.url)
        } else {
            // A null base URL is what makes the origin opaque. Passing the app's own scheme here
            // would let the markup read the app's storage as same-origin.
            web.loadDataWithBaseURL(null, custom.html.orEmpty(), MIME_HTML, ENCODING, null)
        }
        return web
    }

    /**
     * Everything a WebView grants by default that this content has no business having.
     *
     * `setJavaScriptEnabled` is the one line lint flags, and it is deliberate and conditional: it
     * is set only when an author explicitly enabled script for this campaign, which is off unless
     * someone turned it on.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun harden(
        web: WebView,
        allowJavaScript: Boolean,
    ) {
        web.settings.apply {
            javaScriptEnabled = allowJavaScript
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            // File and content access are the paths from a rendered page to the app's own data.
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            // No persistence: a marketing creative has no reason to leave state on the device, and
            // storage shared across campaigns is a tracking surface the customer never agreed to.
            domStorageEnabled = false
            databaseEnabled = false
            setGeolocationEnabled(false)
            // Autoplaying video or audio out of a message the user did not open is hostile.
            mediaPlaybackRequiresUserGesture = true
        }
        web.isVerticalScrollBarEnabled = false
        web.isHorizontalScrollBarEnabled = false
        runCatching {
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        }.getOrNull()
    }

    private fun sandboxClient(
        activity: Activity,
        allowJavaScript: Boolean,
        log: ArselLog,
        onMessage: (JSONObject) -> Unit,
    ): WebViewClient =
        object : WebViewClient() {
            override fun onPageFinished(
                view: WebView,
                url: String?,
            ) {
                if (!allowJavaScript) return
                view.evaluateJavascript(BRIDGE_SHIM, null)
                openChannel(view, log, onMessage)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean = leaveSandbox(activity, request.url, log)

            // The API 24 overload above is the one modern devices call; this is what minSdk 23
            // devices get, and without it a link tap there navigates the sandbox itself.
            @Deprecated("Superseded by the WebResourceRequest overload on API 24+")
            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(
                view: WebView,
                url: String,
            ): Boolean = leaveSandbox(activity, Uri.parse(url), log)
        }

    /**
     * A tap inside the markup never navigates the sandbox.
     *
     * Letting it would turn the message into an uncontrolled browser sitting inside the customer's
     * app, with no address bar and no way back. An http(s) destination is handed to the system
     * instead, where the user can see where they are; anything else is dropped.
     */
    private fun leaveSandbox(
        activity: Activity,
        uri: Uri,
        log: ArselLog,
    ): Boolean {
        val scheme = uri.scheme?.lowercase()
        if (scheme == SCHEME_HTTP || scheme == SCHEME_HTTPS) {
            runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                .onFailure { log.w("in-app: nothing on this device handles $uri", it) }
        }
        return true
    }

    /**
     * Opens the one channel the markup has back to the SDK.
     *
     * A [WebMessagePort] carries strings, so the page can describe an intent but can never name a
     * method, a class or a field — which is exactly what `addJavascriptInterface` would have given
     * it. Every message is still parsed defensively on the way in.
     */
    private fun openChannel(
        view: WebView,
        log: ArselLog,
        onMessage: (JSONObject) -> Unit,
    ) {
        val channel = runCatching { view.createWebMessageChannel() }.getOrNull() ?: return
        val host = channel.getOrNull(0) ?: return
        val guest = channel.getOrNull(1) ?: return

        host.setWebMessageCallback(
            object : WebMessagePort.WebMessageCallback() {
                override fun onMessage(
                    port: WebMessagePort,
                    message: WebMessage?,
                ) {
                    val data = message?.data ?: return
                    // Bounded before it is parsed: the page is untrusted and a multi-megabyte
                    // string would be paid for in the UI thread's parse.
                    if (data.length > MAX_BRIDGE_CHARS) return
                    val json = runCatching { JSONObject(data) }.getOrNull() ?: return
                    onMessage(json)
                }
            },
        )

        runCatching {
            view.postWebMessage(WebMessage(BRIDGE_HANDSHAKE, arrayOf(guest)), Uri.parse(ANY_ORIGIN))
        }.onFailure { log.w("in-app: this WebView has no message channel", it) }
    }

    /**
     * Gives the markup the same API it has on the web.
     *
     * On the web the creative sits in an iframe and posts to `parent`. Here it is the top-level
     * page, so `parent` is itself — this shim listens for exactly those posts and forwards them
     * down the port. One snippet therefore works unchanged on web, Android and iOS, which is the
     * only way custom templates are portable at all.
     */
    private const val BRIDGE_SHIM = """
(function () {
  if (window.__arselBridge) return;
  var port = null;
  var queue = [];
  function flush() { while (port && queue.length) port.postMessage(queue.shift()); }
  window.addEventListener('message', function (event) {
    if (!port && event.ports && event.ports.length) { port = event.ports[0]; flush(); return; }
    var data = event.data;
    if (!data || typeof data.type !== 'string') return;
    if (data.type.lastIndexOf('arsel:', 0) !== 0) return;
    queue.push(JSON.stringify(data));
    flush();
  });
  window.__arselBridge = true;
})();
"""

    private const val BRIDGE_HANDSHAKE = "arsel:init"
    private const val ANY_ORIGIN = "*"
    private const val MIME_HTML = "text/html"
    private const val ENCODING = "utf-8"
    private const val SCHEME_HTTP = "http"
    private const val SCHEME_HTTPS = "https"
    private const val MAX_BRIDGE_CHARS = 16_384
}
