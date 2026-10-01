package com.raen.spoz.ui.player

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream

class StreamPlayerBridge(
    private val onPlayStateChanged: (isPlaying: Boolean) -> Unit,
    private val onProgressUpdated: (positionSec: Double, durationSec: Double) -> Unit,
    private val onVideoError: (errorMessage: String) -> Unit
) {
    @JavascriptInterface
    fun onVideoPlay() {
        onPlayStateChanged(true)
    }

    @JavascriptInterface
    fun onVideoPause() {
        onPlayStateChanged(false)
    }

    @JavascriptInterface
    fun onTimeUpdate(currentTime: Double, duration: Double) {
        onProgressUpdated(currentTime, duration)
    }

    @JavascriptInterface
    fun onVideoError(msg: String) {
        onVideoError(msg)
    }
}

class StreamWebChromeClient(
    private val onCustomViewShown: (View, WebChromeClient.CustomViewCallback) -> Unit,
    private val onCustomViewHidden: () -> Unit
) : WebChromeClient() {
    private var customView: View? = null
    private var customViewCallback: CustomViewCallback? = null

    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
        if (customView != null) {
            onHideCustomView()
            return
        }
        customView = view
        customViewCallback = callback
        onCustomViewShown(view, callback)
    }

    override fun onHideCustomView() {
        if (customView == null) return
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        onCustomViewHidden()
    }
}

open class StreamWebViewClient(
    private val allowedBaseUrl: String,
    private val onPageLoaded: (WebView) -> Unit = {},
    private val onError: (String) -> Unit = {}
) : WebViewClient() {

    private val adDomainKeywords = listOf(
        "popads", "propeller", "adsterra", "bet365", "1xbet", "parimatch",
        "doubleclick", "googlesyndication", "monetag", "adnxs", "trafficjunky",
        "exoclick", "onclickpredict", "fstats", "analytics", "aclib", "histats"
    )

    private val blockedSchemes = setOf(
        "market", "intent", "vnd.youtube", "tg", "whatsapp", "itms-apps", "tel", "mailto"
    )

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url ?: return true
        val scheme = uri.scheme?.lowercase() ?: ""
        val host = uri.host?.lowercase() ?: ""

        // 1. Block external app intent redirects (Play Store, Telegram, etc.)
        if (scheme in blockedSchemes || !scheme.startsWith("http")) {
            Log.d("StreamWebView", "Blocked non-http scheme redirect: $uri")
            return true
        }

        // 2. Allow same-origin navigation, subdomains, and CDN streams
        val baseHost = try { Uri.parse(allowedBaseUrl).host?.lowercase() ?: "" } catch (e: Exception) { "" }
        val isAllowedHost = host.isEmpty() || host.endsWith(baseHost) || baseHost.endsWith(host) ||
                host.contains("jsdelivr") || host.contains("jwplayer") || host.contains("cloudflare") ||
                host.contains("streamfree") || host.contains("streamed.pk") || host.contains("cdn")

        // 3. Block malicious main-frame page hijacking
        if (request.isForMainFrame && !isAllowedHost) {
            Log.w("StreamWebView", "Blocked malicious main-frame redirect to: $uri")
            return true
        }

        // 4. Block known ad domains
        if (adDomainKeywords.any { host.contains(it) }) {
            Log.d("StreamWebView", "Blocked navigation to ad domain: $host")
            return true
        }

        return false
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString().lowercase()
        val host = request.url.host?.lowercase() ?: ""

        // Intercept and blackhole ad scripts / tracker bundles
        if (adDomainKeywords.any { host.contains(it) || url.contains(it) } ||
            url.endsWith("/ad.html") || url.contains("popunder") || url.contains("analytics.js")) {
            return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
        }

        return super.shouldInterceptRequest(view, request)
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        // Neutralize popups and sandDetect traps before scripts load
        view.evaluateJavascript(StreamJsInjector.PRE_INIT_JS, null)
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        // Inject layout expansion, ad overlay removal & HTML5 video bridge
        view.evaluateJavascript(StreamJsInjector.BUILD_INJECTION_JS(), null)
        onPageLoaded(view)
    }

    @Deprecated("Deprecated in Java", ReplaceWith("onReceivedError(view, request, error)"))
    @Suppress("DEPRECATION")
    override fun onReceivedError(view: WebView, errorCode: Int, description: String, failingUrl: String) {
        super.onReceivedError(view, errorCode, description, failingUrl)
        Log.e("StreamWebView", "WebView error $errorCode on $failingUrl: $description")
        onError(description)
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        Log.e("StreamWebView", "Renderer process crashed (didCrash: ${detail.didCrash()})")
        (view.parent as? ViewGroup)?.removeView(view)
        view.destroy()
        onError("Web stream renderer reset due to memory limits")
        return true
    }
}

object StreamJsInjector {
    val PRE_INIT_JS = """
        (function() {
            window.open = function() { console.log('Spoz: Blocked window.open'); return null; };
            window.alert = function() {};
            window.confirm = function() { return false; };
            
            // Bypass anti-sandbox traps
            window.sandDetect = function() {};
            if (document.getElementById('sandDetect')) {
                document.getElementById('sandDetect').remove();
            }

            // Block Service Worker crashes on older Android WebViews
            if (navigator.serviceWorker) {
                Object.defineProperty(navigator, 'serviceWorker', {
                    get: function() {
                        return {
                            register: function() { return Promise.reject(new Error('SW blocked')); },
                            ready: Promise.reject(new Error('SW blocked')),
                            getRegistrations: function() { return Promise.resolve([]); }
                        };
                    }
                });
            }
            
            if (window.Hls && window.Hls.P2pEngine) {
                window.Hls.P2pEngine.tryRegisterServiceWorker = function() {
                    return Promise.reject(new Error('P2P blocked'));
                };
            }
        })();
    """.trimIndent()

