/**
 * PIXELTV - TV Navigation Layer v3.0.0
 * Injected into the WebView by MainActivity.
 *
 * What it does:
 * - Scans the page for interactive elements (links, buttons, player frames, cards)
 * - Groups them into visual rows, so D-pad Up/Down changes row and Left/Right
 *   moves inside the row (predictable, TV-like navigation)
 * - Highlights the focused element with a red frame and a small label
 * - OK clicks exactly once (real pages break when a click fires twice)
 * - iframes/videos are activated with a real native tap from MainActivity
 * - Exposes helpers for "Putar", "Ganti Server" and native player controls
 *
 * Written in ES5 on purpose: some TV WebViews are old.
 */
(function () {
    'use strict';

    if (window.__PIXELTV_NAV) return;
    window.__PIXELTV_NAV = true;
    window.__pixeltv_version = '3.0.0';

    var HIGHLIGHT_ID = 'pixeltv-highlight';
    var LABEL_ID = 'pixeltv-highlight-label';
    var STYLE_ID = 'pixeltv-nav-style';
    var VIEWPORT_MARGIN = 1.6;

    var currentEl = null;
    var lastPoint = null;
    var rows = [];
    var dirty = true;
    var lastBuild = 0;
    var hideTimer = null;
    var updatePending = false;
    var serverIndex = -1;
    var highlightEl = null;
    var labelEl = null;

    var SELECTOR = [
        'a[href]',
        'button',
        'input[type="submit"]',
        'input[type="button"]',
        '[role="button"]',
        '[role="tab"]',
        '[onclick]',
        '[tabindex]:not([tabindex="-1"])',
        'video',
        'iframe',
        '[class*="play"]',
        '[class*="btn"]',
        '[class*="card"]',
        '[class*="item"]',
        '[class*="movie"]',
        '[class*="film"]',
        '[class*="poster"]',
        '[class*="tab"]',
        '[class*="server"]',
        '[class*="episode"]',
        '[class*="season"]',
        '[class*="link"]'
    ].join(',');

    // ==================== small helpers ====================

    function rectOf(el) {
        try {
            return el.getBoundingClientRect();
        } catch (e) {
            return { left: 0, top: 0, right: 0, bottom: 0, width: 0, height: 0 };
        }
    }

    function cxOf(el) {
        var b = rectOf(el);
        return b.left + b.width / 2;
    }

    function cyOf(el) {
        var b = rectOf(el);
        return b.top + b.height / 2;
    }

    function areaOf(el) {
        var b = rectOf(el);
        return Math.max(0, b.width) * Math.max(0, b.height);
    }

    function inDoc(el) {
        try {
            return !!(el && document.documentElement && document.documentElement.contains(el));
        } catch (e) {
            return false;
        }
    }

    function styleOf(el) {
        try {
            return window.getComputedStyle(el);
        } catch (e) {
            return null;
        }
    }

    function matchesSel(el, sel) {
        try {
            var fn = el.matches || el.webkitMatchesSelector || el.msMatchesSelector;
            return fn ? fn.call(el, sel) : false;
        } catch (e) {
            return false;
        }
    }

    function closestSel(el, sel) {
        var node = el;
        var guard = 0;
        while (node && node.nodeType === 1 && guard < 80) {
            if (matchesSel(node, sel)) return node;
            node = node.parentElement;
            guard++;
        }
        return null;
    }

    function classOf(el) {
        var c = el && el.className;
        if (!c) return '';
        if (typeof c === 'string') return c;
        return c.baseVal || '';
    }

    function textOf(el) {
        var text = '';
        try {
            text = el.getAttribute('aria-label') || el.getAttribute('title') || el.innerText || el.textContent || '';
        } catch (e) {}
        return String(text).replace(/\s+/g, ' ').replace(/^\s+|\s+$/g, '');
    }

    function labelOf(el) {
        if (!el) return '';
        var label = textOf(el);
        if (!label) {
            try {
                var img = el.querySelector ? el.querySelector('img[alt]') : null;
                if (img) label = img.getAttribute('alt') || '';
            } catch (e) {}
        }
        if (!label) {
            var tag = (el.tagName || '').toLowerCase();
            if (tag === 'video') label = 'Pemutar video';
            else if (tag === 'iframe') label = 'Pemutar / embed';
            else if (tag === 'input') label = el.value || 'Tombol';
        }
        if (label.length > 42) label = label.substring(0, 42) + '…';
        return label;
    }

    function visible(el) {
        if (!el || !el.getBoundingClientRect) return false;
        var b = rectOf(el);
        if (b.width < 8 || b.height < 8) return false;
        var vh = window.innerHeight || 1;
        var vw = window.innerWidth || 1;
        var margin = vh * VIEWPORT_MARGIN;
        if (b.bottom < -margin || b.top > vh + margin) return false;
        if (b.right < -margin || b.left > vw + margin) return false;
        var s = styleOf(el);
        if (!s) return false;
        if (s.display === 'none' || s.visibility === 'hidden') return false;
        if (s.pointerEvents === 'none') return false;
        var opacity = parseFloat(s.opacity || '1');
        if (!isNaN(opacity) && opacity <= 0.05) return false;
        return true;
    }

    function visibleSimple(el) {
        if (!el || !el.getBoundingClientRect) return false;
        var b = rectOf(el);
        if (b.width < 8 || b.height < 8) return false;
        var s = styleOf(el);
        return !!(s && s.display !== 'none' && s.visibility !== 'hidden');
    }

    function isOnScreen(el) {
        var b = rectOf(el);
        var vw = window.innerWidth || 0;
        var vh = window.innerHeight || 0;
        return b.bottom > 0 && b.top < vh && b.right > 0 && b.left < vw;
    }

    function distToCenter(el) {
        var vx = (window.innerWidth || 0) / 2;
        var vy = (window.innerHeight || 0) / 2;
        var b = rectOf(el);
        return Math.abs(b.left + b.width / 2 - vx) + Math.abs(b.top + b.height / 2 - vy);
    }

    function isSemantic(el) {
        var tag = (el.tagName || '').toLowerCase();
        if (tag === 'a' || tag === 'button' || tag === 'input' || tag === 'video' || tag === 'iframe') return true;
        var role = el.getAttribute ? el.getAttribute('role') : '';
        if (role === 'button' || role === 'tab') return true;
        return !!(el.hasAttribute && el.hasAttribute('onclick'));
    }

    // ==================== styles & overlay ====================

    function installStyles() {
        if (document.getElementById(STYLE_ID)) return;
        var css =
            '#' + HIGHLIGHT_ID + '{' +
                'position:fixed !important;' +
                'z-index:2147483646 !important;' +
                'box-sizing:border-box !important;' +
                'border:3px solid #E50914 !important;' +
                'border-radius:10px !important;' +
                'background:rgba(229,9,20,0.10) !important;' +
                'box-shadow:0 0 0 2px rgba(0,0,0,0.55),0 0 22px rgba(229,9,20,0.75) !important;' +
                'pointer-events:none !important;' +
                'display:none;' +
                'transition:left .12s ease-out,top .12s ease-out,width .12s ease-out,height .12s ease-out}' +
            '#' + LABEL_ID + '{' +
                'position:fixed !important;' +
                'z-index:2147483647 !important;' +
                'max-width:320px !important;' +
                'overflow:hidden !important;' +
                'text-overflow:ellipsis !important;' +
                'white-space:nowrap !important;' +
                'background:#E50914 !important;' +
                'color:#fff !important;' +
                'font:700 12px/1.4 sans-serif !important;' +
                'padding:3px 8px !important;' +
                'border-radius:6px !important;' +
                'pointer-events:none !important;' +
                'display:none;' +
                'box-shadow:0 2px 8px rgba(0,0,0,0.6) !important}';
        var style = document.createElement('style');
        style.id = STYLE_ID;
        style.type = 'text/css';
        style.appendChild(document.createTextNode(css));
        (document.head || document.documentElement).appendChild(style);
    }

    function installOverlay() {
        if (document.getElementById(HIGHLIGHT_ID)) {
            highlightEl = document.getElementById(HIGHLIGHT_ID);
            labelEl = document.getElementById(LABEL_ID);
            return;
        }
        highlightEl = document.createElement('div');
        highlightEl.id = HIGHLIGHT_ID;
        labelEl = document.createElement('div');
        labelEl.id = LABEL_ID;
        // Attach to <html>, not <body>: a transformed body would break position:fixed.
        document.documentElement.appendChild(highlightEl);
        document.documentElement.appendChild(labelEl);
    }

    function updateOverlay() {
        if (!highlightEl || !labelEl) return;
        if (!currentEl || !inDoc(currentEl)) {
            hideOverlay();
            return;
        }
        if (document.fullscreenElement || document.webkitFullscreenElement) {
            hideOverlay();
            return;
        }
        if (!isOnScreen(currentEl)) {
            hideOverlay();
            return;
        }
        var b = rectOf(currentEl);
        if (b.width < 1 || b.height < 1) {
            hideOverlay();
            return;
        }
        var vw = window.innerWidth || 0;
        var vh = window.innerHeight || 0;
        var pad = 5;
        var left = Math.max(-10, b.left - pad);
        var top = Math.max(-10, b.top - pad);
        var right = Math.min(vw + 10, b.right + pad);
        var bottom = Math.min(vh + 10, b.bottom + pad);

        highlightEl.style.display = 'block';
        highlightEl.style.left = left + 'px';
        highlightEl.style.top = top + 'px';
        highlightEl.style.width = Math.max(0, right - left) + 'px';
        highlightEl.style.height = Math.max(0, bottom - top) + 'px';

        var label = labelOf(currentEl);
        if (label) {
            labelEl.textContent = label;
            labelEl.style.display = 'block';
            labelEl.style.left = Math.max(4, left) + 'px';
            labelEl.style.top = (top > 28 ? top - 24 : bottom + 6) + 'px';
            labelEl.style.maxWidth = Math.max(80, Math.min(320, vw - 16)) + 'px';
        } else {
            labelEl.style.display = 'none';
        }
    }

    function scheduleOverlayUpdate() {
        if (updatePending) return;
        updatePending = true;
        var run = function () {
            updatePending = false;
            updateOverlay();
        };
        if (window.requestAnimationFrame) window.requestAnimationFrame(run);
        else setTimeout(run, 60);
    }

    function hideOverlay() {
        if (highlightEl) highlightEl.style.display = 'none';
        if (labelEl) labelEl.style.display = 'none';
    }

    function scheduleHide() {
        if (hideTimer) clearTimeout(hideTimer);
        hideTimer = setTimeout(function () {
            hideOverlay();
        }, 6000);
    }

    function showOverlay() {
        updateOverlay();
        scheduleHide();
    }

    // ==================== scanning ====================

    function collect() {
        var nodes = document.querySelectorAll(SELECTOR);
        var list = [];
        var i;
        var el;
        var b;

        for (i = 0; i < nodes.length; i++) {
            el = nodes[i];
            if (!el || el.id === HIGHLIGHT_ID || el.id === LABEL_ID) continue;
            if (!visible(el)) continue;
            b = rectOf(el);
            if (b.width < 22 || b.height < 22) continue;
            list.push(el);
        }

        if (list.length < 2) return list;

        // Tag candidates so nested elements can be detected without O(n^2) lookups.
        for (i = 0; i < list.length; i++) {
            list[i].__pixeltvIndex = i;
        }

        var descCount = [];
        var ancestors = [];
        for (i = 0; i < list.length; i++) {
            descCount.push(0);
            ancestors.push([]);
        }

        for (i = 0; i < list.length; i++) {
            var p = list[i].parentElement;
            var guard = 0;
            while (p && p.nodeType === 1 && guard < 80) {
                var pi = p.__pixeltvIndex;
                if (typeof pi === 'number' && list[pi] === p) {
                    ancestors[i].push(pi);
                    descCount[pi] = descCount[pi] + 1;
                }
                p = p.parentElement;
                guard++;
            }
        }

        // A candidate holding more than one candidate is a container (grid, list,
        // page section): drop it so its children participate instead.
        var removed = [];
        for (i = 0; i < list.length; i++) {
            removed.push(descCount[i] > 1);
        }

        // Among the rest keep only the outermost element (highlight the card,
        // click the semantic child later).
        var finalList = [];
        for (i = 0; i < list.length; i++) {
            if (removed[i]) continue;
            var keep = true;
            var anc = ancestors[i];
            for (var j = 0; j < anc.length; j++) {
                if (!removed[anc[j]]) {
                    keep = false;
                    break;
                }
            }
            if (keep) finalList.push(list[i]);
        }

        // A movie page can have thousands of nodes. Keep the ones nearest the
        // viewport, the rest appear after a scroll and a rebuild.
        if (finalList.length > 600) {
            finalList.sort(function (a, c) {
                return distToCenter(a) - distToCenter(c);
            });
            finalList = finalList.slice(0, 600);
        }

        return finalList;
    }

    function sameRow(row, r) {
        var rowH = row.bottom - row.top;
        var h = r.bottom - r.top;
        if (rowH <= 0 || h <= 0) return false;
        var minH = Math.min(rowH, h);
        var maxH = Math.max(rowH, h);
        // A banner or a full-height sidebar is its own row.
        if (minH < maxH * 0.55) return false;
        var topDiff = Math.abs(r.top - row.top);
        return topDiff <= Math.max(24, minH * 0.35);
    }

    function buildRows(list) {
        var items = list.slice(0);
        items.sort(function (a, b) {
            var ra = rectOf(a);
            var rb = rectOf(b);
            return (ra.top - rb.top) || (ra.left - rb.left);
        });

        var built = [];
        var i;
        for (i = 0; i < items.length; i++) {
            var el = items[i];
            var r = rectOf(el);
            var row = built.length ? built[built.length - 1] : null;
            if (row && sameRow(row, r)) {
                row.items.push(el);
                if (r.top < row.top) row.top = r.top;
                if (r.bottom > row.bottom) row.bottom = r.bottom;
            } else {
                built.push({ top: r.top, bottom: r.bottom, items: [el] });
            }
        }

        for (i = 0; i < built.length; i++) {
            built[i].items.sort(function (a, b) {
                return rectOf(a).left - rectOf(b).left;
            });
        }
        return built;
    }

    function rebuild(force) {
        var now = new Date().getTime();
        if (!force) {
            if (!dirty && rows.length && now - lastBuild < 1500) return;
            if (now - lastBuild < 300) return;
        }
        dirty = false;
        lastBuild = now;
        var list = collect();
        rows = buildRows(list);
        if (currentEl && !inDoc(currentEl)) currentEl = null;
    }

    // ==================== geometry / navigation ====================

    function findPosition(el) {
        for (var i = 0; i < rows.length; i++) {
            var items = rows[i].items;
            for (var j = 0; j < items.length; j++) {
                if (items[j] === el) return { r: i, c: j };
            }
        }
        return null;
    }

    function nearestToCenter() {
        var best = null;
        var bestD = Infinity;
        for (var i = 0; i < rows.length; i++) {
            for (var j = 0; j < rows[i].items.length; j++) {
                var el = rows[i].items[j];
                var d = distToCenter(el);
                if (d < bestD) {
                    bestD = d;
                    best = el;
                }
            }
        }
        return best;
    }

    function nearestToPoint(pt) {
        if (!pt) return nearestToCenter();
        var best = null;
        var bestD = Infinity;
        for (var i = 0; i < rows.length; i++) {
            for (var j = 0; j < rows[i].items.length; j++) {
                var el = rows[i].items[j];
                var b = rectOf(el);
                var d = Math.abs(b.left + b.width / 2 - pt.x) + Math.abs(b.top + b.height / 2 - pt.y);
                if (d < bestD) {
                    bestD = d;
                    best = el;
                }
            }
        }
        return best;
    }

    function resolve(pos, direction) {
        if (direction === 'left' || direction === 'right') {
            var row = rows[pos.r];
            if (!row) return null;
            var nc = pos.c + (direction === 'right' ? 1 : -1);
            if (nc < 0 || nc >= row.items.length) return null;
            return row.items[nc];
        }

        var ref = rows[pos.r] ? rows[pos.r].items[pos.c] : currentEl;
        var refX = ref ? cxOf(ref) : (lastPoint ? lastPoint.x : (window.innerWidth || 0) / 2);
        var step = direction === 'up' ? -1 : 1;

        for (var i = pos.r + step; i >= 0 && i < rows.length; i += step) {
            var row2 = rows[i];
            if (!row2 || !row2.items.length) continue;
            var best = null;
            var bestD = Infinity;
            for (var j = 0; j < row2.items.length; j++) {
                var d = Math.abs(cxOf(row2.items[j]) - refX);
                if (d < bestD) {
                    bestD = d;
                    best = row2.items[j];
                }
            }
            return best;
        }
        return null;
    }

    function scrollIntoViewSafe(el) {
        try {
            el.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'smooth' });
        } catch (e) {
            try {
                el.scrollIntoView();
            } catch (e2) {}
        }
    }

    function dispatchHover(el, x, y) {
        try {
            var opts = { bubbles: true, cancelable: true, view: window, clientX: x, clientY: y };
            el.dispatchEvent(new MouseEvent('mouseover', opts));
            el.dispatchEvent(new MouseEvent('mousemove', opts));
        } catch (e) {}
    }

    function focusPoint(el) {
        if (!el) return;
        currentEl = el;
        var b = rectOf(el);
        lastPoint = { x: b.left + b.width / 2, y: b.top + b.height / 2 };
        scrollIntoViewSafe(el);
        showOverlay();
        dispatchHover(el, lastPoint.x, lastPoint.y);
    }

    function pageScroll(direction, retry) {
        var dh = Math.round((window.innerHeight || 600) * 0.7);
        var dw = Math.round((window.innerWidth || 800) * 0.7);
        if (direction === 'down') window.scrollBy(0, dh);
        else if (direction === 'up') window.scrollBy(0, -dh);
        else if (direction === 'left') window.scrollBy(-dw, 0);
        else if (direction === 'right') window.scrollBy(dw, 0);
        scheduleOverlayUpdate();

        if (retry) {
            setTimeout(function () {
                rebuild(true);
                var anchor = null;
                if (currentEl && findPosition(currentEl)) anchor = currentEl;
                else if (lastPoint) anchor = nearestToPoint(lastPoint);
                else anchor = nearestToCenter();
                if (anchor) {
                    currentEl = anchor;
                    showOverlay();
                }
            }, 330);
        }
    }

    function navigate(direction) {
        rebuild(false);

        if (!rows.length) {
            pageScroll(direction, true);
            return;
        }

        var pos = currentEl ? findPosition(currentEl) : null;
        if (!pos) {
            var start = nearestToCenter();
            if (!start) {
                pageScroll(direction, true);
                return;
            }
            focusPoint(start);
            return;
        }

        var target = resolve(pos, direction);
        if (target) {
            focusPoint(target);
            return;
        }

        // Nothing in that direction: scroll a page and try once more.
        pageScroll(direction, false);
        setTimeout(function () {
            rebuild(true);
            var anchor = null;
            if (currentEl && findPosition(currentEl)) anchor = currentEl;
            else if (lastPoint) anchor = nearestToPoint(lastPoint);
            else anchor = nearestToCenter();
            if (!anchor) return;

            var p = findPosition(anchor);
            var t = p ? resolve(p, direction) : null;
            if (t) {
                focusPoint(t);
            } else {
                currentEl = anchor;
                showOverlay();
            }
        }, 330);
    }

    // ==================== clicking ====================

    function clickTargetFor(el) {
        if (!el) return null;
        var tag = (el.tagName || '').toLowerCase();
        var role = el.getAttribute ? el.getAttribute('role') : '';
        if (tag === 'a' || tag === 'button' || tag === 'input' || tag === 'video' || tag === 'iframe' ||
            role === 'button' || role === 'tab' || (el.hasAttribute && el.hasAttribute('onclick'))) {
            return el;
        }
        if (!el.querySelectorAll) return el;
        var found = el.querySelectorAll('a[href],button,[onclick],[role="button"],[role="tab"],input[type="submit"],input[type="button"]');
        var best = null;
        var bestArea = -1;
        for (var i = 0; i < found.length; i++) {
            if (!visibleSimple(found[i])) continue;
            var a = areaOf(found[i]);
            if (a > bestArea) {
                bestArea = a;
                best = found[i];
            }
        }
        return best || el;
    }

    function fireMouse(el, type, x, y) {
        try {
            var evt = new MouseEvent(type, {
                bubbles: true,
                cancelable: true,
                view: window,
                clientX: x,
                clientY: y,
                button: 0
            });
            el.dispatchEvent(evt);
        } catch (e) {
            try {
                var legacy = document.createEvent('MouseEvents');
                legacy.initMouseEvent(type, true, true, window, 1, 0, 0, x, y, false, false, false, false, 0, null);
                el.dispatchEvent(legacy);
            } catch (e2) {}
        }
    }

    function dispatchClick(el) {
        if (!el) return;
        var b = rectOf(el);
        var x = b.left + b.width / 2;
        var y = b.top + b.height / 2;
        // One click only. The previous version fired .click() AND a synthetic
        // click, which toggled play buttons and modals twice.
        fireMouse(el, 'mousedown', x, y);
        fireMouse(el, 'mouseup', x, y);
        fireMouse(el, 'click', x, y);
    }

    function normalizedPoint(el) {
        var b = rectOf(el);
        var vw = Math.max(1, window.innerWidth || 1);
        var vh = Math.max(1, window.innerHeight || 1);
        return {
            x: (b.left + b.width / 2) / vw,
            y: (b.top + b.height / 2) / vh
        };
    }

    // ==================== play / server helpers ====================

    function findPlayCandidate() {
        var nodes = document.querySelectorAll(
            'button,a,[role="button"],[onclick],[class*="play"],[class*="watch"],' +
            '[class*="nonton"],[class*="tonton"],[class*="stream"]'
        );
        var best = null;
        var bestScore = 0;

        for (var i = 0; i < nodes.length; i++) {
            var el = nodes[i];
            if (!visible(el)) continue;
            var b = rectOf(el);
            if (b.height > 280) continue;
            var hay = (
                (el.getAttribute('aria-label') || '') + ' ' +
                (el.getAttribute('title') || '') + ' ' +
                classOf(el) + ' ' +
                (el.id || '') + ' ' +
                textOf(el)
            ).toLowerCase();
            var score = 0;
            if (/play|nonton|tonton|watch|stream|mulai|resume|lanjut/.test(hay)) score += 6;
            if (/play|watch|nonton|tonton/.test(classOf(el) + ' ' + (el.id || ''))) score += 3;
            if (closestSel(el, '[class*="player"],[id*="player"],[class*="video"]')) score += 3;
            if (b.width >= 60 && b.height >= 32) score += 1;
            try {
                if (el.querySelector('svg,[class*="play"]')) score += 1;
            } catch (e) {}
            if (score > bestScore) {
                bestScore = score;
                best = el;
            }
        }

        if (best && bestScore >= 5) return best;

        // Many sites put the player in an iframe. Tap the biggest one.
        var frames = document.querySelectorAll('iframe');
        var bestFrame = null;
        var bestArea = 0;
        for (var j = 0; j < frames.length; j++) {
            var f = frames[j];
            if (!visible(f)) continue;
            var fb = rectOf(f);
            if (fb.height < 120 || fb.width < 200) continue;
            var src = ((f.src || '') + ' ' + classOf(f) + ' ' + (f.id || '')).toLowerCase();
            var looksPlayer = /player|embed|stream|video|vid|movie|film|server/.test(src);
            var area = fb.width * fb.height;
            if ((looksPlayer || !bestFrame) && area > bestArea) {
                bestArea = area;
                bestFrame = f;
            }
        }
        if (bestFrame) return bestFrame;
        return bestScore > 0 ? best : null;
    }

    function serverCandidates() {
        var nodes = document.querySelectorAll(
            'a[href],button,[role="tab"],[class*="server"],[class*="mirror"],' +
            '[class*="tab"],[class*="episode"],[class*="season"]'
        );
        var out = [];
        for (var i = 0; i < nodes.length; i++) {
            var el = nodes[i];
            if (!visible(el)) continue;
            var b = rectOf(el);
            if (b.height > 170 || b.width > 460) continue;
            var hay = (
                (el.getAttribute('aria-label') || '') + ' ' +
                (el.getAttribute('title') || '') + ' ' +
                classOf(el) + ' ' +
                (el.id || '') + ' ' +
                (el.getAttribute('href') || '') + ' ' +
                textOf(el)
            ).toLowerCase();
            if (/download|unduh|iklan|ads?\b|promo|telegram|whatsapp|livechat|login|daftar|register/.test(hay)) continue;
            if (!/server|mirror|hosting|hd|sd|360|480|540|720|1080|episode|season|eps|putar|nonton|stream/.test(hay)) continue;
            if (out.indexOf(el) === -1) out.push(el);
        }
        out.sort(function (a, b2) {
            var ra = rectOf(a);
            var rb = rectOf(b2);
            return (ra.top - rb.top) || (ra.left - rb.left);
        });
        return out;
    }

    function mainVideo() {
        var vids = document.querySelectorAll('video');
        var fallback = null;
        var playing = null;
        for (var i = 0; i < vids.length; i++) {
            var v = vids[i];
            if (!fallback) fallback = v;
            if (v.readyState > 0 && !isNaN(v.duration) && v.duration > 0) fallback = v;
            if (!v.paused && !v.ended) {
                if (!playing || (v.readyState > playing.readyState)) playing = v;
            }
        }
        return playing || fallback;
    }

    // ==================== public API ====================

    window.__pixeltv_navigate = function (direction) {
        if (direction === 'up' || direction === 'down' || direction === 'left' || direction === 'right') {
            navigate(direction);
        }
    };

    window.__pixeltv_click = function () {
        rebuild(false);
        if (!currentEl || !inDoc(currentEl)) {
            currentEl = nearestToCenter();
            if (currentEl) showOverlay();
        }
        if (!currentEl) return { ok: false };

        var target = clickTargetFor(currentEl);
        if (!target) return { ok: false };

        var tag = (target.tagName || '').toLowerCase();
        if (tag === 'iframe' || tag === 'video') {
            if (!isOnScreen(target)) {
                try { target.scrollIntoView({ block: 'center' }); } catch (e) {}
            }
            var pt = normalizedPoint(target);
            return { ok: true, native: true, x: pt.x, y: pt.y, label: labelOf(target) };
        }

        dispatchClick(target);
        return { ok: true, native: false, label: labelOf(target) };
    };

    window.__pixeltv_play = function () {
        rebuild(false);

        var video = mainVideo();
        if (video) {
            try {
                if (video.paused) {
                    video.play();
                    return { ok: true, native: false, message: '▶ Memutar video' };
                }
                if (video.requestFullscreen) video.requestFullscreen();
                else if (video.webkitEnterFullscreen) video.webkitEnterFullscreen();
                return { ok: true, native: false, message: '⛶ Fullscreen' };
            } catch (e) {}
        }

        var cand = findPlayCandidate();
        if (!cand) {
            return { ok: false, message: 'Tombol putar tidak ditemukan — coba Mode Kursor' };
        }

        var tag = (cand.tagName || '').toLowerCase();
        if (tag === 'iframe' || tag === 'video') {
            if (!isOnScreen(cand)) {
                try { cand.scrollIntoView({ block: 'center' }); } catch (e) {}
            }
            var pt = normalizedPoint(cand);
            return {
                ok: true,
                native: true,
                x: pt.x,
                y: pt.y,
                message: '▶ Menekan pemutar'
            };
        }

        focusPoint(cand);
        dispatchClick(cand);
        return { ok: true, native: false, message: '▶ ' + (labelOf(cand) || 'Tombol putar') };
    };

    window.__pixeltv_next_server = function () {
        rebuild(false);
        var list = serverCandidates();
        if (!list.length) {
            return { ok: false, message: 'Tab server tidak ditemukan — coba Mode Kursor' };
        }
        serverIndex = (serverIndex + 1) % list.length;
        var el = list[serverIndex];
        focusPoint(el);
        dispatchClick(el);
        return {
            ok: true,
            native: false,
            message: 'Server: ' + (labelOf(el) || (serverIndex + 1))
        };
    };

    window.__pixeltv_video_state = function () {
        var v = mainVideo();
        if (!v) return null;
        var d = v.duration;
        if (!isFinite(d) || d < 0) d = 0;
        var t = v.currentTime || 0;
        if (!isFinite(t) || t < 0) t = 0;
        return { paused: !!v.paused, t: t, d: d, ended: !!v.ended };
    };

    window.__pixeltv_video_toggle = function () {
        var v = mainVideo();
        if (!v) return false;
        try {
            if (v.paused) v.play();
            else v.pause();
            return true;
        } catch (e) {
            return false;
        }
    };

    window.__pixeltv_video_seek = function (seconds) {
        var v = mainVideo();
        if (!v) return false;
        try {
            var t = (v.currentTime || 0) + Number(seconds || 0);
            var d = v.duration;
            if (isFinite(d) && d > 0) t = Math.min(d - 0.3, t);
            v.currentTime = Math.max(0, t);
            return true;
        } catch (e) {
            return false;
        }
    };

    window.__pixeltv_hide = function () {
        hideOverlay();
    };

    // ==================== ad / popup hygiene ====================

    function hideAds() {
        var selectors = [
            '[class*="ads"]',
            '[id*="ads"]',
            '[class*="popup"]',
            '[id*="popup"]',
            'iframe[src*="ad"]',
            '.adsbygoogle',
            '[class*="iklan"]',
            '[id*="iklan"]'
        ];
        for (var i = 0; i < selectors.length; i++) {
            var found;
            try {
                found = document.querySelectorAll(selectors[i]);
            } catch (e) {
                continue;
            }
            for (var j = 0; j < found.length; j++) {
                var el = found[j];
                try {
                    if (el.querySelector && el.querySelector('video')) continue;
                    if (closestSel(el, '.player,[class*="player"],[class*="play"]')) continue;
                    el.style.display = 'none';
                } catch (e2) {}
            }
        }
    }

    // ==================== init ====================

    function init() {
        installStyles();
        installOverlay();
        hideAds();

        try {
            window.open = function () { return null; };
        } catch (e) {}

        var metas = document.querySelectorAll('meta[http-equiv="refresh"]');
        for (var i = metas.length - 1; i >= 0; i--) {
            if (metas[i].parentNode) metas[i].parentNode.removeChild(metas[i]);
        }

        var body = document.body || document.documentElement;
        if (body && window.MutationObserver) {
            var observer = new MutationObserver(function () {
                dirty = true;
            });
            observer.observe(body, { childList: true, subtree: true });
        }

        window.addEventListener('scroll', scheduleOverlayUpdate, true);
        window.addEventListener('resize', scheduleOverlayUpdate);
        document.addEventListener('fullscreenchange', hideOverlay, true);
        document.addEventListener('webkitfullscreenchange', hideOverlay, true);

        rebuild(true);
        console.log('PIXELTV Nav 3.0 loaded: ' + rows.length + ' rows');
    }

    init();
})();
