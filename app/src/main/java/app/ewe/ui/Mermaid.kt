package app.ewe.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color as AColor
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.ewe.data.AppJson
import kotlinx.serialization.json.JsonPrimitive

/**
 * Draws a Mermaid diagram with the bundled mermaid.js (works offline), and offers a subtle
 * "Copy" for the code. If the code does not parse, the code itself is shown instead.
 */
/** Diagrams the AI has already been asked to fix in this app run, so it is asked at most once each. */
private val aiAttempted = mutableSetOf<String>()

/**
 * Draws a Mermaid diagram with the bundled mermaid.js (offline). If the code does not parse, the page
 * tries known repairs; a repair that draws is passed to [onFixed] to be saved. If none works, [aiFix]
 * (the notes model) is asked once; its answer is saved and drawn. Only if that fails too is the code
 * shown with the error.
 */
@Composable
fun MermaidDiagram(
    code: String, modifier: Modifier = Modifier, onLabel: (() -> Unit)? = null,
    fixKey: String? = null, onFixed: ((String) -> Unit)? = null, aiFix: (suspend (String, String) -> String)? = null,
) {
    // Height reported by the page, in CSS pixels (= dp in a WebView at its default scale).
    var heightCss by remember(code) { mutableIntStateOf(0) }
    var error by remember(code) { mutableStateOf<String?>(null) }
    var showCode by remember(code) { mutableStateOf(false) }
    var fixing by remember(code) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun askAi(reason: String) {
        val ai = aiFix ?: return
        fixing = true
        scope.launch {
            val fixed = runCatching { ai(code, reason) }.getOrNull()
            fixing = false
            if (fixed != null && fixed.isNotBlank() && fixed != code) onFixed?.invoke(fixed)
            else error = "$reason (the AI could not fix it)"
        }
    }

    Box(modifier) {
        ExtraFrame("Diagram · Mermaid", "Diagram code", code, onLabel, barExtras = {
            if (error == null && !fixing) Text(if (showCode) "Diagram" else "Code", style = ts(11, 500, C.G500),
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button) { showCode = !showCode }.padding(horizontal = 8.dp, vertical = 10.dp))
        }) {
            when {
                fixing -> Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), color = C.G500, strokeWidth = 2.dp)
                    Text("Fixing this diagram…", style = ts(13, 400, C.G600))
                }
                // A new page for each version of the code, so its callbacks always update the current state.
                error == null && !showCode -> androidx.compose.runtime.key(code) { AndroidView(
                    factory = { ctx ->
                        diagramWebView(ctx, onHeight = { heightCss = it }, onFixed = { repaired -> onFixed?.invoke(repaired) }, onError = { e ->
                            val key = fixKey ?: code
                            if (aiFix != null && key !in aiAttempted) { aiAttempted += key; askAi(e) } else error = e
                        })
                    },
                    update = { web -> web.tag.let { if (it != code) { web.tag = code; web.draw(code) } } },
                    modifier = Modifier.fillMaxWidth().padding(8.dp)
                        .height(if (heightCss > 0) heightCss.dp else 120.dp)
                        .semantics { contentDescription = "Diagram" },
                ) }
                else -> Column {
                    if (error != null) Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Couldn't draw this diagram: $error", style = ts(12, 400, C.Amber, 1.4f), modifier = Modifier.weight(1f))
                        if (aiFix != null) Text("Fix with AI", style = ts(12, 600, C.Blue),
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button) { val r = error ?: ""; error = null; askAi(r) }
                                .padding(horizontal = 8.dp, vertical = 10.dp))
                    }
                    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp)) {
                        Text(code, style = ts(12, 400, C.G700, 1.45f).copy(fontFamily = FontFamily.Monospace), softWrap = false)
                    }
                }
            }
        }
    }
}

fun copyText(context: Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
}

private class Bridge(val onHeight: (Int) -> Unit, val onFixed: (String) -> Unit, val onError: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    @JavascriptInterface fun height(value: String) { value.toIntOrNull()?.let { h -> main.post { onHeight(h) } } }
    @JavascriptInterface fun fixed(value: String) { if (value.isNotBlank()) main.post { onFixed(value) } }
    @JavascriptInterface fun error(value: String) { main.post { onError(value.ifBlank { "invalid diagram" }) } }
}

@SuppressLint("SetJavaScriptEnabled")
private fun diagramWebView(context: Context, onHeight: (Int) -> Unit, onFixed: (String) -> Unit, onError: (String) -> Unit): WebView =
    WebView(context).apply {
        setBackgroundColor(AColor.TRANSPARENT)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        settings.javaScriptEnabled = true
        // Only the bundled page and script are loaded; nothing from the network.
        settings.blockNetworkLoads = true
        settings.allowContentAccess = false
        addJavascriptInterface(Bridge(onHeight, onFixed, onError), "EweBridge")
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                (view.tag as? String)?.let { view.evaluateJavascript("draw(${quote(it)})", null) }
            }
        }
        loadUrl("file:///android_asset/mermaid/render.html")
    }

private fun WebView.draw(code: String) {
    if (progress >= 100) evaluateJavascript("draw(${quote(code)})", null)
}

private fun quote(s: String): String = AppJson.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(s))
