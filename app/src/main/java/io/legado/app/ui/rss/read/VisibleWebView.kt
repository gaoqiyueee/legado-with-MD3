package io.legado.app.ui.rss.read

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.app.R
import io.legado.app.ui.dict.DictDialog
import io.legado.app.utils.toastOnUi

@SuppressLint("SetJavaScriptEnabled")
class VisibleWebView(
    context: Context,
    attrs: AttributeSet? = null
) : WebView(context, attrs) {

    private var lastSelectedText: String = ""

    /** Called after each page load to inject the selection bridge. */
    fun injectSelectionBridge() {
        val js = """
            (function(){
                if(window.__selectionBridgeInjected) return;
                window.__selectionBridgeInjected = true;
                document.addEventListener('selectionchange', function() {
                    var text = window.getSelection().toString();
                    if (text) { TextSelectionBridge.onTextSelected(text); }
                    else { TextSelectionBridge.onTextSelected(''); }
                });
            })();
        """.trimIndent()
        evaluateJavascript(js, null)
    }

    /** Set by the hosting activity/fragment to inject an extra menu item.
     *  Receives the selected text when the item is clicked. */
    var onBookmarkSelected: ((selectedText: String) -> Unit)? = null

    init {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true

        addJavascriptInterface(object {
            @JavascriptInterface
            fun onTextSelected(text: String) {
                lastSelectedText = text
            }
        }, "TextSelectionBridge")
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    override fun startActionMode(callback: ActionMode.Callback?): ActionMode {
        return super.startActionMode(createWrappedCallback(callback))
    }

    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode {
        return super.startActionMode(createWrappedCallback(callback), type)
    }

    private fun createWrappedCallback(original: ActionMode.Callback?): ActionMode.Callback {
        return object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                lastSelectedText = ""   // reset so getSelectedText fetches fresh via JS
                val result = original?.onCreateActionMode(mode, menu) ?: false
                menu.add(Menu.NONE, MENU_ID_DICT, 0, R.string.dict)
                if (onBookmarkSelected != null) {
                    menu.add(Menu.NONE, MENU_ID_BOOKMARK, 1, "📌 书签")
                }
                getSelectedText { }
                return result
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                updateDictMenuItem(menu)
                return original?.onPrepareActionMode(mode, menu) ?: false
            }

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                return when (item.itemId) {
                    MENU_ID_DICT -> {
                        postDelayed({
                            getSelectedText { selectedText ->
                                if (selectedText.isNotBlank()) {
                                    showDictDialog(selectedText)
                                } else {
                                    context.toastOnUi("未获取到选中文本，请重试")
                                }
                            }
                        }, 200)
                        mode.finish()
                        true
                    }

                    MENU_ID_BOOKMARK -> {
                        getSelectedText { selectedText ->
                            if (selectedText.isNotBlank()) {
                                onBookmarkSelected?.invoke(selectedText)
                            }
                        }
                        mode.finish()
                        true
                    }

                    else -> original?.onActionItemClicked(mode, item) ?: false
                }
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                original?.onDestroyActionMode(mode)
            }
        }
    }

    private fun updateDictMenuItem(menu: Menu) {
        val dictItem = menu.findItem(MENU_ID_DICT)
        dictItem?.let { item ->
            getSelectedText { selectedText ->
                item.isEnabled = selectedText.isNotBlank()
            }
        }
    }

    private fun getSelectedText(callback: (String) -> Unit) {
        evaluateJavascript("(function(){return window.getSelection().toString();})()") { result ->
            val selectedText = result?.removeSurrounding("\"") ?: lastSelectedText
            if (selectedText.isNotBlank()) lastSelectedText = selectedText
            callback(selectedText)
        }
    }

    private fun showDictDialog(selectedText: String) {
        val activity = context as? AppCompatActivity ?: return
        val dialog = DictDialog(selectedText)
        activity.supportFragmentManager.beginTransaction()
            .add(dialog, "DictDialog")
            .commitAllowingStateLoss()
    }

    companion object {
        private const val MENU_ID_DICT = 1001
        private const val MENU_ID_BOOKMARK = 1002
    }
}

@Composable
fun VisibleWebViewCompose(
    modifier: Modifier = Modifier,
    onCreated: (VisibleWebView) -> Unit,
    onDestroyed: (() -> Unit)? = null
) {
    val webViewHolder = remember { WebViewHolder() }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            VisibleWebView(context).also {
                webViewHolder.webView = it
                onCreated(it)
            }
        },
        update = {
            webViewHolder.webView = it
        }
    )
    DisposableEffect(Unit) {
        onDispose {
            onDestroyed?.invoke()
            webViewHolder.webView?.destroy()
            webViewHolder.webView = null
        }
    }
}

private class WebViewHolder {
    var webView: VisibleWebView? = null
}
