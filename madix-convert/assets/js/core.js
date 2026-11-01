/* ==========================================================
   MADIX Convert — النواة المشتركة (التطبيق + لوحة الإدارة)
   عناصر DOM، أيقونات، اتصال بالسيرفر، نوافذ، تنبيهات، تخزين محلي
   Py | Sohaib
   ========================================================== */
(function () {
  'use strict';
  var K = (window.K = window.K || {});

  // ---------- بناء العناصر بأمان (بدون innerHTML لبيانات المستخدم) ----------
  function el(tag, props) {
    var node = document.createElement(tag);
    var children = Array.prototype.slice.call(arguments, 2);
    if (props) {
      for (var k in props) {
        if (!Object.prototype.hasOwnProperty.call(props, k)) continue;
        var v = props[k];
        if (v === null || v === undefined || v === false) continue;
        if (k === 'class') node.className = v;
        else if (k === 'text') node.textContent = v;
        else if (k === 'on') {
          for (var ev in v) node.addEventListener(ev, v[ev]);
        } else if (k === 'style' && typeof v === 'object') {
          for (var s in v) node.style.setProperty(s, v[s]);
        } else if (k === 'dataset') {
          for (var d in v) node.dataset[d] = v[d];
        } else if (k === 'value') node.value = v;
        else if (k === 'checked' || k === 'disabled' || k === 'hidden' || k === 'selected' || k === 'multiple' || k === 'readOnly') node[k] = !!v;
        else node.setAttribute(k, v === true ? '' : String(v));
      }
    }
    append(node, children);
    return node;
  }
  function append(node, children) {
    for (var i = 0; i < children.length; i++) {
      var c = children[i];
      if (c === null || c === undefined || c === false) continue;
      if (Array.isArray(c)) append(node, c);
      else if (c instanceof Node) node.appendChild(c);
      else node.appendChild(document.createTextNode(String(c)));
    }
  }
  K.el = el;
  K.clear = function (node) { while (node && node.firstChild) node.removeChild(node.firstChild); return node; };
  K.$ = function (sel, root) { return (root || document).querySelector(sel); };

  // ---------- الأيقونات ----------
  var P = {
    camera: '<path d="M4 8h3l2-3h6l2 3h3a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1z"/><circle cx="12" cy="13.5" r="3.5"/>',
    image: '<rect x="3" y="4" width="18" height="16" rx="2.5"/><circle cx="9" cy="10" r="2"/><path d="M21 16l-5-5-9 9"/>',
    scan: '<path d="M3 8V5a2 2 0 0 1 2-2h3M16 3h3a2 2 0 0 1 2 2v3M21 16v3a2 2 0 0 1-2 2h-3M8 21H5a2 2 0 0 1-2-2v-3"/><path d="M7 12h10"/>',
    sheet: '<rect x="3" y="3" width="18" height="18" rx="2.5"/><path d="M3 9h18M3 15h18M9 3v18"/>',
    copy: '<rect x="8" y="8" width="13" height="13" rx="2.5"/><path d="M16 8V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h3"/>',
    shield: '<path d="M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z"/><path d="M9 12l2 2 4-4"/>',
    wallet: '<path d="M3 7a2 2 0 0 1 2-2h13v4"/><rect x="3" y="7" width="18" height="13" rx="2.5"/><path d="M16 13.5h2"/>',
    ticket: '<path d="M4 6h16a1 1 0 0 1 1 1v3a2 2 0 0 0 0 4v3a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1v-3a2 2 0 0 0 0-4V7a1 1 0 0 1 1-1z"/><path d="M14 6v12" stroke-dasharray="2 2.5"/>',
    sliders: '<path d="M4 6h9M17 6h3M4 12h3M11 12h9M4 18h11M19 18h1"/><circle cx="15" cy="6" r="2"/><circle cx="9" cy="12" r="2"/><circle cx="17" cy="18" r="2"/>',
    home: '<path d="M3 11l9-7 9 7"/><path d="M5 10v10h14V10"/><path d="M10 20v-6h4v6"/>',
    files: '<path d="M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"/><path d="M14 3v6h6M8 13h8M8 17h5"/>',
    plus: '<path d="M12 5v14M5 12h14"/>',
    minus: '<path d="M5 12h14"/>',
    trash: '<path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3"/>',
    rotl: '<path d="M3 12a9 9 0 1 0 3-6.7L3 8"/><path d="M3 3v5h5"/>',
    rotr: '<path d="M21 12a9 9 0 1 1-3-6.7L21 8"/><path d="M21 3v5h-5"/>',
    crop: '<path d="M6 2v14a2 2 0 0 0 2 2h14"/><path d="M18 22V8a2 2 0 0 0-2-2H2"/>',
    sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>',
    moon: '<path d="M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z"/>',
    auto: '<circle cx="12" cy="12" r="9"/><path d="M12 3v18"/><path d="M12 3a9 9 0 0 1 0 18z" fill="currentColor" stroke="none"/>',
    chevl: '<path d="M15 6l-6 6 6 6"/>',
    chevr: '<path d="M9 6l6 6-6 6"/>',
    back: '<path d="M5 12h14M13 6l6 6-6 6"/>',
    search: '<circle cx="11" cy="11" r="7"/><path d="M20 20l-3.5-3.5"/>',
    share: '<circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><path d="M8.6 13.5l6.8 4M15.4 6.5l-6.8 4"/>',
    download: '<path d="M12 3v12M7 10l5 5 5-5M5 21h14"/>',
    upload: '<path d="M12 21V9M7 14l5-5 5 5M5 3h14"/>',
    printer: '<path d="M6 9V3h12v6"/><rect x="3" y="9" width="18" height="9" rx="2"/><path d="M6 14h12v7H6z"/>',
    check: '<path d="M5 12l5 5L20 7"/>',
    x: '<path d="M6 6l12 12M18 6L6 18"/>',
    alert: '<path d="M12 3.5l9.5 17h-19z"/><path d="M12 10v4.5M12 17.5v.01"/>',
    info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v5M12 8h.01"/>',
    edit: '<path d="M4 20h4L19 9l-4-4L4 16z"/><path d="M13.5 6.5l4 4"/>',
    layers: '<path d="M12 3l9 5-9 5-9-5z"/><path d="M3 13l9 5 9-5"/>',
    sparkles: '<path d="M12 3l1.8 4.7L18.5 9.5l-4.7 1.8L12 16l-1.8-4.7L5.5 9.5l4.7-1.8z"/><path d="M19 15l.8 2.2L22 18l-2.2.8L19 21l-.8-2.2L16 18l2.2-.8z"/>',
    eye: '<path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z"/><circle cx="12" cy="12" r="3"/>',
    eyeoff: '<path d="M3 3l18 18M10.6 5.1A10 10 0 0 1 12 5c6.5 0 10 7 10 7a17 17 0 0 1-3.2 4.1M6.6 6.6A17 17 0 0 0 2 12s3.5 7 10 7a9.6 9.6 0 0 0 5.4-1.6"/><path d="M9.9 9.9a3 3 0 0 0 4.2 4.2"/>',
    logout: '<path d="M15 4h3a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-3"/><path d="M10 17l-5-5 5-5M5 12h11"/>',
    key: '<circle cx="8" cy="15" r="4"/><path d="M10.8 12.2L20 3M16 7l3 3M14 9l2 2"/>',
    user: '<circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0 1 16 0"/>',
    users: '<circle cx="9" cy="8" r="3.5"/><path d="M2.5 20a6.5 6.5 0 0 1 13 0"/><path d="M16 4.5a3.5 3.5 0 0 1 0 7M18 14a6.5 6.5 0 0 1 3.5 6"/>',
    cpu: '<rect x="6" y="6" width="12" height="12" rx="2"/><rect x="9.5" y="9.5" width="5" height="5" rx="1"/><path d="M9 2v4M15 2v4M9 18v4M15 18v4M2 9h4M2 15h4M18 9h4M18 15h4"/>',
    chart: '<path d="M4 20V10M10 20V4M16 20v-7M22 20H2"/>',
    list: '<path d="M8 6h13M8 12h13M8 18h13M3.5 6h.01M3.5 12h.01M3.5 18h.01"/>',
    msg: '<path d="M21 11.5a8.5 8.5 0 0 1-12.6 7.4L3 20.5l1.6-5.2A8.5 8.5 0 1 1 21 11.5z"/>',
    wand: '<path d="M4 20L15 9"/><path d="M15 3v3M15 12v-1M18 6h3M10 6h2M17.5 4.5l1.5-1.5M17.5 7.5L19 9"/>',
    lock: '<rect x="4" y="11" width="16" height="10" rx="2.5"/><path d="M8 11V7a4 4 0 0 1 8 0v4"/>',
    clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
    phone: '<path d="M5 3h4l2 5-2.5 1.5a11 11 0 0 0 6 6L16 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 5a2 2 0 0 1 2-2z"/>',
    idcard: '<rect x="3" y="5" width="18" height="14" rx="2.5"/><circle cx="9" cy="11" r="2"/><path d="M6 16a3 3 0 0 1 6 0M14 10h4M14 14h4"/>',
    filter: '<path d="M3 5h18l-7 8v6l-4-2v-4z"/>',
    more: '<circle cx="12" cy="5" r="1.2"/><circle cx="12" cy="12" r="1.2"/><circle cx="12" cy="19" r="1.2"/>',
    columns: '<rect x="3" y="3" width="18" height="18" rx="2.5"/><path d="M12 3v18"/>',
    coins: '<circle cx="9" cy="9" r="6"/><path d="M15.5 9.5A6 6 0 1 1 9.5 15.5"/>',
    database: '<ellipse cx="12" cy="5" rx="8" ry="3"/><path d="M4 5v14c0 1.7 3.6 3 8 3s8-1.3 8-3V5"/><path d="M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3"/>',
    refresh: '<path d="M21 12a9 9 0 1 1-3-6.7L21 8"/><path d="M21 3v5h-5"/>',
    grid: '<rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/>',
    bolt: '<path d="M13 2L4 14h7l-1 8 9-12h-7z"/>',
    star: '<path d="M12 3l2.8 5.7 6.2.9-4.5 4.4 1 6.2L12 17.3 6.5 20.2l1-6.2L3 9.6l6.2-.9z"/>',
    gift: '<rect x="3" y="8" width="18" height="5" rx="1"/><path d="M5 13v8h14v-8M12 8v13M12 8S10.5 3 7.5 4.5 9 8 12 8zM12 8s1.5-5 4.5-3.5S15 8 12 8z"/>',
    install: '<rect x="6" y="2" width="12" height="20" rx="3"/><path d="M12 7v7M9 11l3 3 3-3M10 18.5h4"/>',
    link: '<path d="M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1"/><path d="M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1"/>'
  };
  K.icon = function (name, cls) {
    var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('viewBox', '0 0 24 24');
    svg.setAttribute('class', 'ico' + (cls ? ' ' + cls : ''));
    svg.setAttribute('aria-hidden', 'true');
    svg.innerHTML = P[name] || P.info;
    return svg;
  };

  // ---------- أدوات النصوص والأرقام ----------
  K.digits = function (s) {
    return String(s == null ? '' : s)
      .replace(/[٠-٩]/g, function (d) { return String(d.charCodeAt(0) - 0x0660); })
      .replace(/[۰-۹]/g, function (d) { return String(d.charCodeAt(0) - 0x06f0); });
  };
  K.num = function (n) { return Number(n || 0).toLocaleString('en-US'); };
  K.uid = function () {
    if (window.crypto && crypto.getRandomValues) {
      var a = new Uint32Array(3);
      crypto.getRandomValues(a);
      return Date.now().toString(36) + a[0].toString(36) + a[1].toString(36);
    }
    return Date.now().toString(36) + Math.random().toString(36).slice(2);
  };
  function parseUtc(s) {
    if (!s) return null;
    if (s instanceof Date) return s;
    if (typeof s === 'number') return new Date(s);
    var str = String(s);
    if (/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(str)) str = str.replace(' ', 'T') + 'Z';
    var d = new Date(str);
    return isNaN(d.getTime()) ? null : d;
  }
  K.parseDate = parseUtc;
  var dtf, dtfShort, rtf;
  try {
    dtf = new Intl.DateTimeFormat('ar-u-nu-latn', { year: 'numeric', month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });
    dtfShort = new Intl.DateTimeFormat('ar-u-nu-latn', { month: 'short', day: 'numeric' });
    rtf = new Intl.RelativeTimeFormat('ar-u-nu-latn', { numeric: 'auto' });
  } catch (e) { /* متصفح قديم */ }
  K.fmtDate = function (s) { var d = parseUtc(s); if (!d) return '—'; return dtf ? dtf.format(d) : d.toLocaleString(); };
  K.fmtDay = function (s) { var d = parseUtc(s); if (!d) return '—'; return dtfShort ? dtfShort.format(d) : d.toLocaleDateString(); };
  K.ago = function (s) {
    var d = parseUtc(s);
    if (!d) return '—';
    var diff = (d.getTime() - Date.now()) / 1000;
    var abs = Math.abs(diff);
    if (!rtf) return K.fmtDate(d);
    if (abs < 45) return 'الآن';
    if (abs < 3600) return rtf.format(Math.round(diff / 60), 'minute');
    if (abs < 86400) return rtf.format(Math.round(diff / 3600), 'hour');
    if (abs < 86400 * 7) return rtf.format(Math.round(diff / 86400), 'day');
    return K.fmtDate(d);
  };
  K.pagesWord = function (n) {
    n = Number(n) || 0;
    if (n === 1) return 'صفحة';
    if (n === 2) return 'صفحتين';
    if (n >= 3 && n <= 10) return 'صفحات';
    return 'صفحة';
  };
  K.debounce = function (fn, ms) {
    var t;
    return function () { var a = arguments, self = this; clearTimeout(t); t = setTimeout(function () { fn.apply(self, a); }, ms); };
  };

  // ---------- التنبيهات ----------
  K.toast = function (msg, type, ms) {
    var box = document.getElementById('toasts');
    if (!box) return;
    type = type || 'info';
    var iconName = type === 'success' ? 'check' : type === 'error' ? 'alert' : type === 'warn' ? 'alert' : 'info';
    var last = box.lastChild;
    if (last && last._msg === msg && !last.classList.contains('is-out')) return;
    var t = el('div', { class: 'toast ' + type, role: type === 'error' ? 'alert' : 'status' }, K.icon(iconName), el('span', { text: msg }));
    t._msg = msg;
    box.appendChild(t);
    while (box.children.length > 2) box.removeChild(box.firstChild);
    setTimeout(function () {
      t.classList.add('is-out');
      setTimeout(function () { if (t.parentNode) t.parentNode.removeChild(t); }, 260);
    }, ms || (type === 'error' ? 5200 : 3200));
  };

  // ---------- النوافذ المنبثقة ----------
  var openSheets = [];
  K.sheet = function (opts) {
    var layer = document.getElementById('layer');
    var back = el('div', { class: 'backdrop' });
    var closeBtn = el('button', { class: 'btn btn-icon btn-ghost sm', type: 'button', 'aria-label': 'إغلاق' }, K.icon('x'));
    var body = el('div', { class: 'sheet-body' });
    if (opts.body) append(body, [opts.body]);
    var actions = null;
    var sheet = el('div', { class: 'sheet' + (opts.wide ? ' wide' : ''), role: 'dialog', 'aria-modal': 'true', 'aria-label': opts.title || '' },
      el('div', { class: 'sheet-head' }, el('h3', { text: opts.title || '' }), opts.noClose ? null : closeBtn),
      body);
    if (opts.actions && opts.actions.length) {
      actions = el('div', { class: 'sheet-actions' });
      opts.actions.forEach(function (a) {
        var b = el('button', { class: 'btn ' + (a.primary ? 'btn-primary' : a.danger ? 'btn-danger' : ''), type: 'button' }, a.icon ? K.icon(a.icon) : null, a.label);
        b.addEventListener('click', function () {
          if (a.onClick) {
            var r = a.onClick(ctl, b);
            if (r && typeof r.then === 'function') {
              K.busy(b, true);
              r.then(function (keep) { K.busy(b, false); if (keep !== true) ctl.close(); }, function (err) { K.busy(b, false); if (err && err.message) K.toast(err.message, 'error'); });
            } else if (r !== true) ctl.close();
          } else ctl.close();
        });
        actions.appendChild(b);
      });
      sheet.appendChild(actions);
    }
    var closed = false;
    var prevFocus = document.activeElement;
    var ctl = {
      el: sheet, body: body, actions: actions,
      close: function (val) {
        if (closed) return;
        closed = true;
        sheet.classList.add('is-closing');
        back.classList.add('is-closing');
        document.removeEventListener('keydown', onKey);
        openSheets = openSheets.filter(function (s) { return s !== ctl; });
        if (!openSheets.length) document.body.classList.remove('no-scroll');
        setTimeout(function () { if (back.parentNode) back.remove(); if (sheet.parentNode) sheet.remove(); }, 230);
        if (prevFocus && prevFocus.focus) { try { prevFocus.focus({ preventScroll: true }); } catch (e) { /* */ } }
        if (opts.onClose) opts.onClose(val);
      }
    };
    function onKey(e) { if (e.key === 'Escape' && openSheets[openSheets.length - 1] === ctl && !opts.noClose) ctl.close(); }
    closeBtn.addEventListener('click', function () { ctl.close(); });
    if (!opts.noClose) back.addEventListener('click', function () { ctl.close(); });
    document.addEventListener('keydown', onKey);
    layer.appendChild(back);
    layer.appendChild(sheet);
    openSheets.push(ctl);
    document.body.classList.add('no-scroll');
    setTimeout(function () {
      var f = sheet.querySelector('[autofocus]') || sheet.querySelector('input, textarea, select');
      if (f && window.innerWidth > 720) f.focus();
    }, 60);
    return ctl;
  };
  K.closeAllSheets = function () { openSheets.slice().forEach(function (s) { s.close(); }); };
  /** يسكّر آخر نافذة مفتوحة (لزر الرجوع بالجوال) — يرجع true إذا سكّر شي */
  K.closeTopSheet = function () {
    var s = openSheets[openSheets.length - 1];
    if (!s) return false;
    s.close();
    return true;
  };

  K.confirm = function (message, opts) {
    opts = opts || {};
    return new Promise(function (resolve) {
      var done = false;
      K.sheet({
        title: opts.title || 'تأكيد',
        body: el('p', { class: 'muted', style: { 'font-size': '15px' }, text: message }),
        actions: [
          { label: opts.cancel || 'إلغاء', onClick: function () { done = true; resolve(false); } },
          { label: opts.ok || 'تأكيد', primary: !opts.danger, danger: !!opts.danger, onClick: function () { done = true; resolve(true); } }
        ],
        onClose: function () { if (!done) resolve(false); }
      });
    });
  };

  K.prompt = function (opts) {
    return new Promise(function (resolve) {
      var done = false;
      var input = el(opts.multiline ? 'textarea' : 'input', { class: opts.multiline ? 'textarea' : 'input' + (opts.ltr ? ' ltr' : ''), value: opts.value || '', placeholder: opts.placeholder || '', type: opts.type || 'text', autofocus: true, inputmode: opts.inputmode || null });
      var form = el('form', { class: 'stack' }, opts.message ? el('p', { class: 'muted small', text: opts.message }) : null, el('div', { class: 'field' }, opts.label ? el('label', { text: opts.label }) : null, input));
      var sh = K.sheet({
        title: opts.title || '',
        body: form,
        actions: [
          { label: 'إلغاء', onClick: function () { done = true; resolve(null); } },
          { label: opts.ok || 'حفظ', primary: true, onClick: function () { done = true; resolve(input.value); } }
        ],
        onClose: function () { if (!done) resolve(null); }
      });
      form.addEventListener('submit', function (e) { e.preventDefault(); done = true; resolve(input.value); sh.close(); });
      setTimeout(function () { try { input.focus(); if (input.select) input.select(); } catch (e) { /* */ } }, 120);
    });
  };

  K.busy = function (btn, on, label) {
    if (!btn) return;
    if (on) {
      if (btn.dataset.busy === '1') return;
      btn.dataset.busy = '1';
      btn.disabled = true;
      btn._content = Array.prototype.slice.call(btn.childNodes);
      K.clear(btn);
      btn.appendChild(el('span', { class: 'spin' }));
      if (label) btn.appendChild(document.createTextNode(label));
    } else {
      if (btn.dataset.busy !== '1') return;
      btn.dataset.busy = '';
      btn.disabled = false;
      K.clear(btn);
      (btn._content || []).forEach(function (n) { btn.appendChild(n); });
    }
  };

  // ---------- الاتصال بالسيرفر ----------
  // داخل تطبيق الجوال السيرفر بعيد (MADIX_CONFIG) والدخول بتوكن؛ بالمتصفح نفس الموقع بجلسة
  var CFG = window.MADIX_CONFIG || null;
  K.remote = !!CFG;
  K.cleanServer = function (u) {
    u = String(u || '').trim().replace(/\s+/g, '');
    if (!u) return '';
    if (!/^https?:\/\//i.test(u)) u = 'https://' + u;
    return u.replace(/\/(index|admin)\.php.*$/i, '').replace(/[?#].*$/, '').replace(/\/+$/, '');
  };
  K.serverUrl = function () { return K.cleanServer(K.pref.get('server', '') || (CFG && CFG.server) || ''); };
  K.fixedServer = function () { return !!(CFG && CFG.server); };
  K.setServer = function (u) {
    var url = K.cleanServer(u);
    if (!url) return Promise.reject(new Error('اكتب رابط السيرفر'));
    var get = function () {
      return fetch(url + '/index.php?api=me', { headers: { Accept: 'application/json', 'X-Client': 'app' }, cache: 'no-store', credentials: 'omit' })
        .then(function (r) { return r.text(); }, function () { throw new Error('ما قدرنا نوصل للسيرفر'); });
    };
    return get().then(function (txt) {
      if (K.nat && K.nat.on && K.nat.isChallenge(txt)) return K.nat.solveChallenge(txt, url).then(get);
      return txt;
    }).then(function (txt) { try { return JSON.parse(txt); } catch (x) { return null; } })
      .then(function (d) {
        if (!d || !d.app) throw new Error('الرابط غير صحيح');
        K.pref.set('server', url);
        return d;
      }, function (e) { throw e.message ? e : new Error('الرابط غير صحيح'); });
  };
  var api = {
    base: document.body && document.body.classList.contains('is-admin') ? 'admin.php' : 'index.php',
    csrf: '',
    onAuthLost: null
  };
  function endpoint(action) {
    return (K.remote ? K.serverUrl() + '/index.php' : api.base) + '?api=' + encodeURIComponent(action);
  }
  function authHeaders(h) {
    h = h || {};
    h.Accept = 'application/json';
    if (K.remote) {
      h['X-Client'] = 'app';
      h['X-Requested-With'] = 'XMLHttpRequest';
      if (K.nat && K.nat.on && navigator.userAgent) h['User-Agent'] = navigator.userAgent;
      var t = K.pref.get('token', '');
      if (t) h['X-Auth-Token'] = t;
    } else if (api.csrf) {
      h['X-CSRF-Token'] = api.csrf;
    }
    return h;
  }
  api.setToken = function (t) { K.pref.set('token', t || ''); };
  function apiError(msg, status, data) {
    var e = new Error(msg || 'حدث خطأ');
    e.status = status || 0;
    e.data = data || {};
    return e;
  }
  function isChallenge(txt) { return K.remote && K.nat && K.nat.on && K.nat.isChallenge(txt); }
  function challengeError(txt) { var e = apiError('challenge', 0); e.challenge = txt; return e; }
  function solve(e) { return K.nat.solveChallenge(e.challenge, K.serverUrl()); }
  function parseResponse(res) {
    return res.text().then(function (txt) {
      if (isChallenge(txt)) throw challengeError(txt);
      var data = null;
      try { data = JSON.parse(txt); } catch (e) { data = null; }
      if (!data) throw apiError((res.status >= 500 ? 'السيرفر ما رد بشكل صحيح' : 'رد غير متوقع من السيرفر') + ' (' + res.status + ')' + snippet(txt), res.status);
      if (!res.ok || data.ok === false) {
        if (res.status === 401 && api.onAuthLost) api.onAuthLost();
        throw apiError(data.error || 'حدث خطأ', res.status, data);
      }
      if (data.csrf) api.csrf = data.csrf;
      if (data.token) api.setToken(data.token);
      return data;
    });
  }
  /** مقتطف قصير من رد السيرفر لتشخيص المشكلة */
  function snippet(txt) {
    var t = String(txt || '').replace(/<script[\s\S]*?<\/script>/gi, ' ').replace(/<style[\s\S]*?<\/style>/gi, ' ').replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim();
    if (!t) return K.remote ? ' — رد فاضي' : '';
    return K.remote ? ' — ' + t.slice(0, 90) : '';
  }
  /** بالتطبيق: نبعث البيانات كنموذج عادي (مقبول عند كل الاستضافات) */
  function formBody(body) {
    var p = new URLSearchParams();
    Object.keys(body || {}).forEach(function (k) {
      var v = body[k];
      if (v === null || v === undefined) return;
      p.append(k, typeof v === 'object' ? JSON.stringify(v) : String(v));
    });
    return p.toString();
  }
  function netError() { return apiError(navigator.onLine === false ? 'ما في اتصال بالإنترنت' : 'تعذّر الاتصال بالسيرفر — تأكد من النت وحاول مرة ثانية', 0); }
  api.get = function (action, params) {
    var qs = '';
    if (params) {
      qs = Object.keys(params).filter(function (k) { return params[k] !== undefined && params[k] !== null && params[k] !== ''; })
        .map(function (k) { return encodeURIComponent(k) + '=' + encodeURIComponent(params[k]); }).join('&');
    }
    var go = function () {
      return fetch(endpoint(action) + (qs ? '&' + qs : ''), { credentials: K.remote ? 'omit' : 'same-origin', headers: authHeaders(), cache: 'no-store' })
        .then(parseResponse, function () { throw netError(); });
    };
    return go().catch(function (e) { if (e.challenge) return solve(e).then(go); throw e; });
  };
  api.post = function (action, body, retried) {
    return fetch(endpoint(action), {
      method: 'POST', credentials: K.remote ? 'omit' : 'same-origin', cache: 'no-store',
      headers: authHeaders({ 'Content-Type': K.remote ? 'application/x-www-form-urlencoded; charset=UTF-8' : 'application/json' }),
      body: K.remote ? formBody(body) : JSON.stringify(body || {})
    }).then(parseResponse, function () { throw netError(); }).catch(function (e) {
      if (e.challenge && !retried) return solve(e).then(function () { return api.post(action, body, true); });
      if (e.status === 419 && !retried) {
        return api.get('me').then(function () { return api.post(action, body, true); });
      }
      throw e;
    });
  };
  /** رفع ملف مع نسبة التقدم */
  api.upload = function (action, form, onProgress, retried) {
    return new Promise(function (resolve, reject) {
      var xhr = new XMLHttpRequest();
      xhr.open('POST', endpoint(action));
      var hs = authHeaders();
      Object.keys(hs).forEach(function (k) { xhr.setRequestHeader(k, hs[k]); });
      xhr.withCredentials = !K.remote;
      xhr.timeout = 0;
      if (xhr.upload && onProgress) {
        xhr.upload.onprogress = function (e) { if (e.lengthComputable) onProgress(e.loaded / e.total, 'up'); };
        xhr.upload.onload = function () { onProgress(1, 'wait'); };
      }
      xhr.onload = function () {
        if (isChallenge(xhr.responseText) && !retried) {
          return solve(challengeError(xhr.responseText)).then(function () { return api.upload(action, form, onProgress, true); }).then(resolve, reject);
        }
        var data = null;
        try { data = JSON.parse(xhr.responseText); } catch (e) { data = null; }
        if (!data) return reject(apiError(xhr.status === 413 ? 'حجم الصورة أكبر من المسموح على السيرفر' : 'رد غير متوقع من السيرفر (' + xhr.status + ')' + snippet(xhr.responseText), xhr.status));
        if (xhr.status === 419 && !retried) {
          return api.get('me').then(function () { return api.upload(action, form, onProgress, true); }).then(resolve, reject);
        }
        if (xhr.status >= 400 || data.ok === false) {
          if (xhr.status === 401 && api.onAuthLost) api.onAuthLost();
          return reject(apiError(data.error || 'حدث خطأ', xhr.status, data));
        }
        resolve(data);
      };
      xhr.onerror = function () { reject(netError()); };
      xhr.send(form);
    });
  };
  K.api = api;

  // ---------- التخزين المحلي على الجهاز (IndexedDB) ----------
  var DB_NAME = 'madix-convert';
  var dbp = null;
  function idb() {
    if (dbp) return dbp;
    dbp = new Promise(function (resolve, reject) {
      if (!window.indexedDB) return reject(new Error('المتصفح لا يدعم التخزين المحلي'));
      var req = indexedDB.open(DB_NAME, 1);
      req.onupgradeneeded = function () {
        var db = req.result;
        if (!db.objectStoreNames.contains('statements')) {
          var s = db.createObjectStore('statements', { keyPath: 'id' });
          s.createIndex('user', 'userId', { unique: false });
        }
        if (!db.objectStoreNames.contains('images')) {
          var im = db.createObjectStore('images', { keyPath: 'id' });
          im.createIndex('statement', 'statementId', { unique: false });
        }
      };
      req.onsuccess = function () { resolve(req.result); };
      req.onerror = function () { reject(req.error || new Error('تعذّر فتح التخزين المحلي')); };
      req.onblocked = function () { reject(new Error('أغلق نسخ التطبيق الثانية المفتوحة وحاول مرة ثانية')); };
    });
    return dbp;
  }
  function tx(store, mode, fn) {
    return idb().then(function (db) {
      return new Promise(function (resolve, reject) {
        var t = db.transaction(store, mode);
        var os = t.objectStore(store);
        var out;
        var r = fn(os);
        if (r && 'onsuccess' in r) r.onsuccess = function () { out = r.result; };
        t.oncomplete = function () { resolve(out); };
        t.onerror = function () { reject(t.error || new Error('خطأ بالتخزين المحلي')); };
        t.onabort = function () { reject(t.error || new Error('تم إلغاء عملية التخزين — يمكن مساحة الجهاز ممتلئة')); };
      });
    });
  }
  K.db = {
    get: function (store, key) { return tx(store, 'readonly', function (os) { return os.get(key); }); },
    put: function (store, val) { return tx(store, 'readwrite', function (os) { return os.put(val); }); },
    del: function (store, key) { return tx(store, 'readwrite', function (os) { return os.delete(key); }); },
    all: function (store) { return tx(store, 'readonly', function (os) { return os.getAll(); }); },
    byIndex: function (store, index, value) { return tx(store, 'readonly', function (os) { return os.index(index).getAll(value); }); },
    delByIndex: function (store, index, value) {
      return tx(store, 'readwrite', function (os) {
        var req = os.index(index).openKeyCursor(IDBKeyRange.only(value));
        req.onsuccess = function () { var c = req.result; if (c) { os.delete(c.primaryKey); c.continue(); } };
      });
    },
    clear: function (store) { return tx(store, 'readwrite', function (os) { return os.clear(); }); }
  };

  // ---------- مخزن إعدادات صغيرة ----------
  K.pref = {
    get: function (k, d) { try { var v = localStorage.getItem('madix.' + k); return v === null ? d : JSON.parse(v); } catch (e) { return d; } },
    set: function (k, v) { try { localStorage.setItem('madix.' + k, JSON.stringify(v)); } catch (e) { /* */ } }
  };
  K.setTheme = function (t) {
    try { localStorage.setItem('madix.theme', t); } catch (e) { /* */ }
    document.documentElement.setAttribute('data-theme', t);
    var dark = t === 'dark' || (t === 'auto' && window.matchMedia && matchMedia('(prefers-color-scheme: dark)').matches);
    if (K.nat && K.nat.on) K.nat.statusBar(dark);
    var meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.setAttribute('content', dark ? '#090e19' : '#f4f2ec');
  };
  K.getTheme = function () { try { return localStorage.getItem('madix.theme') || 'auto'; } catch (e) { return 'auto'; } };

  // ---------- التوجيه (Router) ----------
  var routes = [];
  K.route = function (pattern, handler) {
    var keys = [];
    var re = new RegExp('^' + pattern.replace(/:[a-zA-Z_]+/g, function (m) { keys.push(m.slice(1)); return '([^/]+)'; }) + '$');
    routes.push({ re: re, keys: keys, handler: handler });
  };
  K.go = function (path, replace) {
    var h = '#' + path;
    if (location.hash === h) { K.resolve(); return; }
    if (replace) location.replace(h); else location.hash = h;
  };
  K.resolve = function () {
    var path = (location.hash || '#/').slice(1) || '/';
    for (var i = 0; i < routes.length; i++) {
      var m = path.match(routes[i].re);
      if (m) {
        var params = {};
        routes[i].keys.forEach(function (k, j) { params[k] = decodeURIComponent(m[j + 1]); });
        routes[i].handler(params);
        return;
      }
    }
    if (routes.length) routes[0].handler({});
  };
  K.startRouter = function () { window.addEventListener('hashchange', K.resolve); K.resolve(); };

  // ---------- شاشة البداية ----------
  var splashStart = Date.now();
  K.hideSplash = function () {
    var s = document.getElementById('splash');
    if (!s || s.classList.contains('is-done')) return;
    var wait = Math.max(0, 1100 - (Date.now() - splashStart));
    setTimeout(function () {
      s.classList.add('is-done');
      setTimeout(function () { if (s.parentNode) s.parentNode.removeChild(s); }, 700);
    }, wait);
  };

  /** تبديل الشاشة — الحركة الناعمة بتصير عبر CSS (viewIn) على كل المتصفحات */
  K.swap = function (root, node) {
    K.clear(root).appendChild(node);
    root.hidden = false;
    try { window.scrollTo({ top: 0, left: 0, behavior: 'instant' }); } catch (e) { window.scrollTo(0, 0); }
  };

  /** اسم التطبيق كشعار أنيق: الكلمة الأولى عريضة والباقي أخف */
  K.wordmark = function (name) {
    name = String(name || 'MADIX Convert').trim();
    var i = name.search(/\s/);
    var first = i < 0 ? name : name.slice(0, i);
    var rest = i < 0 ? '' : name.slice(i + 1);
    var latin = /^[\x20-\x7E]+$/.test(name);
    return el('span', { class: 'wm', dir: latin ? 'ltr' : null }, el('b', { text: first }), rest ? el('span', { text: ' ' + rest }) : null);
  };

  K.footer = function (text) {
    return el('footer', { class: 'footer' },
      el('div', { class: 'f-main', text: (text || 'MADIX Convert — محوّل الكشوفات الذكي') + ' © ' + new Date().getFullYear() }),
      el('div', { class: 'f-sign', text: 'Py | Sohaib' }));
  };

  K.passwordInput = function (opts) {
    var input = el('input', { class: 'input', type: 'password', name: opts.name || 'password', autocomplete: opts.autocomplete || 'current-password', placeholder: opts.placeholder || '••••••', required: true, minlength: opts.minlength || null });
    var tbtn = el('button', { class: 'btn btn-icon btn-ghost sm toggle-pass', type: 'button', 'aria-label': 'إظهار كلمة السر' }, K.icon('eye'));
    tbtn.addEventListener('click', function () {
      var show = input.type === 'password';
      input.type = show ? 'text' : 'password';
      K.clear(tbtn).appendChild(K.icon(show ? 'eyeoff' : 'eye'));
    });
    return { input: input, node: el('div', { class: 'input-wrap' }, K.icon('lock'), input, tbtn) };
  };

  /** تسليم ملف: بالتطبيق قائمة المشاركة/الحفظ، وبالمتصفح تنزيل */
  K.deliver = function (blob, name) {
    if (K.nat && K.nat.on) return K.nat.share(blob, name);
    K.downloadBlob(blob, name);
    return Promise.resolve();
  };

  K.downloadBlob = function (blob, name) {
    var url = URL.createObjectURL(blob);
    var a = el('a', { href: url, download: name });
    document.body.appendChild(a);
    a.click();
    setTimeout(function () { URL.revokeObjectURL(url); a.remove(); }, 1500);
  };

  /** نسخ للحافظة؛ msg رسالة النجاح (افتراضياً "تم النسخ") */
  K.copy = function (text, msg) {
    var ok = function () { K.toast(msg || 'تم النسخ ✓', 'success'); };
    if (K.nat && K.nat.on) return K.nat.copy(text).then(ok, function () { K.toast('ما قدرنا ننسخ', 'error'); });
    if (navigator.clipboard && window.isSecureContext) {
      return navigator.clipboard.writeText(text).then(ok, function () { fallback(); });
    }
    fallback();
    return Promise.resolve();
    function fallback() {
      var ta = el('textarea', { style: { position: 'fixed', top: '-1000px' } });
      ta.value = text;
      document.body.appendChild(ta);
      ta.select();
      try { document.execCommand('copy'); ok(); } catch (e) { K.toast('ما قدرنا ننسخ', 'error'); }
      ta.remove();
    }
  };

  K.registerSW = function () {
    if (!('serviceWorker' in navigator) || location.protocol === 'file:' || K.remote || (K.nat && K.nat.on)) return;
    var v = document.body.getAttribute('data-version') || '1';
    window.addEventListener('load', function () {
      navigator.serviceWorker.register('sw.js?v=' + encodeURIComponent(v)).catch(function () { /* اختياري */ });
    });
  };

  window.addEventListener('error', function (e) {
    if (e && e.message && /ResizeObserver/.test(e.message)) return;
    try { console.error(e.error || e.message); } catch (x) { /* */ }
  });
})();
