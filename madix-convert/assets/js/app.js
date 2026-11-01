/* ==========================================================
   MADIX Convert — التطبيق الرئيسي
   الشاشات: الدخول، الرئيسية، الكشوفات، التصوير والقراءة، المراجعة، المكررات، التصدير، المحفظة، الإعدادات
   Py | Sohaib
   ========================================================== */
(function () {
  'use strict';
  var K = window.K, el = K.el, ic = K.icon, an = K.an;
  var root = document.getElementById('app');
  var S = { app: null, user: null, setup: false, installEvt: null, urls: [], cache: {} };

  // ================================================================
  // أدوات عامة
  // ================================================================
  function objUrl(blob) { var u = URL.createObjectURL(blob); S.urls.push(u); return u; }
  function freeUrls() { S.urls.forEach(function (u) { URL.revokeObjectURL(u); }); S.urls = []; }
  function firstName(n) {
    var p = String(n || '').trim().split(/\s+/);
    if (p.length > 1 && /^(أبو|ابو|أم|ام|عبد)$/.test(p[0])) return p[0] + ' ' + p[1];
    return p[0] || '';
  }
  function appName() { return (S.app && S.app.name) || 'MADIX Convert'; }
  function iconUrl(s) { if (K.remote) return 'assets/img/icon-' + (s >= 512 ? 512 : 192) + '.png'; return 'index.php?asset=icon&s=' + s + '&v=' + encodeURIComponent((S.app && S.app.icon_v) || document.body.getAttribute('data-icon-v') || '0'); }

  function render(node) {
    freeUrls();
    K.closeAllSheets();
    S.keys = null;
    K.swap(root, node);
  }
  document.addEventListener('keydown', function (e) { if (S.keys) S.keys(e); });

  function setBalance(p) {
    if (!S.user) return;
    S.user.pages = p;
    document.querySelectorAll('[data-balance]').forEach(function (n) { n.textContent = K.num(p); });
  }

  // ---------- الهيكل: شريط علوي + محتوى + تذييل + شريط سفلي ----------
  function layout(o) {
    var top;
    if (o.back) {
      top = el('div', { class: 'topbar-in' },
        el('button', { class: 'btn btn-icon btn-ghost', type: 'button', 'aria-label': 'رجوع', on: { click: function () { K.go(o.back); } } }, ic('back')),
        el('div', { class: 'title grow', text: o.title || '' }),
        o.actions || null);
    } else {
      top = el('div', { class: 'topbar-in' },
        el('a', { class: 'brand', href: '#/', 'aria-label': appName() }, el('img', { src: iconUrl(192), alt: '' }), K.wordmark(appName())),
        el('button', { class: 'balance-pill', type: 'button', on: { click: function () { K.go('/wallet'); } }, 'aria-label': 'رصيدك' },
          ic('wallet', 'sm'), el('b', { 'data-balance': '1', text: K.num(S.user ? S.user.pages : 0) }), el('span', { text: 'صفحة' })));
    }
    var offline = el('div', { class: 'offline-bar', hidden: navigator.onLine !== false, id: 'offline-bar' }, 'أنت غير متصل بالإنترنت — الكشوفات المحفوظة على جهازك متاحة');
    return el('div', { class: 'shell' },
      offline,
      el('header', { class: 'topbar' }, top),
      el('main', { class: 'main' + (o.narrow ? ' narrow' : '') }, o.body, K.footer(S.app && S.app.footer)),
      o.cta || null,
      bottomNav(o.nav));
  }

  function bottomNav(active) {
    function item(key, label, icon, path) {
      return el('button', { class: 'nav-item' + (active === key ? ' is-active' : ''), type: 'button', on: { click: function () { K.go(path); } } }, ic(icon), label);
    }
    return el('nav', { class: 'bottomnav', 'aria-label': 'التنقل' },
      el('div', { class: 'bottomnav-in' },
        item('home', 'الرئيسية', 'home', '/'),
        item('list', 'كشوفاتي', 'files', '/statements'),
        el('button', { class: 'nav-fab', type: 'button', 'aria-label': 'كشف جديد', on: { click: function () { startNew('camera'); } } }, ic('scan')),
        item('wallet', 'المحفظة', 'wallet', '/wallet'),
        item('settings', 'الإعدادات', 'sliders', '/settings')));
  }

  window.addEventListener('online', function () { var b = document.getElementById('offline-bar'); if (b) b.hidden = true; });
  window.addEventListener('offline', function () { var b = document.getElementById('offline-bar'); if (b) b.hidden = false; });

  // ================================================================
  // التخزين المحلي للكشوفات
  // ================================================================
  function listStatements() {
    if (!S.user) return Promise.resolve([]);
    return K.db.byIndex('statements', 'user', S.user.id).then(function (list) {
      return (list || []).sort(function (a, b) { return (b.updatedAt || 0) - (a.updatedAt || 0); });
    });
  }
  function getStatement(id) {
    return K.db.get('statements', id).then(function (st) {
      if (!st || !S.user || st.userId !== S.user.id) throw new Error('الكشف غير موجود');
      st.columns = st.columns || [];
      st.rows = st.rows || [];
      st.pages = st.pages || [];
      st.notes = st.notes || [];
      return st;
    });
  }
  function saveStatement(st) {
    st.updatedAt = Date.now();
    try { st.stats = an.stats(st); } catch (e) { st.stats = st.stats || {}; }
    return K.db.put('statements', st).then(function () { return st; });
  }
  function newStatement(title) {
    var d = new Date();
    return {
      id: K.uid(), userId: S.user.id,
      title: title || ('كشف ' + K.fmtDay(d)),
      createdAt: Date.now(), updatedAt: Date.now(),
      columns: [], rows: [], pages: [], notes: [],
      exportedAt: null, imagesDeleted: false, stats: { rows: 0, dups: 0, bad: 0, unclear: 0 }
    };
  }
  function deleteStatement(st) {
    return K.db.delByIndex('images', 'statement', st.id).then(function () { return K.db.del('statements', st.id); });
  }
  function deleteImages(st) {
    return K.db.delByIndex('images', 'statement', st.id).then(function () {
      st.imagesDeleted = true;
      st.pages.forEach(function (p) { p.imageGone = true; });
      return saveStatement(st);
    });
  }

  /** حذف تلقائي للصور القديمة بعد قراءتها (خصوصية) */
  function purgeOldImages() {
    var hours = Number(K.pref.get('imgTTL', 24)) || 24;
    var limit = Date.now() - hours * 3600 * 1000;
    return listStatements().then(function (list) {
      var jobs = list.map(function (st) {
        var pages = (st.pages || []).filter(function (p) { return !p.imageGone && p.status === 'done' && (p.doneAt || p.at || 0) < limit; });
        if (!pages.length) return null;
        return Promise.all(pages.map(function (p) { return K.db.del('images', p.id); })).then(function () {
          pages.forEach(function (p) { p.imageGone = true; });
          if (st.pages.every(function (p) { return p.imageGone; })) st.imagesDeleted = true;
          return K.db.put('statements', st);
        });
      });
      return Promise.all(jobs);
    }).catch(function () { /* غير مهم */ });
  }

  // ================================================================
  // اختيار الصور (كاميرا / معرض)
  // ================================================================
  var camInput = el('input', { type: 'file', accept: 'image/*', capture: 'environment', hidden: true });
  var galInput = el('input', { type: 'file', accept: 'image/*', multiple: true, hidden: true });
  document.body.appendChild(camInput);
  document.body.appendChild(galInput);
  var pickHandler = null;
  function onPicked(e) {
    var files = Array.prototype.slice.call(e.target.files || []);
    e.target.value = '';
    if (files.length && pickHandler) pickHandler(files, e.target === camInput ? 'camera' : 'gallery');
  }
  camInput.addEventListener('change', onPicked);
  galInput.addEventListener('change', onPicked);
  function pick(mode, handler) {
    if (K.nat && K.nat.on) {
      (mode === 'camera' ? K.nat.takePhoto() : K.nat.pickPhotos(40)).then(function (files) {
        if (files && files.length) handler(files, mode);
      }, function (e) { if (!K.nat.isCancel(e)) K.toast((e && e.message) || 'ما قدرنا نفتح الكاميرا', 'error'); });
      return;
    }
    pickHandler = handler;
    (mode === 'camera' ? camInput : galInput).click();
  }

  /** يبدأ كشف جديد مباشرة من الكاميرا أو المعرض */
  function startNew(mode) {
    if (!S.user) return;
    pick(mode, function (files, src) {
      var st = newStatement();
      saveStatement(st).then(function () {
        return addPages(st, files, src === 'camera' && files.length === 1);
      }).then(function (added) {
        if (!added) return deleteStatement(st);
        K.go('/s/' + st.id);
      }).catch(function (err) { K.toast(err.message, 'error'); });
    });
  }

  /** يضيف صور كصفحات للكشف (مع محرر القص للصورة الوحيدة من الكاميرا) */
  function addPages(st, files, withEditor) {
    var overlay = null;
    if (files.length > 1) {
      overlay = K.sheet({ title: 'تجهيز الصور', body: el('div', { class: 'stack' }, el('p', { class: 'muted', text: 'عم نجهّز ' + files.length + ' صور ونضغطها لتناسب النت…' }), el('div', { class: 'progress' }, el('span', { style: { width: '10%' } }))), noClose: true });
    }
    var added = 0;
    var chain = Promise.resolve();
    files.slice(0, 40).forEach(function (file, i) {
      chain = chain.then(function () {
        return K.img.load(file).then(function (c) {
          if (withEditor) return K.img.edit(c, { title: 'صفحة ' + (st.pages.length + 1) });
          return c;
        }).then(function (c) {
          if (!c) return null;
          return Promise.all([K.img.compress(c), K.img.thumb(c)]).then(function (res) {
            var page = { id: K.uid(), n: st.pages.length + 1, status: 'pending', w: res[0].w, h: res[0].h, rows: 0, error: '', at: Date.now() };
            return K.db.put('images', { id: page.id, statementId: st.id, blob: res[0].blob, thumb: res[1], w: res[0].w, h: res[0].h, at: Date.now() }).then(function () {
              st.pages.push(page);
              st.imagesDeleted = false;
              added++;
            });
          });
        }).catch(function (err) { K.toast((err && err.message) || 'تعذّر فتح صورة', 'error'); }).then(function () {
          if (overlay) { var bar = overlay.body.querySelector('.progress span'); if (bar) bar.style.width = Math.round(((i + 1) / files.length) * 100) + '%'; }
        });
      });
    });
    return chain.then(function () {
      if (overlay) overlay.close();
      if (files.length > 40) K.toast('الحد 40 صورة بالمرة الوحدة', 'warn');
      return saveStatement(st);
    }).then(function () {
      if (added) K.toast(added === 1 ? 'تمت إضافة الصفحة ✓' : 'تمت إضافة ' + added + ' صفحات ✓', 'success');
      return added;
    });
  }

  // ================================================================
  // الدخول والتسجيل
  // ================================================================
  function viewAuth(tab) {
    tab = tab || 'login';
    var app = S.app || {};
    if (S.setup) {
      render(el('div', { class: 'auth' }, authHero(), el('div', { class: 'auth-card stack' },
        el('div', { class: 'notice amber' }, ic('alert'), el('div', {}, el('b', { text: 'التطبيق بحاجة لإعداد أول مرة' }), null)),
        el('a', { class: 'btn btn-primary btn-block btn-lg', href: (K.remote ? K.serverUrl() + '/' : '') + 'admin.php', target: K.remote ? '_blank' : null, rel: 'noopener' }, ic('lock'), 'فتح لوحة الإدارة')), K.footer(app.footer)));
      return;
    }
    var canRegister = app.allow_register !== false;
    if (!canRegister) tab = 'login';
    var seg = el('div', { class: 'seg', role: 'tablist' });
    var body = el('div', { class: 'stack lg' });
    function setTab(t) {
      tab = t;
      K.clear(seg);
      seg.appendChild(el('button', { type: 'button', class: t === 'login' ? 'is-active' : '', on: { click: function () { setTab('login'); } } }, 'تسجيل الدخول'));
      if (canRegister) seg.appendChild(el('button', { type: 'button', class: t === 'register' ? 'is-active' : '', on: { click: function () { setTab('register'); } } }, 'حساب جديد'));
      K.clear(body).appendChild(t === 'login' ? loginForm() : registerForm());
    }
    render(el('div', { class: 'auth' }, authHero(), el('div', { class: 'auth-card stack lg' }, seg, body), K.footer(app.footer)));
    setTab(tab);
  }

  function authHero() {
    var app = S.app || {};
    return el('div', { class: 'auth-hero' }, el('div', { class: 'auth-hero-in' },
      el('img', { src: iconUrl(512), alt: '' }),
      el('h1', {}, K.wordmark(appName())),
      el('p', { text: app.tagline || 'حوّل الكشوفات المكتوبة بخط اليد لجدول إكسل خلال ثواني' }),
      el('div', { class: 'auth-feats' },
        el('div', { class: 'auth-feat' }, ic('sparkles'), 'قراءة خط اليد بالذكاء الاصطناعي'),
        el('div', { class: 'auth-feat' }, ic('idcard'), 'كشف المكررات برقم الهوية'),
        el('div', { class: 'auth-feat' }, ic('shield'), 'الصور بتنحذف بعد التصدير'))));
  }

  function phoneInput() {
    return el('div', { class: 'input-wrap' }, ic('phone'), el('input', { class: 'input ltr', type: 'tel', name: 'phone', inputmode: 'tel', autocomplete: 'tel', placeholder: '059xxxxxxx', required: true, maxlength: '20' }));
  }

  function loginForm() {
    var err = el('div', { class: 'form-error', hidden: true });
    var pw = K.passwordInput({ autocomplete: 'current-password' });
    var btn = el('button', { class: 'btn btn-primary btn-block btn-lg', type: 'submit' }, 'دخول');
    var phone = phoneInput();
    var form = el('form', { class: 'stack', novalidate: true },
      el('div', { class: 'field' }, el('label', { text: 'رقم الجوال' }), phone),
      el('div', { class: 'field' }, el('label', { text: 'كلمة السر' }), pw.node),
      err, btn,
      el('button', { class: 'btn btn-ghost btn-sm', type: 'button', on: { click: forgotPassword } }, 'نسيت كلمة السر؟'));
    form.addEventListener('submit', function (e) {
      e.preventDefault();
      err.hidden = true;
      var ph = phone.querySelector('input').value.trim();
      if (!ph || !pw.input.value) { err.textContent = 'اكتب رقم الجوال وكلمة السر'; err.hidden = false; return; }
      K.busy(btn, true);
      K.api.post('login', { phone: ph, password: pw.input.value }).then(function (r) {
        S.user = r.user;
        K.toast('أهلاً ' + firstName(r.user.name) + ' 👋', 'success');
        afterLogin();
      }).catch(function (e2) { err.textContent = e2.message; err.hidden = false; }).then(function () { K.busy(btn, false); });
    });
    return form;
  }

  function registerForm() {
    var app = S.app || {};
    var err = el('div', { class: 'form-error', hidden: true });
    var name = el('input', { class: 'input', name: 'name', autocomplete: 'name', placeholder: 'الاسم الكامل', required: true, maxlength: '60' });
    var phone = phoneInput();
    var pw = K.passwordInput({ autocomplete: 'new-password', placeholder: '6 أحرف أو أكثر' });
    var btn = el('button', { class: 'btn btn-primary btn-block btn-lg', type: 'submit' }, 'إنشاء الحساب');
    var form = el('form', { class: 'stack', novalidate: true },
      app.signup_pages > 0 ? el('div', { class: 'notice green' }, ic('gift'), el('div', { text: 'هدية التسجيل: ' + app.signup_pages + ' ' + K.pagesWord(app.signup_pages) + ' مجاناً للتجربة' })) : null,
      el('div', { class: 'field' }, el('label', { text: 'الاسم' }), el('div', { class: 'input-wrap' }, ic('user'), name)),
      el('div', { class: 'field' }, el('label', { text: 'رقم الجوال' }), phone),
      el('div', { class: 'field' }, el('label', { text: 'كلمة السر' }), pw.node),
      err, btn);
    form.addEventListener('submit', function (e) {
      e.preventDefault();
      err.hidden = true;
      K.busy(btn, true);
      K.api.post('register', { name: name.value, phone: phone.querySelector('input').value, password: pw.input.value }).then(function (r) {
        S.user = r.user;
        K.toast('تم إنشاء حسابك ✓ أهلاً فيك', 'success');
        afterLogin();
      }).catch(function (e2) { err.textContent = e2.message; err.hidden = false; }).then(function () { K.busy(btn, false); });
    });
    return form;
  }

  function forgotPassword() {
    var c = (S.app && S.app.contact) || '';
    K.sheet({
      title: 'نسيت كلمة السر؟',
      body: el('div', { class: 'stack' },
        c ? el('a', { class: 'btn btn-primary btn-block', href: waLink(c, 'السلام عليكم، نسيت كلمة السر لحسابي في ' + appName()), target: '_blank', rel: 'noopener' }, ic('msg'), 'تواصل مع الإدارة عبر واتساب') : el('p', { class: 'muted', text: 'تواصل مع الإدارة' }))
    });
  }

  function waLink(phone, text) {
    var p = K.digits(phone).replace(/\D/g, '');
    if (p.indexOf('0') === 0) p = '970' + p.slice(1);
    return 'https://wa.me/' + p + '?text=' + encodeURIComponent(text || '');
  }

  function afterLogin() {
    purgeOldImages();
    var go = new URLSearchParams(location.search).get('go');
    if (go === 'wallet') K.go('/wallet', true);
    else K.go('/', true);
  }

  // ================================================================
  // الرئيسية
  // ================================================================
  function viewHome() {
    var app = S.app || {};
    var list = el('div', { class: 'list' }, skeletonList(2));
    var tiles = el('div', { class: 'stat-tiles' });
    var body = el('div', {},
      app.announcement ? el('div', { class: 'announce' }, ic('info'), el('div', { text: app.announcement })) : null,
      app.maintenance ? el('div', { class: 'notice amber', style: { 'margin-bottom': '14px' } }, ic('alert'), el('div', { text: 'التطبيق بصيانة حالياً — القراءة متوقفة مؤقتاً.' })) : null,
      !app.ai_ready ? el('div', { class: 'notice amber', style: { 'margin-bottom': '14px' } }, ic('alert'), el('div', { text: 'خدمة القراءة قيد التجهيز من الإدارة. بتقدر تجهز صورك وتقرأها لما تتفعل.' })) : null,
      el('section', { class: 'hero' },
        el('div', { class: 'hero-scan-line' }),
        el('div', { class: 'greet', text: 'أهلاً، ' + firstName(S.user.name) }),
        el('h1', { text: 'حوّل كشفك لإكسل بثواني' }),

        el('div', { class: 'hero-actions' },
          el('button', { class: 'btn btn-primary', type: 'button', on: { click: function () { startNew('camera'); } } }, ic('camera'), 'صوّر كشف'),
          el('button', { class: 'btn btn-glass', type: 'button', on: { click: function () { startNew('gallery'); } } }, ic('image'), 'من المعرض'))),
      tiles,
      installCard(),
      el('div', { class: 'section-title' }, el('h2', { text: 'آخر الكشوفات' }), el('a', { href: '#/statements', class: 'small', text: 'عرض الكل' })),
      list);
    render(layout({ nav: 'home', body: body }));

    listStatements().then(function (sts) {
      var rows = 0, dups = 0;
      sts.forEach(function (s) { rows += (s.stats && s.stats.rows) || 0; dups += (s.stats && s.stats.dups) || 0; });
      K.clear(tiles);
      tiles.appendChild(tile('files', K.num(sts.length), 'كشوفات'));
      tiles.appendChild(tile('sheet', K.num(rows), 'صف مقروء'));
      tiles.appendChild(tile('copy', K.num(dups), 'مكرر تم كشفه', 'amber'));
      K.clear(list);
      if (!sts.length) {
        list.appendChild(el('div', { class: 'empty' }, ic('scan'), el('h3', { text: 'ما في كشوفات لسا' })));
        return;
      }
      sts.slice(0, 5).forEach(function (st) { list.appendChild(statementItem(st)); });
    }).catch(function (e) { K.clear(list).appendChild(el('div', { class: 'notice red' }, ic('alert'), el('div', { text: e.message }))); });
  }

  function tile(icon, v, k, cls) { return el('div', { class: 'tile' + (cls ? ' ' + cls : '') }, ic(icon), el('div', { class: 'v', text: v }), el('div', { class: 'k', text: k })); }
  function step(n, t, s) { return el('div', { class: 'step' }, el('div', { class: 'n', text: String(n) }), el('div', {}, el('b', { text: t }), el('span', { text: s }))); }
  function skeletonList(n) { var a = []; for (var i = 0; i < n; i++) a.push(el('div', { class: 'skeleton', style: { height: '78px' } })); return a; }

  function installCard() {
    if (!S.installEvt || K.pref.get('hideInstall', false)) return null;
    var card = el('div', { class: 'card row', style: { 'margin-top': '14px' } },
      el('div', { class: 'thumb', style: { width: '46px', height: '46px', 'border-radius': '14px', overflow: 'hidden', flex: 'none' } }, el('img', { src: iconUrl(192), alt: '' })),
      el('div', { class: 'grow' }, el('b', { text: 'ثبّت ' + appName() + ' على جوالك' }), el('div', { class: 'small muted', text: 'بيفتح أسرع وبيشتغل زي التطبيقات العادية' })),
      el('button', { class: 'btn btn-soft btn-sm', type: 'button', on: { click: doInstall } }, 'تثبيت'),
      el('button', { class: 'btn btn-icon btn-ghost sm', type: 'button', 'aria-label': 'إخفاء', on: { click: function () { K.pref.set('hideInstall', true); card.remove(); } } }, ic('x')));
    return card;
  }
  function doInstall() {
    if (!S.installEvt) { K.toast('من قائمة المتصفح اختر "إضافة إلى الشاشة الرئيسية"', 'info'); return; }
    S.installEvt.prompt();
    S.installEvt.userChoice.then(function () { S.installEvt = null; });
  }
  window.addEventListener('beforeinstallprompt', function (e) { e.preventDefault(); S.installEvt = e; });

  function statementItem(st, opts) {
    opts = opts || {};
    var s = st.stats || {};
    var thumbBox = el('div', { class: 'thumb' }, ic('sheet'));
    var p0 = (st.pages || []).filter(function (p) { return !p.imageGone; })[0];
    if (p0) {
      K.db.get('images', p0.id).then(function (im) {
        if (im && im.thumb) K.clear(thumbBox).appendChild(el('img', { src: objUrl(im.thumb), alt: '' }));
      }).catch(function () { /* */ });
    }
    var badges = el('div', { class: 'badges' },
      el('span', { class: 'badge' }, K.num(s.rows || 0) + ' صف'),
      s.dups ? el('span', { class: 'badge amber' }, ic('copy'), K.num(s.dups) + ' مكرر') : null,
      s.bad ? el('span', { class: 'badge red' }, ic('alert'), K.num(s.bad) + ' هوية غلط') : null,
      st.exportedAt ? el('span', { class: 'badge green' }, ic('check'), 'تم التصدير') : null,
      (st.pages || []).some(function (p) { return p.status !== 'done'; }) ? el('span', { class: 'badge blue' }, ic('clock'), 'صفحات بانتظار القراءة') : null);
    var node = el(opts.select ? 'label' : 'button', { class: 'item', type: opts.select ? null : 'button' },
      opts.select ? el('input', { type: 'checkbox', value: st.id, style: { width: '20px', height: '20px', 'accent-color': 'var(--primary)' } }) : null,
      thumbBox,
      el('div', { class: 'grow' }, el('div', { class: 't', text: st.title }), el('div', { class: 's', text: K.ago(st.updatedAt) + ' • ' + (st.pages || []).length + ' ' + K.pagesWord((st.pages || []).length) }), badges),
      opts.select ? null : ic('chevl', 'chev'));
    if (!opts.select) node.addEventListener('click', function () { K.go(st.rows && st.rows.length ? '/s/' + st.id + '/review' : '/s/' + st.id); });
    return node;
  }

  // ================================================================
  // قائمة الكشوفات + الدمج
  // ================================================================
  function viewStatements() {
    var list = el('div', { class: 'list' }, skeletonList(3));
    var search = el('input', { class: 'input', type: 'search', placeholder: 'ابحث باسم الكشف…' });
    var all = [];
    function draw() {
      var q = search.value.trim();
      K.clear(list);
      var items = all.filter(function (s) { return !q || s.title.indexOf(q) >= 0; });
      if (!items.length) {
        list.appendChild(el('div', { class: 'empty' }, ic('files'), el('h3', { text: q ? 'ما في نتائج' : 'ما عندك كشوفات لسا' }), el('p', { text: q ? 'جرب كلمة ثانية' : 'اضغط زر التصوير تحت وابدأ أول كشف' })));
        return;
      }
      items.forEach(function (st) { list.appendChild(statementItem(st)); });
    }
    search.addEventListener('input', K.debounce(draw, 150));
    var body = el('div', {},
      el('div', { class: 'page-head' }, el('div', { class: 'grow' }, el('h1', { text: 'كشوفاتي' }), el('div', { class: 'sub', text: 'محفوظة على جهازك فقط' })),
        el('button', { class: 'btn btn-soft btn-sm', type: 'button', on: { click: mergeDialog } }, ic('layers'), 'دمج كشوفات')),
      el('div', { class: 'input-wrap', style: { 'margin-bottom': '14px' } }, ic('search'), search),
      list);
    render(layout({ nav: 'list', body: body }));
    listStatements().then(function (sts) { all = sts; draw(); }).catch(function (e) { K.toast(e.message, 'error'); });
  }

  function mergeDialog(preId) {
    listStatements().then(function (sts) {
      var ready = sts.filter(function (s) { return s.rows && s.rows.length; });
      if (ready.length < 2) { K.toast('لازم يكون عندك كشفين مقروءين على الأقل', 'warn'); return; }
      var box = el('div', { class: 'list' });
      ready.forEach(function (st) {
        var it = statementItem(st, { select: true });
        if (st.id === preId) { var cb = it.querySelector('input'); if (cb) cb.checked = true; }
        box.appendChild(it);
      });
      var title = el('input', { class: 'input', value: 'كشف مدموج ' + K.fmtDay(new Date()) });
      K.sheet({
        title: 'دمج كشوفات',
        body: el('div', { class: 'stack' },
          el('div', { class: 'field' }, el('label', { text: 'اسم الكشف الجديد' }), title),
          box),
        actions: [{ label: 'إلغاء' }, {
          label: 'دمج', primary: true, icon: 'layers', onClick: function () {
            var ids = Array.prototype.slice.call(box.querySelectorAll('input:checked')).map(function (i) { return i.value; });
            if (ids.length < 2) { K.toast('اختار كشفين على الأقل', 'warn'); return true; }
            var chosen = ready.filter(function (s) { return ids.indexOf(s.id) >= 0; });
            var merged = an.merge(chosen, title.value.trim() || 'كشف مدموج');
            var st = newStatement(merged.title);
            st.columns = merged.columns;
            st.rows = merged.rows;
            st.merged = ids;
            return saveStatement(st).then(function () { K.toast('تم الدمج ✓', 'success'); K.go('/s/' + st.id + '/review'); });
          }
        }]
      });
    });
  }

  // ================================================================
  // صفحات الكشف + القراءة بالذكاء الاصطناعي
  // ================================================================
  function viewStatement(p) {
    getStatement(p.id).then(function (st) { drawStatement(st); }).catch(function (e) { K.toast(e.message, 'error'); K.go('/statements', true); });
  }

  function drawStatement(st) {
    var app = S.app || {};
    var models = app.models || [];
    var modelPref = K.pref.get('model', '');
    var model = models.filter(function (m) { return m.id === modelPref; })[0] || models.filter(function (m) { return m.default; })[0] || models[0] || null;

    var grid = el('div', { class: 'pages-grid' });
    var pending = st.pages.filter(function (pg) { return pg.status !== 'done' && !pg.imageGone; });
    var cost = model ? model.page_cost : 1;

    st.pages.forEach(function (pg, i) {
      var badge;
      if (pg.status === 'done') badge = el('span', { class: 'badge green' }, ic('check'), pg.rows + ' صف');
      else if (pg.status === 'error') badge = el('span', { class: 'badge red' }, ic('alert'), 'فشلت');
      else badge = el('span', { class: 'badge' }, 'جاهزة للقراءة');
      if (pg.imageGone) {
        grid.appendChild(el('div', { class: 'page-thumb deleted' }, el('span', { class: 'num', text: String(i + 1) }), el('div', {}, ic('shield'), el('div', { text: 'الصورة انحذفت للخصوصية' })), el('div', { class: 'st' }, badge)));
        return;
      }
      var img = el('img', { alt: 'صفحة ' + (i + 1) });
      K.db.get('images', pg.id).then(function (im) { if (im) img.src = objUrl(im.thumb || im.blob); });
      var btn = el('button', { class: 'page-thumb', type: 'button', 'aria-label': 'صفحة ' + (i + 1) }, img, el('span', { class: 'num', text: String(i + 1) }), el('div', { class: 'st' }, badge));
      btn.addEventListener('click', function () { pageMenu(st, pg, i); });
      grid.appendChild(btn);
    });
    grid.appendChild(el('button', { class: 'page-thumb add', type: 'button', on: { click: function () { pick('camera', function (f, src) { addPages(st, f, src === 'camera' && f.length === 1).then(function () { drawStatement(st); }); }); } } }, ic('camera'), 'صوّر صفحة'));
    grid.appendChild(el('button', { class: 'page-thumb add', type: 'button', on: { click: function () { pick('gallery', function (f) { addPages(st, f, false).then(function () { drawStatement(st); }); }); } } }, ic('image'), 'من المعرض'));

    var modelBox = null;
    if (models.length > 1 && pending.length) {
      var seg = el('div', { class: 'stack' });
      models.forEach(function (m) {
        var r = el('input', { type: 'radio', name: 'model', value: m.id, checked: model && m.id === model.id, style: { width: '20px', height: '20px', 'accent-color': 'var(--primary)' } });
        r.addEventListener('change', function () { K.pref.set('model', m.id); drawStatement(st); });
        seg.appendChild(el('label', { class: 'item', style: { cursor: 'pointer' } }, r,
          el('div', { class: 'grow' }, el('div', { class: 't', text: m.label }), el('div', { class: 's', text: m.description || '' })),
          el('span', { class: 'badge' + (m.default ? ' green' : '') }, m.page_cost + ' ' + K.pagesWord(m.page_cost) + ' / صفحة')));
      });
      modelBox = el('div', {}, el('div', { class: 'section-title' }, el('h2', { text: 'دقة القراءة' })), seg);
    }

    var info = [];
    if (st.imagesDeleted && !pending.length) info.push(el('div', { class: 'notice green' }, ic('shield'), el('div', { class: 'small', text: 'تم حذف صور هذا الكشف من جهازك حفاظاً على الخصوصية. البيانات المقروءة محفوظة.' })));
    if ((st.notes || []).length) {
      info.push(el('div', { class: 'notice' }, ic('info'), el('div', { class: 'small' }, el('b', { text: 'ملاحظات القراءة' }), st.notes.map(function (n) { return el('div', { text: '• ' + n }); }))));
    }

    var cta = null;
    if (pending.length) {
      var need = pending.length * cost;
      var bal = S.user.pages;
      var readBtn = el('button', { class: 'btn btn-primary btn-lg btn-block', type: 'button' }, ic('sparkles'), 'اقرأ ' + (pending.length === 1 ? 'الصفحة' : pending.length + ' صفحات') + ' (' + need + ' من رصيدك)');
      readBtn.addEventListener('click', function () {
        if (!S.app.ai_ready) { K.toast('خدمة القراءة غير مفعّلة بعد من الإدارة', 'warn'); return; }
        if (S.user.role !== 'admin' && bal < cost) { noBalance(); return; }
        processPages(st, pending, model);
      });
      cta = el('div', { class: 'sticky-cta' }, el('div', { class: 'in' }, readBtn));
    }

    var more = el('button', { class: 'btn btn-icon btn-ghost', type: 'button', 'aria-label': 'خيارات' }, ic('more'));
    more.addEventListener('click', function () { statementMenu(st); });

    var body = el('div', { class: 'stack lg' },
      el('div', { class: 'review-head' },
        el('div', { class: 'grow' },
          el('button', { class: 'title-btn', type: 'button', on: { click: function () { renameStatement(st).then(function () { drawStatement(st); }); } } }, st.title, ic('edit')),
          el('div', { class: 'muted small', text: st.pages.length + ' ' + K.pagesWord(st.pages.length) + ' • ' + st.rows.filter(function (r) { return !r.deleted; }).length + ' صف مقروء' }))),
      info.length ? el('div', { class: 'stack' }, info) : null,
      st.rows.length ? el('button', { class: 'btn btn-soft btn-block btn-lg', type: 'button', on: { click: function () { K.go('/s/' + st.id + '/review'); } } }, ic('sheet'), 'عرض الجدول ومراجعة البيانات') : null,
      el('div', {}, el('div', { class: 'section-title' }, el('h2', { text: 'الصفحات' }), null), grid),
      modelBox);
    render(layout({ back: st.rows.length ? '/s/' + st.id + '/review' : '/', title: 'الكشف', actions: more, body: body, cta: cta, nav: 'list' }));
  }

  function noBalance() {
    var app = S.app || {};
    K.sheet({
      title: 'رصيدك ما بكفي',
      body: el('div', { class: 'stack' },
        el('p', { class: 'muted', text: 'اشحن رصيدك بكود من المحفظة، وكمّل قراءة الكشف من نفس المكان.' }),
        app.contact ? el('a', { class: 'btn btn-soft btn-block', href: waLink(app.contact, 'السلام عليكم، بدي أشتري كود صفحات لتطبيق ' + appName()), target: '_blank', rel: 'noopener' }, ic('msg'), 'اطلب كود عبر واتساب') : null),
      actions: [{ label: 'لاحقاً' }, { label: 'الذهاب للمحفظة', primary: true, onClick: function () { K.go('/wallet'); } }]
    });
  }

  function pageMenu(st, pg, i) {
    var acts = el('div', { class: 'stack' });
    var sh = K.sheet({ title: 'صفحة ' + (i + 1), body: acts });
    function act(icon, label, fn, danger) {
      acts.appendChild(el('button', { class: 'btn btn-block ' + (danger ? 'btn-danger' : ''), type: 'button', on: { click: function () { sh.close(); fn(); } } }, ic(icon), label));
    }
    act('crop', 'تعديل (قص / تدوير / تحسين)', function () {
      K.db.get('images', pg.id).then(function (im) {
        if (!im) throw new Error('الصورة غير موجودة');
        return K.img.blobToCanvas(im.blob).then(function (c) { return K.img.edit(c, { title: 'صفحة ' + (i + 1) }); }).then(function (c) {
          if (!c) return;
          return Promise.all([K.img.compress(c), K.img.thumb(c)]).then(function (res) {
            im.blob = res[0].blob; im.thumb = res[1]; im.w = res[0].w; im.h = res[0].h;
            pg.w = res[0].w; pg.h = res[0].h;
            if (pg.status === 'done') pg.status = 'pending';
            pg.error = '';
            return K.db.put('images', im).then(function () { return saveStatement(st); });
          });
        });
      }).then(function () { drawStatement(st); }).catch(function (e) { K.toast(e.message, 'error'); });
    });
    if (pg.status === 'done') {
      act('refresh', 'إعادة القراءة', function () {
        pg.status = 'pending';
        saveStatement(st).then(function () { drawStatement(st); K.toast('إعادة قراءة نفس الصورة مجانية خلال 24 ساعة', 'info'); });
      });
    }
    if (pg.status === 'error' && pg.error) acts.insertBefore(el('div', { class: 'notice red' }, ic('alert'), el('div', { class: 'small', text: pg.error })), acts.firstChild);
    act('trash', 'حذف الصفحة', function () {
      K.confirm(pg.status === 'done' ? 'رح تنحذف الصفحة والصفوف المقروءة منها. متأكد؟' : 'حذف هذه الصفحة؟', { danger: true, ok: 'حذف' }).then(function (ok) {
        if (!ok) return;
        st.rows = st.rows.filter(function (r) { return r.page !== pg.id; });
        st.pages = st.pages.filter(function (x) { return x.id !== pg.id; });
        st.pages.forEach(function (x, k) { x.n = k + 1; });
        K.db.del('images', pg.id).then(function () { return saveStatement(st); }).then(function () { drawStatement(st); });
      });
    }, true);
  }

  function statementMenu(st) {
    var acts = el('div', { class: 'stack' });
    var sh = K.sheet({ title: st.title, body: acts });
    function act(icon, label, fn, danger) {
      acts.appendChild(el('button', { class: 'btn btn-block ' + (danger ? 'btn-danger' : ''), type: 'button', on: { click: function () { sh.close(); fn(); } } }, ic(icon), label));
    }
    act('edit', 'إعادة تسمية', function () { renameStatement(st).then(function () { K.resolve(); }); });
    if (st.rows.length) act('download', 'تصدير', function () { exportDialog(st); });
    if (!st.imagesDeleted && st.pages.length) act('shield', 'حذف الصور الآن (الإبقاء على البيانات)', function () {
      K.confirm('رح تنحذف صور الصفحات من جهازك. الصفحات اللي ما انقرأت لسا رح تضيع. متأكد؟', { danger: true, ok: 'حذف الصور' }).then(function (ok) {
        if (ok) deleteImages(st).then(function () { K.toast('تم حذف الصور ✓', 'success'); K.resolve(); });
      });
    });
    act('trash', 'حذف الكشف نهائياً', function () {
      K.confirm('رح ينحذف الكشف وكل صوره وبياناته من جهازك. ما في تراجع. متأكد؟', { danger: true, ok: 'حذف' }).then(function (ok) {
        if (ok) deleteStatement(st).then(function () { K.toast('تم حذف الكشف', 'success'); K.go('/statements'); });
      });
    }, true);
  }

  function renameStatement(st) {
    return K.prompt({ title: 'اسم الكشف', value: st.title, ok: 'حفظ' }).then(function (v) {
      if (v === null) return;
      v = v.trim();
      if (!v) return;
      st.title = v.slice(0, 80);
      return saveStatement(st);
    });
  }

  /** القراءة: صفحة صفحة مع شاشة الماسح الأنيقة */
  function processPages(st, pages, model) {
    var stopped = false;
    var total = pages.length;
    var imgEl = el('img', { alt: '' });
    var title = el('h3', {});
    var stage = el('div', { class: 'stage' });
    var bar = el('span', {});
    var metaL = el('span', {}), metaR = el('span', {});
    var stopBtn = el('button', { class: 'btn btn-ghost btn-sm', type: 'button' }, 'إيقاف بعد هالصفحة');
    var ui = el('div', { class: 'scanner', role: 'dialog', 'aria-live': 'polite' },
      el('div', { class: 'scan-frame' }, imgEl, el('div', { class: 'scan-dots' }), el('div', { class: 'scan-line' })),
      el('div', { class: 'scan-info' }, title, el('div', { class: 'stage' }, stage), el('div', { class: 'progress' }, bar), el('div', { class: 'meta' }, metaR, metaL)),
      stopBtn);
    stopBtn.addEventListener('click', function () { stopped = true; stopBtn.disabled = true; stopBtn.textContent = 'رح نوقف بعد هالصفحة…'; });
    document.body.appendChild(ui);
    document.body.classList.add('no-scroll');
    var wakeLock = null;
    try { if (navigator.wakeLock) navigator.wakeLock.request('screen').then(function (w) { wakeLock = w; }, function () { }); } catch (e) { /* */ }

    var tips = ['الذكاء الاصطناعي عم يقرأ خط اليد…', 'عم نحوّل الأرقام لأرقام إنجليزية…', 'عم نرتّب الأعمدة والصفوف…', 'عم نعلّم الخانات غير الواضحة…', 'قربنا نخلص…'];
    var tipTimer = null;
    var done = 0, rowsAdded = 0, charged = 0, errors = 0, lastErr = null;

    function setProgress(frac) {
      bar.style.width = Math.min(100, Math.round(((done + frac) / total) * 100)) + '%';
    }
    function hintColumns() { return JSON.stringify(st.columns.map(function (c) { return { label: c.label, kind: c.kind }; })); }

    function one(pg, i) {
      title.textContent = total > 1 ? 'بقرأ الصفحة ' + (i + 1) + ' من ' + total : 'بقرأ الكشف';
      metaR.textContent = 'صفحة ' + pg.n;
      metaL.textContent = rowsAdded ? rowsAdded + ' صف لحد هلأ' : '';
      stage.textContent = 'عم نرفع الصورة…';
      setProgress(0.02);
      return K.db.get('images', pg.id).then(function (im) {
        if (!im) throw new Error('صورة الصفحة غير موجودة على الجهاز');
        imgEl.src = objUrl(im.blob);
        var fd = new FormData();
        fd.append('image', im.blob, 'page.jpg');
        fd.append('columns', hintColumns());
        if (model) fd.append('model', model.id);
        var t0 = 0;
        return K.api.upload('scan', fd, function (f, phase) {
          if (phase === 'up') { stage.textContent = 'عم نرفع الصورة… ' + Math.round(f * 100) + '%'; setProgress(f * 0.25); }
          else if (!tipTimer) {
            stage.textContent = tips[0];
            var k = 0, start = Date.now();
            tipTimer = setInterval(function () {
              k = (k + 1) % tips.length;
              stage.textContent = tips[k];
              var el2 = (Date.now() - start) / 1000;
              setProgress(0.25 + 0.7 * (1 - Math.exp(-el2 / 25)));
            }, 2600);
            void t0;
          }
        });
      }).then(function (r) {
        clearInterval(tipTimer); tipTimer = null;
        var res = r.result;
        // إزالة صفوف القراءة السابقة لنفس الصفحة
        var insertAt = -1;
        st.rows = st.rows.filter(function (row, idx) {
          if (row.page === pg.id) { if (insertAt < 0) insertAt = idx; return false; }
          return true;
        });
        var colIds = an.mapColumns(st, res.columns);
        var colMap = {};
        st.columns.forEach(function (c) { colMap[c.id] = c; });
        var newRows = res.rows.map(function (row) {
          var cells = {};
          colIds.forEach(function (cid, ci) { cells[cid] = normCell(colMap[cid], row.cells[ci] || ''); });
          return { id: K.uid(), page: pg.id, cells: cells, unclear: (row.unclear || []).map(function (u) { return colIds[u]; }).filter(Boolean), y: row.y_bottom > row.y_top ? [row.y_top, row.y_bottom] : null, fresh: true };
        });
        if (insertAt < 0) {
          // نضيفها بعد آخر صف من صفحة أقدم
          var order = {};
          st.pages.forEach(function (x, k) { order[x.id] = k; });
          insertAt = st.rows.length;
          for (var k = 0; k < st.rows.length; k++) {
            if ((order[st.rows[k].page] || 0) > (order[pg.id] || 0)) { insertAt = k; break; }
          }
        }
        Array.prototype.splice.apply(st.rows, [insertAt, 0].concat(newRows));
        if (res.title && /^كشف \d/.test(st.title) && !st.renamed) { st.title = res.title.slice(0, 80); st.renamed = true; }
        if (res.notes) st.notes.push('صفحة ' + pg.n + ': ' + res.notes);
        pg.status = 'done'; pg.rows = newRows.length; pg.error = ''; pg.doneAt = Date.now(); pg.model = model ? model.id : '';
        if (r.size) { pg.w = r.size.w; pg.h = r.size.h; }
        rowsAdded += newRows.length;
        charged += r.charged || 0;
        setBalance(r.pages);
        done++;
        setProgress(0);
        stage.textContent = '✓ ' + newRows.length + ' صف من صفحة ' + pg.n;
        return saveStatement(st);
      }, function (err) {
        clearInterval(tipTimer); tipTimer = null;
        pg.status = 'error';
        pg.error = err.message;
        errors++;
        lastErr = err;
        done++;
        return saveStatement(st).then(function () {
          if (err.status === 402 || err.status === 0 || err.status === 503 || err.status === 401) stopped = true;
        });
      });
    }

    var chain = Promise.resolve();
    pages.forEach(function (pg, i) {
      chain = chain.then(function () { if (!stopped) return one(pg, i); });
    });
    chain.then(function () {
      clearInterval(tipTimer);
      if (wakeLock) { try { wakeLock.release(); } catch (e) { /* */ } }
      ui.remove();
      document.body.classList.remove('no-scroll');
      if (lastErr && lastErr.status === 402) { drawStatement(st); noBalance(); return; }
      if (errors && !rowsAdded) { K.toast(lastErr ? lastErr.message : 'فشلت القراءة', 'error', 7000); drawStatement(st); return; }
      if (errors) K.toast('انقرأت ' + (done - errors) + ' صفحات وفشلت ' + errors + ' — بتقدر تعيدها', 'warn', 6000);
      else K.toast('تمت القراءة ✓ ' + rowsAdded + ' صف' + (charged ? ' — انخصم ' + charged + ' ' + K.pagesWord(charged) : ''), 'success', 4500);
      K.go('/s/' + st.id + '/review');
    });
  }

  // ================================================================
  // محرر الكشف المتكامل: تعديل مباشر، فرز، تحديد متعدد، نسخ ولصق، تراجع
  // ================================================================
  var KIND_OPTS = [['name', 'اسم'], ['national_id', 'رقم هوية'], ['phone', 'رقم جوال'], ['number', 'رقم'], ['serial', 'تسلسل'], ['date', 'تاريخ'], ['text', 'نص']];
  var collator = (function () { try { return new Intl.Collator('ar', { numeric: true, sensitivity: 'base' }); } catch (e) { return null; } })();
  function isLtrKind(k) { return k === 'national_id' || k === 'phone' || k === 'number' || k === 'serial'; }
  function normCell(c, v) {
    v = String(v == null ? '' : v).replace(/[\u0000-\u001F]/g, ' ').trim();
    if (c && (c.kind === 'national_id' || c.kind === 'phone')) v = K.digits(v).replace(/\D/g, '');
    else if (c && (c.kind === 'number' || c.kind === 'serial')) v = K.digits(v);
    return v.slice(0, 300);
  }

  // ---------- سجل التراجع والإعادة ----------
  function hist(st) { var k = 'h_' + st.id; return S.cache[k] || (S.cache[k] = { undo: [], redo: [] }); }
  function snapshot(st) { return JSON.stringify({ c: st.columns, r: st.rows }); }
  function pushUndo(st) {
    var h = hist(st);
    h.undo.push(snapshot(st));
    if (h.undo.length > 80) h.undo.shift();
    h.redo.length = 0;
  }
  function restoreSnap(st, json) { var o = JSON.parse(json); st.columns = o.c; st.rows = o.r; }
  function undoEdit(st) { var h = hist(st); if (!h.undo.length) return false; h.redo.push(snapshot(st)); restoreSnap(st, h.undo.pop()); return true; }
  function redoEdit(st) { var h = hist(st); if (!h.redo.length) return false; h.undo.push(snapshot(st)); restoreSnap(st, h.redo.pop()); return true; }

  // ---------- النسخ واللصق (متوافق مع Excel) ----------
  function toTsv(st, rows, withHeader) {
    function cell(v) { v = String(v == null ? '' : v); return /[\t\n"]/.test(v) ? '"' + v.replace(/"/g, '""') + '"' : v; }
    var lines = [];
    if (withHeader) lines.push(st.columns.map(function (c) { return cell(c.label); }).join('\t'));
    rows.forEach(function (r) { lines.push(st.columns.map(function (c) { return cell(r.cells[c.id]); }).join('\t')); });
    return lines.join('\n');
  }
  function parseClip(text) {
    text = String(text || '').replace(/\r\n?/g, '\n');
    var rows = [], row = [], cur = '', q = false;
    var tabbed = text.indexOf('\t') >= 0;
    var sep = tabbed ? '\t' : (/,/.test(text) && !/[؀-ۿ]\s*,\s*[؀-ۿ]/.test(text.split('\n')[0]) ? ',' : '\t');
    for (var i = 0; i < text.length; i++) {
      var ch = text[i];
      if (q) {
        if (ch === '"' && text[i + 1] === '"') { cur += '"'; i++; }
        else if (ch === '"') q = false;
        else cur += ch;
      } else if (ch === '"' && cur === '') q = true;
      else if (ch === sep) { row.push(cur); cur = ''; }
      else if (ch === '\n') { row.push(cur); rows.push(row); row = []; cur = ''; }
      else cur += ch;
    }
    if (cur !== '' || row.length) { row.push(cur); rows.push(row); }
    return rows.filter(function (r) { return r.some(function (c) { return String(c).trim() !== ''; }); });
  }

  function viewReview(p) {
    Promise.all([getStatement(p.id), listStatements()]).then(function (res) {
      drawReview(res[0], res[1]);
    }).catch(function (e) { K.toast(e.message, 'error'); K.go('/statements', true); });
  }

  function drawReview(st, others) {
    var state = S.cache['rv_' + st.id] || (S.cache['rv_' + st.id] = { filter: 'all', q: '', limit: 200, cross: false, select: false, sel: {}, sortCol: null, sortDir: 1 });
    var A = null;
    var editing = null;
    var visible = [];
    function rowById(id) { for (var i = 0; i < st.rows.length; i++) if (st.rows[i].id === id) return st.rows[i]; return null; }
    function colById(id) { for (var i = 0; i < st.columns.length; i++) if (st.columns[i].id === id) return st.columns[i]; return null; }
    function commitAll(msg) {
      return saveStatement(st).then(function () { refresh(); if (msg) K.toast(msg, 'success'); });
    }

    // ---------- العناصر الثابتة ----------
    var titleBtn = el('button', { class: 'title-btn', type: 'button', on: { click: function () { renameStatement(st).then(function () { K.clear(titleBtn).appendChild(document.createTextNode(st.title)); titleBtn.appendChild(ic('edit')); }); } } }, st.title, ic('edit'));
    var metaLine = el('div', { class: 'muted small' });
    function mkTile(icon, k, cls) { var v = el('div', { class: 'v', text: '0' }); var t = el('div', { class: 'tile ' + (cls || '') }, ic(icon), v, el('div', { class: 'k', text: k })); t._v = v; return t; }
    var tDup = mkTile('copy', 'مكرر', 'amber'), tBad = mkTile('idcard', 'هوية غلط', 'red'), tMiss = mkTile('alert', 'ناقص', 'blue'), tUnc = mkTile('eye', 'مراجعة', 'yellow');
    var dupNotice = el('button', { class: 'notice amber', type: 'button', hidden: true, style: { width: '100%', border: '0', 'margin-top': '12px', 'text-align': 'start', cursor: 'pointer' }, on: { click: function () { K.go('/s/' + st.id + '/dupes'); } } });
    var noIdNotice = el('div', { hidden: true });
    var crossInput = el('input', { type: 'checkbox', checked: state.cross });
    crossInput.addEventListener('change', function () { state.cross = crossInput.checked; refresh(); });
    var crossToggle = el('label', { class: 'switch card flat', style: { padding: '10px 14px', 'margin-top': '12px' } },
      el('b', { class: 'small', text: 'افحص مع كشوفاتي السابقة' }),
      crossInput, el('span', { class: 'track' }));
    var search = el('input', { class: 'input', type: 'search', placeholder: 'ابحث بالاسم أو رقم الهوية…', value: state.q });
    search.addEventListener('input', K.debounce(function () { state.q = search.value; state.limit = 200; refresh(); }, 200));

    function tbtn(icon, label, fn, cls) { return el('button', { class: 'tbtn ' + (cls || ''), type: 'button', on: { click: fn } }, ic(icon, 'sm'), label); }
    var undoBtn = tbtn('rotl', 'تراجع', function () { doUndo(); });
    var redoBtn = tbtn('rotr', 'إعادة', function () { doRedo(); });
    var selBtn = tbtn('check', 'تحديد', function () { state.select = !state.select; if (!state.select) state.sel = {}; refresh(); });
    var tools = el('div', { class: 'tool-strip', role: 'toolbar', 'aria-label': 'أدوات التعديل' },
      undoBtn, redoBtn, selBtn,
      tbtn('filter', 'فرز', sortSheet),
      tbtn('plus', 'صف', function () { addRowInline(); }),
      tbtn('columns', 'عمود', function () { addColumn(st, function () { refresh(); }); }),
      tbtn('copy', 'نسخ', copyRows),
      tbtn('files', 'لصق', pasteSheet),
      tbtn('layers', 'المكررات', function () { K.go('/s/' + st.id + '/dupes'); }),
      tbtn('link', 'دمج', function () { mergeDialog(st.id); }),
      tbtn('camera', 'صفحة', function () { K.go('/s/' + st.id); }));
    var chips = el('div', { class: 'chips' });
    var hint = el('div', { class: 'tiny muted', style: { margin: '2px 2px 8px' } });
    var tblWrap = el('div', { class: 'tbl-wrap' });
    var moreBox = el('div', {});
    var bulkCount = el('b', {});
    var bulk = el('div', { class: 'bulk-bar', hidden: true },
      el('div', { class: 'in' },
        el('button', { class: 'btn btn-icon sm btn-ghost', type: 'button', 'aria-label': 'إلغاء التحديد', on: { click: function () { state.sel = {}; refresh(); } } }, ic('x')),
        bulkCount,
        el('div', { class: 'grow' }),
        el('button', { class: 'btn btn-sm', type: 'button', on: { click: bulkEdit } }, ic('edit', 'sm'), 'تعديل'),
        el('button', { class: 'btn btn-sm', type: 'button', on: { click: copyRows } }, ic('copy', 'sm'), 'نسخ'),
        el('button', { class: 'btn btn-sm', type: 'button', on: { click: bulkConfirm } }, ic('check', 'sm'), 'تأكيد'),
        el('button', { class: 'btn btn-sm btn-danger', type: 'button', on: { click: bulkDelete } }, ic('trash', 'sm'))));

    // ---------- التحديث ----------
    function refresh() {
      var ext = state.cross ? an.extIndex(others, st.id) : null;
      A = an.analyze(st, { ext: ext });
      var C = A.counts;
      metaLine.textContent = K.num(C.rows) + ' صف • ' + st.columns.length + ' عمود • ' + st.pages.length + ' ' + K.pagesWord(st.pages.length);
      tDup._v.textContent = K.num(C.dupRows);
      tBad._v.textContent = K.num(C.badRows);
      tMiss._v.textContent = K.num(C.missRows);
      tUnc._v.textContent = K.num(C.unclearRows);
      dupNotice.hidden = !C.dupGroups;
      if (C.dupGroups) {
        K.clear(dupNotice);
        dupNotice.appendChild(ic('copy'));
        dupNotice.appendChild(el('div', { class: 'grow' }, el('b', { text: C.dupGroups + ' ' + (C.dupGroups === 1 ? 'شخص مكرر' : 'أشخاص مكررين') + ' برقم الهوية' }), null));
        dupNotice.appendChild(ic('chevl'));
      }
      noIdNotice.hidden = !!A.idCols.length || !st.columns.length;
      K.clear(chips);
      chip('all', 'الكل', C.rows);
      chip('dup', 'مكرر', C.dupRows, 'amber');
      chip('bad', 'هوية غلط', C.badRows, 'red');
      chip('miss', 'ناقص', C.missRows, 'blue');
      chip('unclear', 'تحتاج مراجعة', C.unclearRows, 'yellow');
      chip('name', 'تشابه أسماء', C.nameRows, 'violet');
      if (state.cross) chip('ext', 'موجود بكشف ثاني', C.ext, 'violet');
      var h = hist(st);
      undoBtn.disabled = !h.undo.length;
      redoBtn.disabled = !h.redo.length;
      selBtn.classList.toggle('is-on', state.select);
      hint.textContent = '';
      hint.hidden = true;
      drawTable();
      updateBulk();
    }
    function chip(key, label, n, cls) {
      if (key !== 'all' && !n && state.filter !== key) return;
      var b = el('button', { class: 'chip ' + (cls || '') + (state.filter === key ? ' is-active' : ''), type: 'button' }, label, el('span', { class: 'n', text: K.num(n) }));
      b.addEventListener('click', function () { state.filter = key; state.limit = 200; refresh(); });
      chips.appendChild(b);
    }
    function rowMatches(r) {
      var f = A.flags[r.id];
      if (state.filter === 'dup' && !f.dup) return false;
      if (state.filter === 'bad' && !Object.keys(f.bad).length) return false;
      if (state.filter === 'miss' && !Object.keys(f.miss).length) return false;
      if (state.filter === 'unclear' && !Object.keys(f.unclear).length) return false;
      if (state.filter === 'name' && !f.nameDup) return false;
      if (state.filter === 'ext' && !f.ext) return false;
      var q = K.digits(state.q.trim());
      if (q) {
        var nq = an.normName(q);
        var hit = st.columns.some(function (c) {
          var v = String(r.cells[c.id] || '');
          return K.digits(v).indexOf(q) >= 0 || (nq && an.normName(v).indexOf(nq) >= 0);
        });
        if (!hit) return false;
      }
      return true;
    }
    function selCount() { return Object.keys(state.sel).filter(function (id) { return !!rowById(id); }).length; }
    function updateBulk() {
      var n = selCount();
      bulk.hidden = !(state.select && n);
      bulkCount.textContent = n + ' محدد';
    }

    function drawTable() {
      var sTop = tblWrap.scrollTop, sLeft = tblWrap.scrollLeft;
      K.clear(tblWrap);
      K.clear(moreBox);
      if (!st.columns.length) {
        tblWrap.appendChild(el('div', { class: 'empty', style: { margin: '14px' } }, ic('sheet'), el('h3', { text: 'ما في أعمدة لسا' }), el('p', { text: 'اقرأ صفحات الكشف أو أضف عمود جديد' })));
        return;
      }
      visible = A.rows.filter(rowMatches);
      var thead = el('tr', {});
      if (state.select) {
        var all = el('input', { type: 'checkbox', class: 'ck', 'aria-label': 'تحديد الكل' });
        all.checked = visible.length > 0 && visible.every(function (r) { return state.sel[r.id]; });
        all.addEventListener('change', function () {
          visible.forEach(function (r) { if (all.checked) state.sel[r.id] = 1; else delete state.sel[r.id]; });
          refresh();
        });
        thead.appendChild(el('th', { class: 'ckc' }, all));
      }
      thead.appendChild(el('th', { class: 'rn', text: '#' }));
      st.columns.forEach(function (c, ci) {
        var arrow = state.sortCol === c.id ? (state.sortDir > 0 ? ' ↑' : ' ↓') : '';
        var b = el('button', { type: 'button' }, c.label + arrow, ic('chevl', 'sm'));
        b.addEventListener('click', function () { commitEdit(); columnSheet(st, ci, refresh); });
        thead.appendChild(el('th', {}, b, el('span', { class: 'kind', text: an.KINDS[c.kind] || '' })));
      });
      var tbody = el('tbody', {});
      visible.slice(0, state.limit).forEach(function (r) {
        var f = A.flags[r.id];
        var sel = !!state.sel[r.id];
        var tr = el('tr', { class: (f.dup ? 'is-dup ' : '') + (f.ext ? 'is-ext ' : '') + (r.fresh ? 'is-new ' : '') + (sel ? 'is-sel' : ''), 'data-r': r.id });
        if (state.select) tr.appendChild(el('td', { class: 'ckc' }, el('input', { type: 'checkbox', class: 'ck', checked: sel, tabindex: '-1', 'aria-label': 'تحديد' })));
        tr.appendChild(el('td', { class: 'rn' }, String(f.n), f.dup ? el('span', { class: 'grp', title: 'مجموعة مكررة', text: '×' + f.dup }) : null));
        st.columns.forEach(function (c) {
          var v = r.cells[c.id] != null ? String(r.cells[c.id]) : '';
          var cls = c.kind === 'national_id' ? 'id' : c.kind === 'phone' ? 'phone' : (c.kind === 'number' || c.kind === 'serial') ? 'num' : '';
          if (f.bad[c.id]) cls += ' c-bad';
          else if (f.miss[c.id]) cls += ' c-miss';
          else if (f.unclear[c.id]) cls += ' c-unclear';
          tr.appendChild(el('td', { class: cls + ' cell', 'data-c': c.id, text: v || (f.miss[c.id] ? 'ناقص' : '—') }));
        });
        tbody.appendChild(tr);
      });
      tbody.addEventListener('click', onBodyClick);
      if (!visible.length) {
        tbody.appendChild(el('tr', {}, el('td', { colspan: String(st.columns.length + 2), class: 'center muted', style: { padding: '30px' }, text: state.q ? 'ما في نتائج للبحث' : 'ما في صفوف بهذا الفلتر' })));
      }
      tblWrap.appendChild(el('table', { class: 'tbl' + (state.select ? ' selecting' : '') }, el('thead', {}, thead), tbody));
      tblWrap.scrollTop = sTop;
      tblWrap.scrollLeft = sLeft;
      if (visible.length > state.limit) {
        moreBox.appendChild(el('div', { class: 'more-rows' }, el('button', { class: 'btn btn-soft btn-sm', type: 'button', on: { click: function () { state.limit += 300; refresh(); } } }, 'عرض المزيد (' + (visible.length - state.limit) + ')')));
      }
      var anyFresh = false;
      st.rows.forEach(function (r) { if (r.fresh) { delete r.fresh; anyFresh = true; } });
      if (anyFresh) K.db.put('statements', st).catch(function () { });
    }

    function onBodyClick(e) {
      var td = e.target.closest ? e.target.closest('td') : null;
      if (!td || td.classList.contains('editing')) return;
      var tr = td.parentNode;
      var rid = tr && tr.getAttribute('data-r');
      if (!rid) return;
      if (state.select) {
        if (state.sel[rid]) delete state.sel[rid]; else state.sel[rid] = 1;
        tr.classList.toggle('is-sel', !!state.sel[rid]);
        var ck = tr.querySelector('.ck');
        if (ck) ck.checked = !!state.sel[rid];
        updateBulk();
        return;
      }
      if (td.classList.contains('rn')) {
        commitEdit();
        var r = rowById(rid);
        if (r) rowSheet(st, r, A, refresh);
        return;
      }
      var cid = td.getAttribute('data-c');
      if (cid) editCell(rid, cid);
    }

    // ---------- التعديل المباشر داخل الخلية ----------
    function findTd(rid, cid) {
      var tr = tblWrap.querySelector('tr[data-r="' + rid + '"]');
      return tr ? tr.querySelector('td[data-c="' + cid + '"]') : null;
    }
    function editCell(rid, cid) {
      if (editing) commitEdit();
      var td = findTd(rid, cid);
      var r = rowById(rid), c = colById(cid);
      if (!td || !r || !c) return;
      var old = r.cells[cid] != null ? String(r.cells[cid]) : '';
      var ltr = isLtrKind(c.kind);
      var inp = el('input', { class: 'cell-input' + (ltr ? ' ltr' : ''), value: old, dir: ltr ? 'ltr' : 'rtl', inputmode: ltr ? 'numeric' : null, enterkeyhint: 'next', autocomplete: 'off', spellcheck: 'false', 'aria-label': c.label });
      inp.style.minWidth = Math.max(td.offsetWidth, ltr ? 130 : 170) + 'px';
      td.classList.add('editing');
      K.clear(td).appendChild(inp);
      var me = { rid: rid, cid: cid, inp: inp, old: old, td: td };
      editing = me;
      try { inp.focus({ preventScroll: true }); inp.select(); } catch (x) { inp.focus(); }
      if (c.kind === 'national_id') {
        var chk = function () { var s = an.idStatus(inp.value); inp.classList.toggle('bad', !!s && s !== 'ok'); inp.classList.toggle('ok', s === 'ok'); };
        inp.addEventListener('input', chk);
        chk();
      }
      inp.addEventListener('keydown', function (e) {
        if (e.key === 'Enter') { e.preventDefault(); commitEdit(); move(rid, cid, e.shiftKey ? -1 : 1, 0); }
        else if (e.key === 'Tab') { e.preventDefault(); commitEdit(); move(rid, cid, 0, e.shiftKey ? -1 : 1); }
        else if (e.key === 'Escape') { e.preventDefault(); cancelEdit(); }
      });
      inp.addEventListener('blur', function () { setTimeout(function () { if (editing === me) commitEdit(); }, 0); });
      inp.addEventListener('paste', function (e) {
        var t = (e.clipboardData || window.clipboardData).getData('text');
        if (/[\t\n]/.test(String(t).trim())) { e.preventDefault(); cancelEdit(); pasteGrid(rid, cid, t); }
      });
    }
    function cancelEdit() {
      if (!editing) return;
      var ed = editing;
      editing = null;
      ed.td.classList.remove('editing');
      K.clear(ed.td).textContent = ed.old || '—';
    }
    function commitEdit() {
      if (!editing) return;
      var ed = editing;
      editing = null;
      var r = rowById(ed.rid), c = colById(ed.cid);
      var v = normCell(c, ed.inp.value);
      if (!r || !c || v === ed.old) {
        ed.td.classList.remove('editing');
        K.clear(ed.td).textContent = ed.old || '—';
        return;
      }
      pushUndo(st);
      r.cells[c.id] = v;
      r.unclear = (r.unclear || []).filter(function (x) { return x !== c.id; });
      saveStatement(st).catch(function (e) { K.toast(e.message, 'error'); });
      refresh();
      var td = findTd(ed.rid, ed.cid);
      if (td) { td.classList.add('flash'); setTimeout(function () { td.classList.remove('flash'); }, 900); }
    }
    function move(rid, cid, dr, dc) {
      var ri = -1;
      var shown = visible.slice(0, state.limit);
      for (var i = 0; i < shown.length; i++) if (shown[i].id === rid) { ri = i; break; }
      var ci = -1;
      for (var j = 0; j < st.columns.length; j++) if (st.columns[j].id === cid) { ci = j; break; }
      if (ri < 0 || ci < 0) return;
      ri += dr; ci += dc;
      if (ci >= st.columns.length) { ci = 0; ri++; }
      if (ci < 0) { ci = st.columns.length - 1; ri--; }
      if (ri < 0 || ri >= shown.length) return;
      editCell(shown[ri].id, st.columns[ci].id);
      var td = findTd(shown[ri].id, st.columns[ci].id);
      if (td && td.scrollIntoView) td.scrollIntoView({ block: 'nearest', inline: 'nearest' });
    }

    /** لصق جدول (من Excel أو واتساب) ابتداءً من خلية */
    function pasteGrid(rid, cid, text) {
      var grid = parseClip(text);
      if (!grid.length) return;
      pushUndo(st);
      var shown = visible.slice(0, state.limit);
      var ri = shown.findIndex(function (r) { return r.id === rid; });
      var ci = st.columns.findIndex(function (c) { return c.id === cid; });
      var cells = 0, added = 0;
      grid.forEach(function (line, k) {
        var r = shown[ri + k];
        if (!r) { r = blankRow(); st.rows.push(r); added++; }
        line.forEach(function (val, m) {
          var c = st.columns[ci + m];
          if (!c) { c = { id: K.uid(), label: 'عمود ' + (st.columns.length + 1), kind: 'text' }; st.columns.push(c); }
          r.cells[c.id] = normCell(c, val);
          r.unclear = (r.unclear || []).filter(function (x) { return x !== c.id; });
          cells++;
        });
      });
      commitAll('تم لصق ' + cells + ' خانة' + (added ? ' و' + added + ' صف جديد' : '') + ' ✓');
    }

    function blankRow() {
      var r = { id: K.uid(), page: null, cells: {}, unclear: [], y: null, manual: true };
      st.columns.forEach(function (c) { r.cells[c.id] = ''; });
      return r;
    }

    function addRowInline() {
      commitEdit();
      if (!st.columns.length) { addColumn(st, function () { refresh(); }); return; }
      pushUndo(st);
      var r = blankRow();
      var selIds = Object.keys(state.sel);
      var at = st.rows.length;
      if (state.select && selIds.length) {
        for (var i = st.rows.length - 1; i >= 0; i--) if (state.sel[st.rows[i].id]) { at = i + 1; break; }
      }
      st.rows.splice(at, 0, r);
      state.filter = 'all'; state.q = ''; search.value = ''; state.select = false; state.sel = {};
      state.limit = Math.max(state.limit, st.rows.length + 1);
      saveStatement(st);
      refresh();
      editCell(r.id, st.columns[0].id);
      var td = findTd(r.id, st.columns[0].id);
      if (td && td.scrollIntoView) td.scrollIntoView({ block: 'center' });
    }

    // ---------- الفرز ----------
    function sortSheet() {
      commitEdit();
      if (!st.columns.length) return;
      var list = el('div', { class: 'stack' });
      var sh = K.sheet({ title: 'فرز البيانات', body: list });
      st.columns.forEach(function (c) {
        var num = isLtrKind(c.kind);
        list.appendChild(el('div', { class: 'row', style: { gap: '8px' } },
          el('div', { class: 'grow' }, el('b', { text: c.label }), el('div', { class: 'tiny muted', text: an.KINDS[c.kind] })),
          el('button', { class: 'btn btn-sm' + (state.sortCol === c.id && state.sortDir > 0 ? ' btn-soft' : ''), type: 'button', on: { click: function () { sh.close(); sortBy(c.id, 1); } } }, num ? '1 → 9' : 'أ → ي'),
          el('button', { class: 'btn btn-sm' + (state.sortCol === c.id && state.sortDir < 0 ? ' btn-soft' : ''), type: 'button', on: { click: function () { sh.close(); sortBy(c.id, -1); } } }, num ? '9 → 1' : 'ي → أ')));
      });
      list.appendChild(el('button', { class: 'btn btn-block', type: 'button', on: { click: function () { sh.close(); sortOriginal(); } } }, ic('refresh', 'sm'), 'الترتيب الأصلي (حسب الورقة)'));
    }
    function ensureOrder() {
      var max = -1;
      st.rows.forEach(function (r) { if (typeof r.o === 'number' && r.o > max) max = r.o; });
      st.rows.forEach(function (r) { if (typeof r.o !== 'number') r.o = ++max; });
    }
    function sortBy(cid, dir) {
      var c = colById(cid);
      if (!c) return;
      pushUndo(st);
      ensureOrder();
      var num = isLtrKind(c.kind);
      st.rows.sort(function (a, b) {
        var va = String(a.cells[cid] || '').trim(), vb = String(b.cells[cid] || '').trim();
        if (!va && vb) return 1;
        if (va && !vb) return -1;
        var cmp = 0;
        if (num) {
          var na = parseFloat(K.digits(va).replace(/[^\d.\-]/g, '')), nb = parseFloat(K.digits(vb).replace(/[^\d.\-]/g, ''));
          if (!isNaN(na) && !isNaN(nb)) cmp = na - nb;
        }
        if (!cmp) cmp = collator ? collator.compare(va, vb) : (va < vb ? -1 : va > vb ? 1 : 0);
        return cmp * dir || (a.o - b.o);
      });
      state.sortCol = cid; state.sortDir = dir;
      commitAll('تم الفرز حسب "' + c.label + '" ✓');
    }
    function sortOriginal() {
      pushUndo(st);
      ensureOrder();
      st.rows.sort(function (a, b) { return a.o - b.o; });
      state.sortCol = null;
      commitAll('رجع الترتيب الأصلي ✓');
    }

    // ---------- النسخ واللصق ----------
    function copyRows() {
      commitEdit();
      var ids = state.select ? state.sel : null;
      var rows = (ids && Object.keys(ids).length) ? st.rows.filter(function (r) { return ids[r.id]; }) : visible;
      if (!rows.length) { K.toast('ما في صفوف للنسخ', 'warn'); return; }
      K.copy(toTsv(st, rows, true), 'تم نسخ ' + rows.length + ' صف — الصقه بإكسل أو جوجل شيت ✓');
    }
    function pasteSheet() {
      commitEdit();
      var ta = el('textarea', { class: 'textarea ltr', style: { 'min-height': '150px', 'font-family': 'var(--mono)', 'font-size': '13px' }, placeholder: 'الصق هون الصفوف المنسوخة من Excel أو Google Sheets…' });
      var head = el('input', { type: 'checkbox' });
      var info = el('div', { class: 'small muted' });
      function preview() {
        var g = parseClip(ta.value);
        var first = g[0] || [];
        var looksHeader = first.length && st.columns.some(function (c) { return first.some(function (x) { return an.normName(x) && an.normName(x) === an.normName(c.label); }); });
        if (ta._auto !== false) head.checked = !!looksHeader;
        var n = g.length - (head.checked ? 1 : 0);
        info.textContent = g.length ? 'رح ينضاف ' + Math.max(0, n) + ' صف' + (first.length ? ' • ' + first.length + ' عمود' : '') : '';
      }
      ta.addEventListener('input', preview);
      head.addEventListener('change', function () { ta._auto = false; preview(); });
      var readClip = (K.nat && K.nat.on) ? K.nat.paste : (navigator.clipboard && navigator.clipboard.readText && window.isSecureContext ? function () { return navigator.clipboard.readText(); } : null);
      var readBtn = readClip
        ? el('button', { class: 'btn btn-soft btn-sm', type: 'button', on: { click: function () { readClip().then(function (t) { ta.value = t; preview(); }, function () { K.toast('ما قدرنا نقرأ الحافظة', 'warn'); }); } } }, ic('files', 'sm'), 'لصق من الحافظة')
        : null;
      K.sheet({
        title: 'لصق صفوف',
        body: el('div', { class: 'stack' },
          readBtn, ta,
          el('label', { class: 'switch' }, el('b', { class: 'small', text: 'أول سطر هو عناوين الأعمدة' }), head, el('span', { class: 'track' })),
          info),
        actions: [{ label: 'إلغاء' }, {
          label: 'إضافة', primary: true, icon: 'plus', onClick: function () {
            var g = parseClip(ta.value);
            if (!g.length) { K.toast('ما في بيانات', 'warn'); return true; }
            pushUndo(st);
            var map;
            if (head.checked) {
              var hdr = g.shift();
              map = hdr.map(function (lab) {
                var n = an.normName(lab);
                var c = st.columns.filter(function (x) { return an.normName(x.label) === n; })[0];
                if (!c) { c = { id: K.uid(), label: String(lab).trim() || 'عمود', kind: 'text' }; st.columns.push(c); }
                return c;
              });
            } else {
              var w = Math.max.apply(null, g.map(function (l) { return l.length; }));
              while (st.columns.length < w) st.columns.push({ id: K.uid(), label: 'عمود ' + (st.columns.length + 1), kind: 'text' });
              map = st.columns.slice(0, w);
            }
            g.forEach(function (line) {
              var r = blankRow();
              line.forEach(function (v, i) { if (map[i]) r.cells[map[i].id] = normCell(map[i], v); });
              st.rows.push(r);
            });
            state.limit = Math.max(state.limit, st.rows.length);
            return commitAll('تمت إضافة ' + g.length + ' صف ✓');
          }
        }]
      });
    }

    // ---------- التحديد المتعدد ----------
    function selectedRows() { return st.rows.filter(function (r) { return state.sel[r.id]; }); }
    function bulkDelete() {
      var rows = selectedRows();
      if (!rows.length) return;
      K.confirm('حذف ' + rows.length + ' صف محدد؟ (بتقدر تتراجع)', { danger: true, ok: 'حذف' }).then(function (ok) {
        if (!ok) return;
        pushUndo(st);
        st.rows = st.rows.filter(function (r) { return !state.sel[r.id]; });
        state.sel = {};
        commitAll('تم حذف ' + rows.length + ' صف ✓');
      });
    }
    function bulkConfirm() {
      var rows = selectedRows();
      if (!rows.length) return;
      pushUndo(st);
      rows.forEach(function (r) { r.unclear = []; r.reviewed = true; });
      commitAll('تم تأكيد ' + rows.length + ' صف ✓');
    }
    function bulkEdit() {
      var rows = selectedRows();
      if (!rows.length) return;
      var colSel = el('select', { class: 'select' });
      st.columns.forEach(function (c) { colSel.appendChild(el('option', { value: c.id, text: c.label })); });
      var val = el('input', { class: 'input', placeholder: 'القيمة الجديدة (اتركها فاضية للمسح)' });
      K.sheet({
        title: 'تعديل ' + rows.length + ' صف',
        body: el('div', { class: 'stack' },
          el('div', { class: 'field' }, el('label', { text: 'العمود' }), colSel),
          el('div', { class: 'field' }, el('label', { text: 'القيمة' }), val)),
        actions: [{ label: 'إلغاء' }, {
          label: 'تطبيق', primary: true, icon: 'check', onClick: function () {
            var c = colById(colSel.value);
            if (!c) return true;
            pushUndo(st);
            var v = normCell(c, val.value);
            rows.forEach(function (r) { r.cells[c.id] = v; r.unclear = (r.unclear || []).filter(function (x) { return x !== c.id; }); });
            return commitAll('تم تعديل "' + c.label + '" لـ ' + rows.length + ' صف ✓');
          }
        }]
      });
    }

    // ---------- التراجع ----------
    function doUndo() {
      commitEdit();
      if (undoEdit(st)) { saveStatement(st); refresh(); K.toast('تم التراجع ↶', 'info', 1600); }
    }
    function doRedo() {
      commitEdit();
      if (redoEdit(st)) { saveStatement(st); refresh(); K.toast('تمت الإعادة ↷', 'info', 1600); }
    }

    var exportBtn = el('button', { class: 'btn btn-primary btn-sm', type: 'button', on: { click: function () { commitEdit(); exportDialog(st); } } }, ic('download'), 'تصدير');
    var body = el('div', {},
      el('div', { class: 'review-head' }, el('div', { class: 'grow' }, titleBtn, metaLine)),
      el('div', { class: 'stat-tiles four' }, tDup, tBad, tMiss, tUnc),
      dupNotice, noIdNotice, crossToggle,
      el('div', { class: 'toolbar' }, el('div', { class: 'input-wrap' }, ic('search'), search)),
      tools, chips, hint, tblWrap, moreBox,
      el('div', { class: 'legend' },
        el('span', {}, el('i', { style: { background: 'var(--amber-soft)', border: '1px solid var(--amber)' } }), 'مكرر برقم الهوية'),
        el('span', {}, el('i', { style: { background: 'var(--red-soft)', border: '1px solid var(--red)' } }), 'رقم هوية غير صحيح'),
        el('span', {}, el('i', { style: { background: 'var(--blue-soft)', border: '1px dashed var(--blue)' } }), 'خانة ناقصة'),
        el('span', {}, el('i', { style: { background: 'var(--yellow-soft)', border: '1px solid var(--yellow)' } }), 'غير واضحة')),
      el('div', { class: 'card stack', style: { 'margin-top': '18px' } },
        el('div', { class: 'card-title' }, ic('download'), 'التصدير'),
        el('div', { class: 'grid-3' },
          el('button', { class: 'btn btn-primary', type: 'button', on: { click: function () { commitEdit(); doExport(st, 'xlsx', { deleteImages: true }); } } }, ic('sheet'), 'Excel'),
          el('button', { class: 'btn btn-dark', type: 'button', on: { click: function () { commitEdit(); doExport(st, 'pdf', { deleteImages: true }); } } }, ic('files'), 'PDF'),
          el('button', { class: 'btn', type: 'button', on: { click: function () { commitEdit(); exportDialog(st); } } }, ic('more'), 'خيارات'))));

    render(layout({ back: '/statements', title: 'محرر الكشف', actions: exportBtn, body: body, nav: 'list', cta: bulk }));
    refresh();
    S.keys = function (e) {
      var mod = e.ctrlKey || e.metaKey;
      if (!mod) return;
      var tag = (e.target && e.target.tagName) || '';
      var inField = (tag === 'INPUT' || tag === 'TEXTAREA') && !(e.target.classList && e.target.classList.contains('cell-input'));
      if (inField) return;
      var k = e.key.toLowerCase();
      if (k === 'z' && !e.shiftKey) { e.preventDefault(); doUndo(); }
      else if (k === 'y' || (k === 'z' && e.shiftKey)) { e.preventDefault(); doRedo(); }
    };
  }

  function addColumn(st, after) {
    var name = el('input', { class: 'input', placeholder: 'مثال: المنطقة' });
    var kind = kindSelect('text');
    K.sheet({
      title: 'عمود جديد',
      body: el('div', { class: 'stack' }, el('div', { class: 'field' }, el('label', { text: 'اسم العمود' }), name), el('div', { class: 'field' }, el('label', { text: 'نوع البيانات' }), kind)),
      actions: [{ label: 'إلغاء' }, {
        label: 'إضافة', primary: true, onClick: function () {
          if (!name.value.trim()) { K.toast('اكتب اسم العمود', 'warn'); return true; }
          pushUndo(st);
          var col = { id: K.uid(), label: name.value.trim().slice(0, 60), kind: kind.value };
          st.columns.push(col);
          st.rows.forEach(function (r) { if (r.cells[col.id] == null) r.cells[col.id] = ''; });
          return saveStatement(st).then(after);
        }
      }]
    });
  }

  function kindSelect(val) {
    var s = el('select', { class: 'select' });
    KIND_OPTS.forEach(function (o) { s.appendChild(el('option', { value: o[0], text: o[1] })); });
    s.value = val;
    return s;
  }

  function columnSheet(st, ci, after) {
    var c = st.columns[ci];
    var name = el('input', { class: 'input', value: c.label });
    var kind = kindSelect(c.kind);
    var moveR = el('button', { class: 'btn btn-sm', type: 'button', disabled: ci === 0 }, ic('chevr'), 'لليمين');
    var moveL = el('button', { class: 'btn btn-sm', type: 'button', disabled: ci === st.columns.length - 1 }, 'لليسار', ic('chevl'));
    var sh = K.sheet({
      title: 'العمود: ' + c.label,
      body: el('div', { class: 'stack' },
        el('div', { class: 'field' }, el('label', { text: 'اسم العمود' }), name),
        el('div', { class: 'field' }, el('label', { text: 'نوع البيانات' }), kind),
        el('div', { class: 'row' }, el('span', { class: 'label grow', text: 'الترتيب' }), moveR, moveL),
        el('button', { class: 'btn btn-danger btn-block', type: 'button', on: { click: delCol } }, ic('trash'), 'حذف العمود')),
      actions: [{ label: 'إلغاء' }, {
        label: 'حفظ', primary: true, onClick: function () {
          var nl = (name.value.trim() || c.label).slice(0, 60);
          if (nl === c.label && kind.value === c.kind) return;
          pushUndo(st);
          c.label = nl;
          c.kind = kind.value;
          return saveStatement(st).then(after);
        }
      }]
    });
    function move(d) {
      var j = ci + d;
      if (j < 0 || j >= st.columns.length) return;
      pushUndo(st);
      var t = st.columns[ci]; st.columns[ci] = st.columns[j]; st.columns[j] = t;
      sh.close();
      saveStatement(st).then(after);
    }
    moveR.addEventListener('click', function () { move(-1); });
    moveL.addEventListener('click', function () { move(1); });
    function delCol() {
      K.confirm('حذف عمود "' + c.label + '" وكل بياناته؟ (بتقدر تتراجع)', { danger: true, ok: 'حذف' }).then(function (ok) {
        if (!ok) return;
        pushUndo(st);
        st.columns.splice(ci, 1);
        st.rows.forEach(function (r) { delete r.cells[c.id]; r.unclear = (r.unclear || []).filter(function (x) { return x !== c.id; }); });
        sh.close();
        saveStatement(st).then(after);
      });
    }
  }

  /** تفاصيل الصف: صورة السطر الأصلي + كل الخانات */
  function rowSheet(st, r, A, after) {
    var f = A.flags[r.id] || { n: st.rows.length, bad: {}, unclear: {}, miss: {}, dup: 0 };
    var inputs = {};
    var stripBox = el('div', {});
    if (r.y && r.page) {
      var pg = st.pages.filter(function (x) { return x.id === r.page; })[0];
      if (pg && !pg.imageGone) {
        stripBox.appendChild(el('div', { class: 'skeleton', style: { height: '70px' } }));
        K.db.get('images', r.page).then(function (im) {
          if (!im) throw new Error('no image');
          return K.img.strip(im.blob, r.y[0], r.y[1], 1000);
        }).then(function (c) {
          K.clear(stripBox).appendChild(el('div', { class: 'stack', style: { gap: '6px' } }, el('div', { class: 'tiny muted', text: 'من الصورة الأصلية — قارن وصحّح' }), el('div', { class: 'strip' }, c)));
        }).catch(function () { K.clear(stripBox); });
      }
    }
    var fields = el('div', { class: 'row-fields' });
    st.columns.forEach(function (c) {
      var v = r.cells[c.id] != null ? String(r.cells[c.id]) : '';
      var ltr = isLtrKind(c.kind);
      var inp = el('input', { class: 'input' + (ltr ? ' ltr' : '') + (f.bad[c.id] ? ' bad' : f.unclear[c.id] ? ' unclear' : ''), value: v, inputmode: ltr ? 'numeric' : null, dir: ltr ? 'ltr' : null });
      inputs[c.id] = inp;
      var stateEl = null;
      if (c.kind === 'national_id') {
        stateEl = el('span', { class: 'id-state' });
        var upd = function () {
          var s = an.idStatus(inp.value);
          K.clear(stateEl);
          stateEl.className = 'id-state ' + (s === 'ok' ? 'ok' : s ? 'bad' : '');
          if (s) { stateEl.appendChild(ic(s === 'ok' ? 'check' : 'alert', 'sm')); stateEl.appendChild(document.createTextNode(an.ID_MSG[s])); }
          inp.classList.toggle('bad', !!s && s !== 'ok');
        };
        inp.addEventListener('input', upd);
        upd();
      }
      fields.appendChild(el('div', { class: 'field' },
        el('label', {}, c.label, f.unclear[c.id] ? el('span', { class: 'badge yellow', style: { 'margin-inline-start': '8px' } }, 'غير واضحة') : null, f.miss && f.miss[c.id] ? el('span', { class: 'badge blue', style: { 'margin-inline-start': '8px' } }, 'ناقصة') : null),
        inp, stateEl));
    });
    var dupInfo = null;
    if (f.dup) {
      var g = A.groups.filter(function (x) { return x.type === 'id' && x.n === f.dup; })[0];
      if (g) {
        var mates = g.rows.filter(function (x) { return x !== r.id; }).map(function (x) { return A.flags[x] ? A.flags[x].n : '?'; });
        dupInfo = el('div', { class: 'notice amber' }, ic('copy'), el('div', { class: 'small', text: 'هذا الشخص مكرر برقم الهوية مع الصف ' + mates.join('، ') }));
      }
    }
    var extInfo = f.ext ? el('div', { class: 'notice', style: { background: 'var(--violet-soft)' } }, ic('layers'), el('div', { class: 'small', text: 'موجود كمان في: ' + f.ext.join('، ') })) : null;
    var insertBtn = el('button', { class: 'btn btn-sm btn-block', type: 'button' }, ic('plus', 'sm'), 'إدراج صف فاضي تحته');
    var sh = K.sheet({
      title: 'الصف رقم ' + f.n,
      body: el('div', { class: 'stack' }, stripBox, dupInfo, extInfo, fields, insertBtn),
      actions: [
        { label: 'حذف', danger: true, icon: 'trash', onClick: function () {
          pushUndo(st);
          st.rows = st.rows.filter(function (x) { return x.id !== r.id; });
          return saveStatement(st).then(function () { K.toast('تم حذف الصف — بتقدر تتراجع', 'success'); after(); });
        } },
        { label: 'حفظ', primary: true, icon: 'check', onClick: function () {
          pushUndo(st);
          st.columns.forEach(function (c) { r.cells[c.id] = normCell(c, inputs[c.id].value); });
          r.unclear = [];
          r.reviewed = true;
          return saveStatement(st).then(function () { after(); });
        } }]
    });
    insertBtn.addEventListener('click', function () {
      pushUndo(st);
      var nr = { id: K.uid(), page: null, cells: {}, unclear: [], y: null, manual: true };
      st.columns.forEach(function (c) { nr.cells[c.id] = ''; });
      var idx = st.rows.indexOf(r);
      st.rows.splice(idx < 0 ? st.rows.length : idx + 1, 0, nr);
      sh.close();
      saveStatement(st).then(function () { K.toast('تمت إضافة صف — اضغط على خاناته لتعبئتها', 'success'); after(); });
    });
  }

  // ================================================================
  // المكررات
  // ================================================================
  function viewDupes(p) {
    getStatement(p.id).then(function (st) { drawDupes(st); }).catch(function (e) { K.toast(e.message, 'error'); K.go('/statements', true); });
  }

  function drawDupes(st) {
    var state = S.cache['dp_' + st.id] || (S.cache['dp_' + st.id] = { tab: 'id' });
    var A = an.analyze(st);
    var byId = {};
    A.rows.forEach(function (r) { byId[r.id] = r; });
    var groups = A.groups.filter(function (g) { return g.type === state.tab; });
    var counts = { id: 0, name: 0, phone: 0 };
    A.groups.forEach(function (g) { counts[g.type]++; });
    var seg = el('div', { class: 'seg' });
    [['id', 'رقم الهوية'], ['name', 'تشابه الأسماء'], ['phone', 'نفس الجوال']].forEach(function (t) {
      seg.appendChild(el('button', { type: 'button', class: state.tab === t[0] ? 'is-active' : '', on: { click: function () { state.tab = t[0]; drawDupes(st); } } }, t[1] + ' (' + counts[t[0]] + ')'));
    });
    var nameCol = A.nameCol;
    var list = el('div', { class: 'stack' });
    var desc = {
      id: 'نفس رقم الهوية مكتوب أكثر من مرة — غالباً نفس الشخص مسجل مرتين.',
      name: 'نفس الاسم بأرقام هوية مختلفة — ممكن يكون تشابه أسماء أو خطأ بكتابة الهوية. راجعها يدوياً.',
      phone: 'نفس رقم الجوال لأكثر من شخص — غالباً أفراد من نفس العائلة.'
    };
    if (!groups.length) {
      list.appendChild(el('div', { class: 'empty' }, ic('check'), el('h3', { text: 'ما في مكررات هون ✓' }), el('p', { text: state.tab === 'id' ? 'كل أرقام الهوية بالكشف مختلفة' : 'ما لقينا شي بهذا النوع' })));
    }
    groups.forEach(function (g) {
      var label = g.type === 'id' ? 'رقم الهوية ' + g.key : g.type === 'phone' ? 'الجوال ' + g.key : (nameCol && byId[g.rows[0]] ? byId[g.rows[0]].cells[nameCol.id] : g.key);
      var box = el('div', { class: 'dup-group' },
        el('div', { class: 'dup-group-head ' + g.type },
          ic(g.type === 'id' ? 'idcard' : g.type === 'phone' ? 'phone' : 'user'),
          el('b', { class: 'grow' + (g.type !== 'name' ? ' ltr' : ''), style: { 'text-align': 'right' }, text: label }),
          el('span', { class: 'badge', text: g.rows.length + ' مرات' })));
      g.rows.forEach(function (rid, k) {
        var r = byId[rid];
        if (!r) return;
        var f = A.flags[rid];
        var sub = A.idCols.map(function (c) { return r.cells[c.id]; }).filter(Boolean).join(' • ');
        var pg = st.pages.filter(function (x) { return x.id === r.page; })[0];
        box.appendChild(el('div', { class: 'dup-row' },
          el('span', { class: 'badge', text: '#' + f.n }),
          el('div', { class: 'grow' }, el('div', { class: 't', text: nameCol ? (r.cells[nameCol.id] || '—') : sub }), el('div', { class: 's ltr', style: { 'text-align': 'right' }, text: sub + (pg ? '  •  صفحة ' + pg.n : '') })),
          k === 0 && g.type === 'id' ? el('span', { class: 'badge green', text: 'الأول' }) : null,
          el('button', { class: 'btn btn-icon sm', type: 'button', 'aria-label': 'عرض', on: { click: function () { rowSheet(st, r, A, function () { drawDupes(st); }); } } }, ic('eye')),
          el('button', { class: 'btn btn-icon sm btn-danger', type: 'button', 'aria-label': 'حذف', on: { click: function () {
            K.confirm('حذف الصف #' + f.n + '؟', { danger: true, ok: 'حذف' }).then(function (ok) {
              if (!ok) return;
              pushUndo(st);
              st.rows = st.rows.filter(function (x) { return x.id !== rid; });
              saveStatement(st).then(function () { drawDupes(st); K.toast('تم الحذف — بتقدر تتراجع من المحرر', 'success'); });
            });
          } } }, ic('trash'))));
      });
      if (g.type === 'id') {
        box.appendChild(el('div', { class: 'dup-row' }, el('button', { class: 'btn btn-soft btn-sm btn-block', type: 'button', on: { click: function () { keepFirst([g]); } } }, ic('check'), 'إبقاء الأول وحذف الباقي')));
      }
      list.appendChild(box);
    });

    function keepFirst(gs) {
      var remove = {};
      var n = 0;
      gs.forEach(function (g) { g.rows.slice(1).forEach(function (rid) { if (!remove[rid]) { remove[rid] = 1; n++; } }); });
      K.confirm('رح ينحذف ' + n + ' صف مكرر ويضل أول ظهور لكل شخص. متأكد؟', { ok: 'حذف المكرر' }).then(function (ok) {
        if (!ok) return;
        pushUndo(st);
        st.rows = st.rows.filter(function (r) { return !remove[r.id]; });
        saveStatement(st).then(function () { K.toast('تم حذف ' + n + ' صف مكرر ✓', 'success'); drawDupes(st); });
      });
    }

    var idGroups = A.groups.filter(function (g) { return g.type === 'id'; });
    var body = el('div', { class: 'stack lg' },
      el('div', {}, el('h1', { style: { 'font-size': '21px' }, text: 'المكررات' }), el('div', { class: 'muted small', text: st.title })),
      seg,
      el('p', { class: 'muted small', text: desc[state.tab] }),
      state.tab === 'id' && idGroups.length ? el('button', { class: 'btn btn-primary btn-block', type: 'button', on: { click: function () { keepFirst(idGroups); } } }, ic('sparkles'), 'حذف كل المكرر (إبقاء أول ظهور)') : null,
      list);
    render(layout({ back: '/s/' + st.id + '/review', title: 'المكررات', body: body, nav: 'list' }));
  }

  // ================================================================
  // التصدير
  // ================================================================
  function safeFileName(s) { return String(s || 'كشف').replace(/[\\/:*?"<>|]+/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 80) || 'كشف'; }

  function exportDialog(st) {
    var name = el('input', { class: 'input', value: safeFileName(st.title) });
    var delImgs = el('input', { type: 'checkbox', checked: true });
    var withDupes = el('input', { type: 'checkbox', checked: true });
    var withIds = el('input', { type: 'checkbox', checked: true });
    function sw(input, t, s) { return el('label', { class: 'switch' }, el('div', {}, el('b', { class: 'small', text: t }), s ? el('div', { class: 'tiny muted', text: s }) : null), input, el('span', { class: 'track' })); }
    var nat = !!(K.nat && K.nat.on);
    var canShare = nat || !!(navigator.canShare && window.File);
    var sh;
    function opts() { return { name: name.value, deleteImages: delImgs.checked, dupes: withDupes.checked, ids: withIds.checked }; }
    function run(kind) { return function () { sh.close(); doExport(st, kind, opts()); }; }
    sh = K.sheet({
      title: 'تصدير الكشف',
      body: el('div', { class: 'stack' },
        el('div', { class: 'field' }, el('label', { text: 'اسم الملف' }), name),
        el('div', { class: 'card flat', style: { padding: '4px 14px' } },
          sw(withDupes, 'ورقة المكررات'),
          sw(withIds, 'ورقة تدقيق أرقام الهوية'),
          sw(delImgs, 'حذف صور الكشف بعد التصدير')),
        el('div', { class: 'grid-2' },
          el('button', { class: 'btn btn-primary', type: 'button', on: { click: run('xlsx') } }, ic('sheet'), 'Excel'),
          el('button', { class: 'btn btn-dark', type: 'button', on: { click: run('pdf') } }, ic('files'), 'PDF عالي الدقة'),
          canShare ? el('button', { class: 'btn', type: 'button', on: { click: run('share') } }, ic('share'), 'مشاركة') : null,
          el('button', { class: 'btn', type: 'button', on: { click: run('csv') } }, ic('files'), 'CSV'),
          nat ? el('button', { class: 'btn', type: 'button', on: { click: run('save') } }, ic('download'), 'حفظ بالجهاز')
            : el('button', { class: 'btn', type: 'button', on: { click: run('print') } }, ic('printer'), 'طباعة')))
    });
  }

  function doExport(st, kind, o) {
    o = o || {};
    var A = an.analyze(st, { skipEmpty: true });
    if (!A.rows.length) { K.toast('الكشف فاضي', 'warn'); return; }
    var fname = safeFileName(o.name || st.title);
    var p;
    try {
      if (kind === 'xlsx' || kind === 'share' || kind === 'save') {
        var blob = K.xlsx.fromStatement(st, A, { dupes: o.dupes !== false, ids: o.ids !== false, appName: appName() });
        if (kind === 'save' && K.nat && K.nat.on) {
          p = K.nat.saveToDevice(blob, fname + '.xlsx').then(function (where) { K.toast('انحفظ بـ ' + where, 'success', 4500); });
        } else if (kind === 'share' && !(K.nat && K.nat.on)) {
          var file = new File([blob], fname + '.xlsx', { type: blob.type });
          if (navigator.canShare && navigator.canShare({ files: [file] })) {
            p = navigator.share({ files: [file], title: fname, text: appName() });
          } else {
            K.downloadBlob(blob, fname + '.xlsx');
            p = Promise.resolve();
          }
        } else {
          p = K.deliver(blob, fname + '.xlsx');
        }
      } else if (kind === 'csv') {
        p = K.deliver(toCsv(st, A), fname + '.csv');
      } else if (kind === 'pdf') {
        var bar = el('span', { style: { width: '4%' } });
        var prog = K.sheet({ title: 'تجهيز ملف PDF', noClose: true, body: el('div', { class: 'stack' }, el('p', { class: 'muted', text: 'عم نرسم الكشف بدقة عالية…' }), el('div', { class: 'progress', style: { background: 'var(--surface-3)' } }, bar)) });
        p = K.pdf.fromStatement(st, A, { appName: appName(), iconUrl: iconUrl(192), onProgress: function (f) { bar.style.width = Math.round(f * 100) + '%'; } }).then(function (blob) {
          prog.close();
          return K.deliver(blob, fname + '.pdf');
        }, function (e) { prog.close(); throw e; });
      } else if (kind === 'print') {
        printStatement(st, A);
        p = Promise.resolve();
      }
    } catch (e) {
      K.toast('صار خطأ بالتصدير: ' + e.message, 'error');
      return;
    }
    p.then(function () {
      st.exportedAt = Date.now();
      var hasImgs = !st.imagesDeleted && st.pages.some(function (x) { return !x.imageGone; });
      if (o.deleteImages !== false && hasImgs && kind !== 'print') {
        return deleteImages(st).then(function () { K.toast('تم التصدير ✓ وانحذفت صور الكشف من جهازك للخصوصية', 'success', 5000); });
      }
      return saveStatement(st).then(function () { if (kind !== 'print') K.toast('تم التصدير ✓', 'success'); });
    }).catch(function (e) {
      if (e && e.name === 'AbortError') return;
      if (K.nat && K.nat.on && K.nat.isCancel(e)) return;
      K.toast(kind === 'share' ? 'ما تمت المشاركة' : 'صار خطأ بالتصدير: ' + ((e && e.message) || ''), kind === 'share' ? 'warn' : 'error');
    });
  }

  function toCsv(st, A) {
    function q(v) {
      var s = String(v == null ? '' : v);
      if (/^[=+@\-]/.test(s) && !/^-?\d/.test(s)) s = "'" + s;
      return /[",\n\r]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
    }
    var lines = [['#'].concat(st.columns.map(function (c) { return c.label; }), ['ملاحظات']).map(q).join(',')];
    A.rows.forEach(function (r) {
      var f = A.flags[r.id];
      var notes = [];
      if (f.dup) notes.push('مكرر');
      if (Object.keys(f.bad).length) notes.push('هوية غير صحيحة');
      if (Object.keys(f.unclear).length) notes.push('تحتاج مراجعة');
      lines.push([f.n].concat(st.columns.map(function (c) { return r.cells[c.id] || ''; }), [notes.join(' - ')]).map(q).join(','));
    });
    return new Blob(['﻿' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8' });
  }

  function printStatement(st, A) {
    var area = document.getElementById('print-area');
    K.clear(area);
    var thead = el('tr', {}, el('th', { text: '#' }), st.columns.map(function (c) { return el('th', { text: c.label }); }));
    var tbody = el('tbody', {});
    A.rows.forEach(function (r) {
      var f = A.flags[r.id];
      tbody.appendChild(el('tr', { class: f.dup ? 'dup' : '' }, el('td', { text: String(f.n) }), st.columns.map(function (c) {
        var isLtr = c.kind === 'national_id' || c.kind === 'phone';
        return el('td', { class: (f.bad[c.id] ? 'bad ' : '') + (isLtr ? 'ltr' : ''), text: r.cells[c.id] || '' });
      })));
    });
    area.appendChild(el('div', { class: 'p-head' },
      el('div', {}, el('h2', { text: st.title }), el('div', { text: A.counts.rows + ' صف • مكرر: ' + A.counts.dupRows + ' • هوية غير صحيحة: ' + A.counts.badRows })),
      el('div', { text: K.fmtDate(new Date()) })));
    area.appendChild(el('table', {}, el('thead', {}, thead), tbody));
    area.appendChild(el('div', { class: 'p-foot' }, el('div', { text: appName() + ' — محوّل الكشوفات الذكي' }), el('div', { class: 'ltr', style: { 'text-align': 'center' }, text: 'Py | Sohaib' })));
    setTimeout(function () { window.print(); }, 60);
  }

  // ================================================================
  // المحفظة
  // ================================================================
  function viewWallet() {
    var app = S.app || {};
    var ledger = el('div', {}, skeletonList(3));
    var code = el('input', { class: 'input code-input', placeholder: 'XXXX-XXXX-XXXX', autocomplete: 'off', autocapitalize: 'characters', spellcheck: 'false', maxlength: '20', inputmode: 'text' });
    code.addEventListener('input', function () {
      var raw = K.digits(code.value).toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 12);
      var f = raw.match(/.{1,4}/g);
      code.value = f ? f.join('-') : '';
    });
    var btn = el('button', { class: 'btn btn-primary btn-block btn-lg', type: 'submit' }, ic('ticket'), 'اشحن الرصيد');
    var form = el('form', { class: 'stack' }, code, btn);
    form.addEventListener('submit', function (e) {
      e.preventDefault();
      if (code.value.replace(/-/g, '').length < 8) { K.toast('اكتب الكود كامل', 'warn'); return; }
      K.busy(btn, true);
      K.api.post('redeem', { code: code.value }).then(function (r) {
        setBalance(r.pages);
        code.value = '';
        K.toast(r.message, 'success', 4500);
        loadLedger();
        var big = document.querySelector('.wallet-card .big b');
        if (big) big.textContent = K.num(r.pages);
      }).catch(function (e2) { K.toast(e2.message, 'error'); }).then(function () { K.busy(btn, false); });
    });
    var kinds = { code: ['ticket', 'شحن بكود'], scan: ['scan', 'قراءة صفحة'], signup: ['gift', 'هدية التسجيل'], admin: ['star', 'من الإدارة'], refund: ['refresh', 'استرجاع'] };
    function loadLedger() {
      K.api.get('ledger').then(function (r) {
        setBalance(r.pages);
        K.clear(ledger);
        if (!r.items.length) { ledger.appendChild(el('p', { class: 'muted small center', style: { padding: '14px' }, text: 'ما في حركات لسا' })); return; }
        r.items.forEach(function (it) {
          var k = kinds[it.kind] || ['info', it.kind];
          var plus = Number(it.delta) > 0;
          ledger.appendChild(el('div', { class: 'ledger-item' },
            el('div', { class: 'ic ' + (plus ? 'plus' : 'minus') }, ic(k[0])),
            el('div', { class: 'grow' }, el('div', { style: { 'font-weight': '600' }, text: it.note || k[1] }), el('div', { class: 'tiny muted', text: K.fmtDate(it.created_at) + (it.kind === 'code' && it.ref ? ' • ' + it.ref : '') })),
            el('div', { class: 'd ' + (plus ? 'plus' : 'minus'), dir: 'ltr', text: (plus ? '+' : '') + it.delta })));
        });
      }).catch(function (e) { K.clear(ledger).appendChild(el('p', { class: 'muted small', text: e.message })); });
    }
    var body = el('div', { class: 'stack lg' },
      el('div', { class: 'wallet-card' },
        el('div', { class: 'row between' }, el('div', { class: 'lbl', text: 'رصيدك الحالي' }), el('div', { class: 'chip-gold' })),
        el('div', { class: 'big' }, el('b', { 'data-balance': '1', text: K.num(S.user.pages) }), el('small', { text: 'صفحة' })),
        el('div', { class: 'foot' }, el('div', {}, el('div', { style: { 'font-weight': '700', color: '#fff' }, text: S.user.name }), el('div', { class: 'ltr', text: S.user.phone })), el('div', { text: appName() }))),
      el('div', { class: 'card' }, el('div', { class: 'card-title' }, ic('ticket'), 'شحن بكود'), form),
      (app.prices && app.prices.length) || app.contact ? el('div', { class: 'card stack' },
        el('div', { class: 'card-title' }, ic('coins'), 'الأسعار'),
        app.prices && app.prices.length ? el('div', { class: 'price-list' }, app.prices.map(function (p) { return el('div', { class: 'price-row' }, ic('check', 'sm'), p); })) : null,
        app.contact ? el('a', { class: 'btn btn-soft btn-block', href: waLink(app.contact, 'السلام عليكم، بدي أشتري كود صفحات لتطبيق ' + appName()), target: '_blank', rel: 'noopener' }, ic('msg'), 'اطلب كود عبر واتساب') : null) : null,
      el('div', { class: 'card' }, el('div', { class: 'card-title' }, ic('list'), 'سجل الحركات'), ledger));
    render(layout({ nav: 'wallet', body: body, narrow: true }));
    loadLedger();
  }

  // ================================================================
  // الإعدادات
  // ================================================================
  function viewSettings() {
    var u = S.user;
    var theme = K.getTheme();
    var seg = el('div', { class: 'seg' });
    [['auto', 'تلقائي'], ['light', 'فاتح'], ['dark', 'داكن']].forEach(function (t) {
      seg.appendChild(el('button', { type: 'button', class: theme === t[0] ? 'is-active' : '', on: { click: function () { K.setTheme(t[0]); viewSettings(); } } }, t[1]));
    });
    var ttl = el('select', { class: 'select' });
    [[6, 'بعد 6 ساعات'], [24, 'بعد 24 ساعة'], [72, 'بعد 3 أيام'], [168, 'بعد أسبوع']].forEach(function (o) { ttl.appendChild(el('option', { value: String(o[0]), text: o[1] })); });
    ttl.value = String(K.pref.get('imgTTL', 24));
    ttl.addEventListener('change', function () { K.pref.set('imgTTL', Number(ttl.value)); purgeOldImages(); K.toast('تم الحفظ ✓', 'success'); });

    function mi(icon, title, sub, fn, cls) {
      return el('button', { class: 'menu-item ' + (cls || ''), type: 'button', on: { click: fn } }, el('div', { class: 'ic' }, ic(icon)), el('div', { class: 'grow' }, el('b', { text: title }), sub ? el('span', { text: sub }) : null), ic('chevl', 'sm'));
    }
    var body = el('div', { class: 'stack lg' },
      el('div', { class: 'card profile-card' },
        el('div', { class: 'avatar', text: (u.name || '?').trim().charAt(0) }),
        el('div', { class: 'grow' }, el('b', { style: { 'font-size': '17px' }, text: u.name }), el('div', { class: 'muted small ltr', style: { 'text-align': 'right' }, text: u.phone }), el('div', { class: 'tiny muted', text: 'عضو من ' + K.fmtDay(u.created_at) + ' • استخدمت ' + K.num(u.pages_used) + ' صفحة' })),
        el('button', { class: 'btn btn-icon btn-ghost', type: 'button', 'aria-label': 'تعديل الاسم', on: { click: editName } }, ic('edit'))),
      el('div', { class: 'card stack' }, el('div', { class: 'card-title' }, ic('sun'), 'المظهر'), seg),
      el('div', { class: 'card stack' }, el('div', { class: 'card-title' }, ic('shield'), 'الخصوصية'),
        el('div', { class: 'field' }, el('label', { text: 'حذف صور الصفحات المقروءة تلقائياً' }), ttl)),
      el('div', { class: 'menu' },
        mi('wallet', 'المحفظة والأكواد', 'رصيدك: ' + K.num(u.pages) + ' صفحة', function () { K.go('/wallet'); }),
        mi('lock', 'تغيير كلمة السر', null, changePassword),
        S.installEvt ? mi('install', 'تثبيت التطبيق', 'على شاشة جوالك', doInstall) : null,
        K.remote ? mi('link', 'السيرفر', K.serverUrl().replace(/^https?:\/\//, ''), serverSheet) : null,
        u.role === 'admin' ? mi('cpu', 'لوحة الإدارة', null, function () { var url = (K.remote ? K.serverUrl() + '/' : '') + 'admin.php'; location.href = url; }) : null),
      el('div', { class: 'menu' },
        mi('trash', 'مسح كل الكشوفات من الجهاز', 'ما بأثر على رصيدك', clearLocal, 'danger'),
        mi('logout', 'تسجيل الخروج', null, logout, 'danger')),
      el('p', { class: 'center tiny muted', text: appName() + ' • الإصدار ' + ((S.app && S.app.version) || '') }));
    render(layout({ nav: 'settings', body: body, narrow: true }));
  }

  function editName() {
    K.prompt({ title: 'الاسم', value: S.user.name, ok: 'حفظ' }).then(function (v) {
      if (v === null || !v.trim()) return;
      K.api.post('profile', { name: v.trim() }).then(function (r) { S.user = r.user; K.toast('تم الحفظ ✓', 'success'); viewSettings(); }).catch(function (e) { K.toast(e.message, 'error'); });
    });
  }

  function changePassword() {
    var oldP = K.passwordInput({ autocomplete: 'current-password' });
    var newP = K.passwordInput({ autocomplete: 'new-password', placeholder: '6 أحرف أو أكثر' });
    K.sheet({
      title: 'تغيير كلمة السر',
      body: el('div', { class: 'stack' }, el('div', { class: 'field' }, el('label', { text: 'كلمة السر الحالية' }), oldP.node), el('div', { class: 'field' }, el('label', { text: 'كلمة السر الجديدة' }), newP.node)),
      actions: [{ label: 'إلغاء' }, { label: 'حفظ', primary: true, onClick: function () {
        return K.api.post('password', { old: oldP.input.value, new: newP.input.value }).then(function (r) { K.toast(r.message, 'success'); });
      } }]
    });
  }

  /** تغيير رابط السيرفر (تطبيق الجوال) */
  function serverSheet() {
    var inp = el('input', { class: 'input ltr', type: 'url', value: K.serverUrl(), placeholder: 'https://example.com/madix-convert', autocomplete: 'off', spellcheck: 'false' });
    K.sheet({
      title: 'السيرفر',
      body: el('div', { class: 'stack' }, inp),
      actions: [{ label: 'إلغاء' }, { label: 'حفظ', primary: true, onClick: function () {
        var old = K.serverUrl();
        return K.setServer(inp.value).then(function () {
          if (K.serverUrl() !== old) { K.api.setToken(''); S.user = null; location.hash = '#/'; location.reload(); }
        });
      } }]
    });
  }

  function clearLocal() {
    K.confirm('رح تنحذف كل الكشوفات والصور المحفوظة على هذا الجهاز. ما في تراجع.', { danger: true, ok: 'مسح الكل' }).then(function (ok) {
      if (!ok) return;
      listStatements().then(function (list) { return Promise.all(list.map(deleteStatement)); }).then(function () { K.toast('تم المسح ✓', 'success'); K.go('/'); });
    });
  }

  function logout() {
    K.confirm('تسجيل الخروج من الحساب؟ كشوفاتك بتضل محفوظة على الجهاز.', { ok: 'خروج' }).then(function (ok) {
      if (!ok) return;
      K.api.post('logout', {}).catch(function () { }).then(function () { K.api.setToken(''); S.user = null; K.go('/', true); });
    });
  }

  // ================================================================
  // التشغيل
  // ================================================================
  function guard(fn) {
    return function (p) {
      if (!S.user) { viewAuth(); return; }
      fn(p || {});
    };
  }
  K.route('/', guard(viewHome));
  K.route('/statements', guard(viewStatements));
  K.route('/s/:id', guard(viewStatement));
  K.route('/s/:id/review', guard(viewReview));
  K.route('/s/:id/dupes', guard(viewDupes));
  K.route('/wallet', guard(viewWallet));
  K.route('/settings', guard(viewSettings));
  K.route('/new', guard(function () { K.go('/', true); setTimeout(function () { startNew('camera'); }, 50); }));

  K.api.onAuthLost = function () {
    if (K.remote) K.api.setToken('');
    if (!S.user) return;
    S.user = null;
    K.toast('انتهت الجلسة، سجّل دخولك مرة ثانية', 'warn');
    viewAuth('login');
  };

  function bootError(e) {
    K.hideSplash();
    render(el('div', { class: 'auth' }, authHero(), el('div', { class: 'auth-card stack' },
      el('div', { class: 'notice red' }, ic('alert'), el('div', {}, el('b', { text: 'تعذّر الاتصال' }), el('div', { class: 'small', text: e.message }))),
      el('button', { class: 'btn btn-primary btn-block', type: 'button', on: { click: function () { location.reload(); } } }, ic('refresh'), 'إعادة المحاولة'),
      el('button', { class: 'btn btn-block', type: 'button', on: { click: offlineMode } }, ic('files'), 'فتح الكشوفات المحفوظة'),
      K.remote ? el('button', { class: 'btn btn-ghost btn-block', type: 'button', on: { click: function () { viewServerSetup(true); } } }, ic('link'), 'تغيير رابط السيرفر') : null), K.footer()));
  }

  /** ربط التطبيق بالسيرفر (أول تشغيل إذا الرابط مش مثبت بالتطبيق) */
  function viewServerSetup(fromError) {
    K.hideSplash();
    var err = el('div', { class: 'form-error', hidden: true });
    var inp = el('input', { class: 'input ltr', type: 'url', inputmode: 'url', value: K.serverUrl(), placeholder: 'https://example.com/madix-convert', autocomplete: 'off', spellcheck: 'false', autocapitalize: 'off' });
    var btn = el('button', { class: 'btn btn-primary btn-block btn-lg', type: 'submit' }, ic('link'), 'اتصال');
    var form = el('form', { class: 'stack' }, el('div', { class: 'field' }, el('label', { text: 'رابط السيرفر' }), inp), err, btn);
    form.addEventListener('submit', function (e) {
      e.preventDefault();
      err.hidden = true;
      K.busy(btn, true);
      K.setServer(inp.value).then(function () { K.api.setToken(''); location.hash = '#/'; location.reload(); })
        .catch(function (x) { err.textContent = x.message; err.hidden = false; }).then(function () { K.busy(btn, false); });
    });
    render(el('div', { class: 'auth' }, authHero(), el('div', { class: 'auth-card stack lg' }, form), K.footer()));
    void fromError;
  }

  // زر الرجوع بالجوال: يسكّر النوافذ، وبعدين يرجع للشاشة الأم، وبالرئيسية ضغطتين للخروج
  function parentRoute(h) {
    var m;
    if ((m = h.match(/^#\/s\/([^/]+)\/dupes$/))) return '/s/' + m[1] + '/review';
    if (/^#\/s\/[^/]+(\/review)?$/.test(h)) return '/statements';
    if (h === '#/' || h === '#' || h === '') return null;
    return '/';
  }
  if (K.nat && K.nat.on) {
    var lastBack = 0;
    K.nat.onBack(function () {
      if (document.querySelector('.scanner')) return;
      var ed = document.querySelector('.editor .editor-top .btn-icon');
      if (ed) { ed.click(); return; }
      if (K.closeTopSheet()) return;
      var parent = S.user ? parentRoute(location.hash) : null;
      if (parent) { K.go(parent); return; }
      if (Date.now() - lastBack < 1800) { K.nat.exit(); return; }
      lastBack = Date.now();
      K.toast('اضغط رجوع مرة ثانية للخروج', 'info', 1800);
    });
  }

  function offlineMode() {
    var last = K.pref.get('lastUser', null);
    if (!last) { K.toast('ما في كشوفات محفوظة لهذا الجهاز', 'warn'); return; }
    S.user = last;
    S.app = S.app || { name: 'MADIX Convert', models: [], prices: [] };
    K.startRouter();
  }

  function boot() {
    K.setTheme(K.getTheme());
    K.registerSW();
    if (K.nat && K.nat.on) K.nat.hideSplash();
    if (K.remote && !K.serverUrl()) { viewServerSetup(); return; }
    K.api.get('me').then(function (r) {
      S.app = r.app;
      S.user = r.user;
      S.setup = !!r.setup;
      if (S.user) K.pref.set('lastUser', S.user);
      document.title = appName() + ' — محوّل الكشوفات الذكي';
      if (S.user) purgeOldImages();
      var go = new URLSearchParams(location.search).get('go');
      if (S.user && go && !location.hash) {
        if (go === 'wallet') location.hash = '#/wallet';
        else if (go === 'new') location.hash = '#/';
      }
      K.startRouter();
      K.hideSplash();
    }).catch(bootError);
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
})();