    fun BUILD_INJECTION_JS(): String = """
        (function() {
            // Remove sandbox restrictions from iframes
            document.querySelectorAll('iframe[sandbox]').forEach(function(f) {
                f.removeAttribute('sandbox');
            });

            // Expand video to 100% viewport and hide ad overlays
            const style = document.createElement('style');
            style.innerHTML = `
                html, body {
                    margin: 0 !important;
                    padding: 0 !important;
                    width: 100vw !important;
                    height: 100vh !important;
                    overflow: hidden !important;
                    background-color: #000000 !important;
                }
                #player-container, #jwplayer, .jwplayer, .video-js, video {
                    width: 100% !important;
                    height: 100% !important;
                    max-width: 100vw !important;
                    max-height: 100vh !important;
                    object-fit: contain !important;
                }
                [id*="ad"], [class*="ad-"], [class*="banner"], [id*="pop"],
                [class*="pop"], .overlay-ad, .ad-overlay, #sandDetect,
                iframe[src*="ad."], iframe[src*="pop"], .close-btn-ad {
                    display: none !important;
                    visibility: hidden !important;
                    pointer-events: none !important;
                }
            `;
            document.head.appendChild(style);

            // Hook HTML5 <video> elements to report state to Android
            function setupVideoElement(video) {
                if (video.dataset.spozAttached) return;
                video.dataset.spozAttached = "true";
                video.muted = false;
                video.playsInline = true;
                video.setAttribute('playsinline', 'true');
                video.play().catch(() => {});

                video.addEventListener('play', () => {
                    if (window.AndroidBridge) window.AndroidBridge.onVideoPlay();
                });
                video.addEventListener('pause', () => {
                    if (window.AndroidBridge) window.AndroidBridge.onVideoPause();
                });
                video.addEventListener('timeupdate', () => {
                    if (window.AndroidBridge) window.AndroidBridge.onTimeUpdate(video.currentTime || 0, video.duration || 0);
                });
                video.addEventListener('error', (e) => {
                    const err = video.error ? video.error.message : 'Unknown Video Error';
                    if (window.AndroidBridge) window.AndroidBridge.onVideoError(err);
                });
            }

            document.querySelectorAll('video').forEach(setupVideoElement);

            const observer = new MutationObserver(mutations => {
                mutations.forEach(mutation => {
                    mutation.addedNodes.forEach(node => {
                        if (node.tagName === 'VIDEO') {
                            setupVideoElement(node);
                        } else if (node.querySelectorAll) {
                            node.querySelectorAll('video').forEach(setupVideoElement);
                        }
                    });
                });
            });
            observer.observe(document.body, { childList: true, subtree: true });
        })();
    """.trimIndent()
}

@Composable
fun EnhancedStreamWebView(
    url: String,
    modifier: Modifier = Modifier,
    onPlaybackStateChange: (isPlaying: Boolean) -> Unit = {},
    onProgressUpdate: (pos: Long, dur: Long) -> Unit = { _, _ -> },
    onError: (String) -> Unit = {}
) {
    var customView by remember { mutableStateOf<View?>(null) }
    var customViewCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }

    // Exit HTML5 fullscreen on back press
    BackHandler(enabled = customView != null) {
        customViewCallback?.onCustomViewHidden()
        customView = null
        customViewCallback = null
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    webViewInstance = this
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        @Suppress("DEPRECATION")
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        setSupportZoom(false)
                        builtInZoomControls = false
                        displayZoomControls = false
                        setSupportMultipleWindows(false)
                        javaScriptCanOpenWindowsAutomatically = false
                        allowFileAccess = false
                        allowContentAccess = false
                        userAgentString = "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                    }
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    setBackgroundColor(android.graphics.Color.BLACK)

                    addJavascriptInterface(
                        StreamPlayerBridge(
                            onPlayStateChanged = onPlaybackStateChange,
                            onProgressUpdated = { cur, dur ->
                                onProgressUpdate((cur * 1000).toLong(), (dur * 1000).toLong())
                            },
                            onVideoError = onError
                        ),
                        "AndroidBridge"
                    )

                    webChromeClient = StreamWebChromeClient(
                        onCustomViewShown = { view, callback ->
                            customView = view
                            customViewCallback = callback
                        },
                        onCustomViewHidden = {
                            customView = null
                            customViewCallback = null
                        }
                    )

                    webViewClient = StreamWebViewClient(
                        allowedBaseUrl = url,
                        onError = onError
                    )

                    loadUrl(url)
                }
            },
            update = { view ->
                if (view.url != url && url.isNotEmpty()) {
                    view.loadUrl(url)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // HTML5 Fullscreen View Overlay
        customView?.let { fullScreenView ->
            AndroidView(
                factory = {
                    fullScreenView.apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    DisposableEffect(url) {
        onDispose {
            webViewInstance?.apply {
                stopLoading()
                loadUrl("about:blank")
                onPause()
                pauseTimers()
                clearHistory()
                removeAllViews()
                destroy()
            }
            webViewInstance = null
        }
    }
}
