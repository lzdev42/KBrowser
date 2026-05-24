package xyz.kbrowser.webview

object JsScripts {
    val EXTRACT_SNAPSHOT: String = """
        (function() {
            if (!window.__kb_element_map) {
                window.__kb_element_map = new Map();
            }
            
            var elements = [];
            var allNodes = document.querySelectorAll('*');
            
            function getDirectText(node) {
                var text = '';
                for (var i = 0; i < node.childNodes.length; i++) {
                    if (node.childNodes[i].nodeType === 3) {
                        var t = node.childNodes[i].textContent.trim();
                        if (t) text += t + ' ';
                    }
                }
                return text.trim();
            }
            
            allNodes.forEach(function(el) {
                var refid = el.__kb_refid;
                if (!refid) {
                    refid = 'kb_' + Math.random().toString(36).substring(2, 11) + '_' + Date.now();
                    el.__kb_refid = refid;
                    window.__kb_element_map.set(refid, el);
                }
                
                var rect = el.getBoundingClientRect();
                var isVisible = rect.width > 0 && rect.height > 0;
                
                var textContent = '';
                try { textContent = getDirectText(el); } catch(e) {}
                if (textContent.length > 200) textContent = textContent.substring(0, 200);
                
                var attrs = {};
                for (var i = 0; i < el.attributes.length; i++) {
                    var attr = el.attributes[i];
                    if (attr.name !== 'class' && attr.name !== 'id' && attr.name !== 'style') {
                        attrs[attr.name] = attr.value.substring(0, 100);
                    }
                }
                
                elements.push({
                    refid: refid,
                    tagName: el.tagName.toLowerCase(),
                    id: el.id || '',
                    className: (el.className && typeof el.className === 'string') ? el.className : '',
                    text: textContent,
                    isVisible: isVisible,
                    x: Math.round(rect.left + window.scrollX),
                    y: Math.round(rect.top + window.scrollY),
                    width: Math.round(rect.width),
                    height: Math.round(rect.height),
                    centerX: Math.round(rect.left + window.scrollX + rect.width / 2),
                    centerY: Math.round(rect.top + window.scrollY + rect.height / 2),
                    childCount: el.children.length,
                    attributes: attrs
                });
            });
            
            var diag = {
                url: window.location.href,
                innerWidth: window.innerWidth,
                innerHeight: window.innerHeight,
                scrollX: window.scrollX,
                scrollY: window.scrollY,
                documentWidth: document.documentElement.scrollWidth,
                documentHeight: document.documentElement.scrollHeight,
                devicePixelRatio: window.devicePixelRatio || 1.0,
                totalElements: elements.length,
                visibleElements: elements.filter(function(e) { return e.isVisible; }).length,
                hiddenElements: elements.filter(function(e) { return !e.isVisible; }).length,
                iframeCount: document.querySelectorAll('iframe').length
            };
            
            return JSON.stringify({
                url: diag.url,
                innerWidth: diag.innerWidth,
                innerHeight: diag.innerHeight,
                scrollX: diag.scrollX,
                scrollY: diag.scrollY,
                documentWidth: diag.documentWidth,
                documentHeight: diag.documentHeight,
                devicePixelRatio: diag.devicePixelRatio,
                totalElements: diag.totalElements,
                visibleElements: diag.visibleElements,
                hiddenElements: diag.hiddenElements,
                iframeCount: diag.iframeCount,
                nodes: elements
            });
        })()
    """.trimIndent()
}
