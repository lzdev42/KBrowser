@file:OptIn(
    kotlin.js.ExperimentalWasmJsInterop::class,
    kotlin.io.encoding.ExperimentalEncodingApi::class
)

package xyz.kbrowser.web

import androidx.compose.runtime.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.platform.Font
import kotlinx.coroutines.delay
import kotlin.io.encoding.Base64

@Composable
internal fun WithFontResourcesLoaded(
    content: @Composable () -> Unit
) {
    var state by remember { mutableStateOf(FontAccessState.Loading) }
    val resolver = androidx.compose.ui.platform.LocalFontFamilyResolver.current

    LaunchedEffect(Unit) {
        startFontAccessFlow()
        while (state == FontAccessState.Loading) {
            delay(80)
            when (readFontStatus()) {
                1 -> {
                    val count = readFontCount()
                    consoleLog("[FontAccess] Loading $count fonts into Skia...")
                    for (i in 0 until count) {
                        val base64 = readFontBase64(i)
                        val family = readFontFamily(i)
                        if (base64.isNotEmpty()) {
                            try {
                                val bytes = Base64.decode(base64)
                                val font = Font(family, bytes, FontWeight.Normal, FontStyle.Normal)
                                resolver.preload(FontFamily(font))
                            } catch (e: Throwable) {
                                consoleLog("[FontAccess] Failed: $family - ${e.message}")
                            }
                        }
                    }
                    consoleLog("[FontAccess] All $count fonts loaded")
                    state = FontAccessState.Granted
                }
                2 -> state = FontAccessState.Denied
            }
        }
    }

    if (state == FontAccessState.Granted) content()
}

enum class FontAccessState { Loading, Granted, Denied }

private fun readFontStatus(): Int = js("(window.__kbFontStatus || 0)")
private fun readFontCount(): Int = js("(window.__kbFontData ? window.__kbFontData.length : 0)")
private fun readFontBase64(index: Int): String = js("window.__kbFontData[index].base64")
private fun readFontFamily(index: Int): String = js("window.__kbFontData[index].family")
private fun consoleLog(msg: String) { js("console.log(msg)") }

private fun startFontAccessFlow() {
    js("""
        window.__kbFontStatus = 0;
        window.__kbFontData = [];

        var overlay = document.createElement('div');
        overlay.style.cssText = 'position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,0.9);display:flex;align-items:center;justify-content:center;z-index:99999;font-family:system-ui,-apple-system,sans-serif';
        document.body.appendChild(overlay);

        function setLoading(msg) {
            overlay.innerHTML = '<div style="color:#fff;font-size:16px;text-align:center">' + (msg || '正在加载系统字体...') + '</div>';
        }

        function showDenied(msg) {
            overlay.innerHTML =
                '<div style="background:#1E1E1E;border-radius:16px;padding:24px;text-align:center;max-width:380px;border:1px solid #333;box-shadow:0 8px 24px rgba(0,0,0,0.5)">' +
                '<div style="color:#fff;font-size:18px;font-weight:bold">未授权使用字体，无法访问应用</div>' +
                '<div style="color:#AAA;font-size:13px;margin-top:8px;line-height:1.6">' + (msg || '请允许读取本地字体') + '</div>' +
                '<button id="kb-font-retry" style="background:#4A90D9;color:#fff;border:none;border-radius:8px;padding:10px 24px;font-size:14px;margin-top:20px;cursor:pointer;font-family:inherit">重新授权</button>' +
                '</div>';
            var btn = overlay.querySelector('#kb-font-retry');
            if (btn) btn.onclick = function() { tryAccess(); };
        }

        async function blobToBase64(blob) {
            var buffer = await blob.arrayBuffer();
            var bytes = new Uint8Array(buffer);
            var binary = '';
            var chunk = 0x8000;
            for (var i = 0; i < bytes.length; i += chunk) {
                binary += String.fromCharCode.apply(null, bytes.subarray(i, Math.min(i + chunk, bytes.length)));
            }
            return btoa(binary);
        }

        async function tryAccess() {
            setLoading('正在请求字体授权...');

            if (!('queryLocalFonts' in window)) {
                showDenied('当前浏览器不支持 Local Font Access API，请使用 Chrome 103+');
                window.__kbFontStatus = 2;
                return;
            }

            try {
                var fonts = await window.queryLocalFonts();
                console.log('[FontAccess] queryLocalFonts returned', fonts.length, 'fonts');

                var seenFamilies = {};
                var toLoad = [];

                for (var i = 0; i < fonts.length; i++) {
                    var f = fonts[i];
                    var family = f.family;
                    if (seenFamilies[family]) continue;
                    seenFamilies[family] = true;
                    toLoad.push(f);
                }

                console.log('[FontAccess] Unique families:', toLoad.length);
                console.log('[FontAccess] Families:', toLoad.map(function(f){ return f.family; }).join(', '));

                for (var k = 0; k < toLoad.length; k++) {
                    setLoading('正在加载系统字体<br/>' + (k+1) + ' / ' + toLoad.length + '<br/><span style="color:#888;font-size:13px">' + toLoad[k].family + '</span>');
                    try {
                        var base64 = await blobToBase64(await toLoad[k].blob());
                        window.__kbFontData.push({
                            family: toLoad[k].family,
                            base64: base64
                        });
                    } catch(e) {
                        console.log('[FontAccess] Failed:', toLoad[k].family, e);
                    }
                }

                console.log('[FontAccess] Total loaded:', window.__kbFontData.length);

                if (window.__kbFontData.length > 0) {
                    overlay.remove();
                    window.__kbFontStatus = 1;
                } else {
                    showDenied('未能加载任何字体数据');
                    window.__kbFontStatus = 2;
                }
            } catch(e) {
                console.log('[FontAccess] Error:', e);
                showDenied('请求字体权限失败: ' + (e.message || e.name));
                window.__kbFontStatus = 2;
            }
        }

        tryAccess();
    """)
}
