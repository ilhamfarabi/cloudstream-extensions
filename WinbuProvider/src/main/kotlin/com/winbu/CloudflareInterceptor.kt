package com.winbu

import android.annotation.SuppressLint
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.CloudStreamApp
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.atomic.AtomicReference

class CloudflareInterceptor(
    private val targetCookie: String = "cf_clearance"
) : Interceptor {

    @SuppressLint("SetJavaScriptEnabled", "WebViewClientOnReceivedSslError")
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val url = originalRequest.url.toString()
        val domainUrl = "\( {originalRequest.url.scheme}:// \){originalRequest.url.host}"
        val cookieManager = CookieManager.getInstance()

        cookieManager.setAcceptCookie(true)

        val existingCookies = cookieManager.getCookie(domainUrl) ?: ""

        if (existingCookies.contains(targetCookie)) {
            val response = chain.proceed(
                originalRequest.newBuilder()
                    .header("Cookie", existingCookies)
                    .build()
            )

            if (response.code in listOf(403, 503, 429)) {
                response.close()
                cookieManager.setCookie(domainUrl, "$targetCookie=; Max-Age=0; path=/; Secure")
                cookieManager.flush()
            } else {
                return response
            }
        }

        val context = CloudStreamApp.context
            ?: return chain.proceed(originalRequest)

        val handler = Handler(Looper.getMainLooper())
        val userAgentRef = AtomicReference(originalRequest.header("User-Agent") ?: "")
        val webViewRef = AtomicReference<WebView?>(null)

        handler.post {
            val wv = WebView(context)
            webViewRef.set(wv)

            cookieManager.setAcceptThirdPartyCookies(wv, true)

            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true

                val ua = userAgentRef.get()
                if (ua.isNotBlank()) userAgentString = ua
            }

            userAgentRef.set(wv.settings.userAgentString)

            wv.webViewClient = object : WebViewClient() {

                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: SslError?
                ) {
                    handler?.proceed()
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    cookieManager.flush()
                }
            }

            wv.loadUrl(url)
        }

        for (i in 0 until 45) {
            Thread.sleep(1000)
            val cookies = cookieManager.getCookie(domainUrl) ?: ""
            if (cookies.contains(targetCookie)) {
                cookieManager.flush()
                break
            }
        }

        handler.post {
            webViewRef.getAndSet(null)?.apply {
                stopLoading()
                destroy()
            }
        }

        val finalCookies = cookieManager.getCookie(domainUrl) ?: ""
        val finalUA = userAgentRef.get()

        return chain.proceed(
            originalRequest.newBuilder()
                .apply { if (finalUA.isNotBlank()) header("User-Agent", finalUA) }
                .header("Cookie", finalCookies)
                .build()
        )
    }
}