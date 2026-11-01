/* ==========================================================
   MADIX Convert — لوحة الإدارة (admin.php)
   نظرة عامة، نماذج الذكاء الاصطناعي، مفاتيح API، الأكواد وطباعة الكروت، المستخدمين، الإعدادات، الأيقونة، السجلات
   Py | Sohaib
   ========================================================== */
(function () {
  'use strict';
  var K = window.K, el = K.el, ic = K.icon;
  var root = document.getElementById('app');
  var S = { app: null, user: null, setup: false, settings: null };
  var KNOWN_MODELS = [
    ['claude-opus-5-5', 'Claude Opus 5.5 — الأدق', 4, 20],
    ['claude-sonnet-5-5', 'Claude Sonnet 5.5 — متوازن', 2, 10],
    ['claude-haiku-5-5', 'Claude Haiku 5.5 — اقتصادي', 0.1, 0.5],
    ['claude-fable-5-1', 'Claude Fable 5.1 — الأقوى والأغلى', 10, 50],
    ['claude-opus-5', 'Claude Opus 5', 5, 25],
    ['claude-sonnet-5', 'Claude Sonnet 5', 2, 10]
  ];
  var EFFORTS = [['low', 'منخفض (أسرع وأرخص)'], ['medium', 'متوسط (منصوح)'], ['high', 'عالي (أدق للخط الصعب)'], ['xhigh', 'عالي جداً'], ['max', 'أقصى']];
  var NAV = [
    ['overview', 'نظرة عامة', 'chart'],
    ['android', 'تطبيق أندرويد', 'install'],
    ['models', 'نماذج الذكاء', 'cpu'],
    ['keys', 'مفاتيح API', 'key'],
    ['codes', 'الأكواد والكروت', 'ticket'],
    ['users', 'المستخدمين', 'users'],
    ['settings', 'الإعدادات', 'sliders'],
    ['logs', 'السجلات', 'list']
  ];

  function iconUrl(s) { return 'index.php?asset=icon&s=' + s + '&v=' + encodeURIComponent((S.app && S.app.icon_v) || '0'); }
  function money(v) { return Number(v || 0).toLocaleString('en-US', { maximumFractionDigits: 2 }); }
  function usd(v) { return '$' + Number(v || 0).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 4 }); }
  function render(node) { K.closeAllSheets(); K.swap(root, node); }
  function field(label, input) { return el('div', { class: 'field' }, el('label', { text: label }), input); }
  function sw(label, checked, sub) {
    var i = el('input', { type: 'checkbox', checked: !!checked });
    void sub;
    return { input: i, node: el('label', { class: 'switch' }, el('b', { class: 'small', text: label }), i, el('span', { class: 'track' })) };
  }
  function selectOf(opts, val) {
    var s = el('select', { class: 'select' });
    opts.forEach(function (o) { s.appendChild(el('option', { value: o[0], text: o[1] })); });
    s.value = val;
    return s;
  }
  function loading() { return el('div', { class: 'stack' }, el('div', { class: 'skeleton', style: { height: '90px' } }), el('div', { class: 'skeleton', style: { height: '220px' } })); }
  function errBox(e) { return el('div', { class: 'notice red' }, ic('alert'), el('div', { text: e.message })); }

  // ---------- الهيكل ----------
  function shell(active, title, sub, content, actions) {
    function link(n, tabs) {
      return el('button', { class: 'adm-link' + (active === n[0] ? ' is-active' : ''), type: 'button', on: { click: function () { K.go('/' + n[0]); } } }, ic(n[2]), n[1]);
    }
    var side = el('aside', { class: 'adm-side' },
      el('div', { class: 'brand' }, el('img', { src: iconUrl(192), alt: '' }), el('div', {}, K.wordmark((S.app && S.app.name) || 'MADIX Convert'), el('small', { text: 'لوحة الإدارة' }))),
      NAV.map(function (n) { return link(n); }),
      el('div', { class: 'spacer' }),
      el('a', { class: 'adm-link', href: 'index.php' }, ic('home'), 'فتح التطبيق'),
      el('button', { class: 'adm-link', type: 'button', on: { click: themeToggle } }, ic(K.getTheme() === 'dark' ? 'sun' : 'moon'), 'تبديل المظهر'),
      el('button', { class: 'adm-link', type: 'button', on: { click: logout } }, ic('logout'), 'تسجيل الخروج'));
    var tabs = el('nav', { class: 'adm-tabs' }, NAV.map(function (n) { return link(n, true); }), el('a', { class: 'adm-link', href: 'index.php' }, ic('home'), 'التطبيق'), el('button', { class: 'adm-link', type: 'button', on: { click: logout } }, ic('logout'), 'خروج'));
    return el('div', { class: 'adm' }, side,
      el('div', { style: { 'min-width': '0' } }, tabs,
        el('main', { class: 'adm-main' },
          el('div', { class: 'page-head' }, el('div', { class: 'grow' }, el('h1', { text: title }), sub ? el('div', { class: 'sub', text: sub }) : null), actions || null),
          content,
          K.footer(S.app && S.app.footer))));
  }
  function themeToggle() {
    var t = K.getTheme();
    var dark = t === 'dark' || (t === 'auto' && window.matchMedia && matchMedia('(prefers-color-scheme: dark)').matches);
    K.setTheme(dark ? 'light' : 'dark');
    K.resolve();
  }
  function logout() {
    K.api.post('logout', {}).catch(function () { }).then(function () { S.user = null; viewLogin(); });
  }

  // ---------- الإعداد الأول والدخول ----------
  function authWrap(title, sub, form) {
    return el('div', { class: 'auth' },
      el('div', { class: 'auth-hero' }, el('div', { class: 'auth-hero-in' }, el('img', { src: iconUrl(512), alt: '' }), el('h1', { text: title }), el('p', { text: sub }))),
      el('div', { class: 'auth-card stack lg' }, form),
      K.footer(S.app && S.app.footer));
  }

  function viewSetup() {
    var err = el('div', { class: 'form-error', hidden: true });
    var name = el('input', { class: 'input', placeholder: 'اسم المدير', required: true });
    var phone = el('input', { class: 'input ltr', type: 'tel', inputmode: 'tel', placeholder: '059xxxxxxx', required: true });
    var pw = K.passwordInput({ autocomplete: 'new-password', placeholder: '6 أحرف أو أكثر' });
    var btn = el('button', { class: 'btn btn-primary btn-block btn-lg', type: 'submit' }, ic('check'), 'إنشاء حساب المدير');
    var form = el('form', { class: 'stack' },
      field('الاسم', name), field('رقم الجوال (للدخول)', phone), field('كلمة السر', pw.node), err, btn);
    form.addEventListener('submit', function (e) {
      e.preventDefault();
      err.hidden = true;
      K.busy(btn, true);
      K.api.post('setup', { name: name.value, phone: phone.value, password: pw.input.value }).then(function (r) {
        S.user = r.user; S.setup = false;
        K.toast('تم إعداد النظام ✓ الخطوة التالية: أضف مفتاح API', 'success', 5000);
        K.go('/keys', true);
        K.resolve();
      }).catch(function (e2) { err.textContent = e2.message; err.hidden = false; }).then(function () { K.busy(btn, false); });
    });
    render(authWrap('إعداد ' + ((S.app && S.app.name) || 'MADIX Convert'), 'لوحة الإدارة — أول تشغيل', form));
  }

  function viewLogin(msg) {
    var err = el('div', { class: 'form-error', hidden: !msg, text: msg || '' });
    var phone = el('input', { class: 'input ltr', type: 'tel', inputmode: 'tel', placeholder: '059xxxxxxx', autocomplete: 'username' });
    var pw = K.passwordInput({});
    var btn = el('button', { class: 'btn btn-primary btn-block btn-lg', type: 'submit' }, ic('lock'), 'دخول للوحة الإدارة');
    var form = el('form', { class: 'stack' }, field('رقم الجوال', phone), field('كلمة السر', pw.node), err, btn, el('a', { class: 'btn btn-ghost btn-sm', href: 'index.php' }, 'الرجوع للتطبيق'));
    form.addEventListener('submit', function (e) {
      e.preventDefault();
      err.hidden = true;
      K.busy(btn, true);
      K.api.post('login', { phone: phone.value, password: pw.input.value }).then(function (r) {
        if (r.user.role !== 'admin') { err.textContent = 'هذا الحساب مش حساب مدير'; err.hidden = false; return K.api.post('logout', {}); }
        S.user = r.user;
        K.go('/overview', true);
        K.resolve();
      }).catch(function (e2) { err.textContent = e2.message; err.hidden = false; }).then(function () { K.busy(btn, false); });
    });
    render(authWrap('لوحة الإدارة', (S.app && S.app.name) || 'MADIX Convert', form));
  }

  // ---------- نظرة عامة ----------
  function viewOverview() {
    var box = el('div', {}, loading());
    render(shell('overview', 'نظرة عامة', 'أداء التطبيق والإيرادات والتكلفة', box,
      el('a', { class: 'btn btn-sm', href: 'admin.php?api=backup' }, ic('database'), 'نسخة احتياطية')));
    K.api.get('stats').then(function (s) {
      K.clear(box);
      var cur = s.currency || '₪';
      if (s.mock) box.appendChild(el('div', { class: 'notice amber', style: { 'margin-bottom': '14px' } }, ic('alert'), el('div', { text: 'وضع التجربة مفعّل (MADIX_MOCK_AI)' })));
      if (!s.ai_ready) box.appendChild(el('button', { class: 'notice amber', type: 'button', style: { width: '100%', border: '0', 'margin-bottom': '14px', 'text-align': 'start', cursor: 'pointer' }, on: { click: function () { K.go('/keys'); } } }, ic('key'), el('div', { class: 'grow' }, el('b', { text: 'خدمة القراءة غير مفعّلة — أضف مفتاح API' })), ic('chevl')));
      function kpi(icon, k, v, sub) { return el('div', { class: 'kpi' }, el('div', { class: 'k' }, ic(icon), k), el('div', { class: 'v', text: v }), sub ? el('div', { class: 's', text: sub }) : null); }
      box.appendChild(el('div', { class: 'kpis' },
        kpi('users', 'المستخدمين', K.num(s.users.total), '+' + s.users.today + ' آخر 24 ساعة'),
        kpi('scan', 'صفحات مقروءة', K.num(s.scans.total), s.scans.today + ' اليوم • ' + s.scans.week + ' هالأسبوع'),
        kpi('sheet', 'صفوف مستخرجة', K.num(s.scans.rows), 'متوسط القراءة ' + (s.scans.avg_ms / 1000).toFixed(1) + ' ث'),
        kpi('ticket', 'أكواد مباعة', K.num(s.codes.used), K.num(s.codes.new) + ' كود جاهز للبيع'),
        kpi('coins', 'الإيرادات', money(s.codes.revenue) + ' ' + cur, 'آخر 30 يوم: ' + money(s.codes.revenue_month) + ' ' + cur),
        kpi('cpu', 'تكلفة الذكاء الاصطناعي', usd(s.cost.month), 'آخر 30 يوم • للصفحة ' + usd(s.cost.per_page)),
        kpi('wallet', 'رصيد المستخدمين', K.num(s.users.balance), 'صفحة غير مستخدمة'),
        kpi('alert', 'قراءات فاشلة', K.num(s.scans.failed_week), 'آخر 7 أيام (ما انخصمت)')));
      var max = Math.max.apply(null, s.series.map(function (d) { return d.scans; }).concat([1]));
      var bars = el('div', { class: 'bars' });
      s.series.forEach(function (d) {
        bars.appendChild(el('div', { class: 'b' }, el('i', { 'data-v': d.scans + ' صفحة', style: { height: Math.max(2, (d.scans / max) * 100) + '%' } }), el('span', { text: d.d.slice(8) })));
      });
      box.appendChild(el('div', { class: 'card', style: { 'margin-top': '14px' } }, el('div', { class: 'card-title' }, ic('chart'), 'الصفحات المقروءة — آخر 14 يوم'), bars));
      var top = el('tbody', {});
      s.top_users.forEach(function (u) { top.appendChild(el('tr', {}, el('td', { text: u.name }), el('td', { class: 'ltr', text: u.phone }), el('td', { text: K.num(u.pages_used) }), el('td', { text: K.num(u.pages) }))); });
      box.appendChild(el('div', { class: 'grid-2', style: { 'margin-top': '14px' } },
        el('div', { class: 'card pad-0' }, el('div', { class: 'card-title', style: { padding: '16px 16px 4px' } }, ic('star'), 'أكثر المستخدمين'), el('div', { style: { overflow: 'auto' } }, el('table', { class: 'dt' }, el('thead', {}, el('tr', {}, el('th', { text: 'الاسم' }), el('th', { text: 'الجوال' }), el('th', { text: 'استخدم' }), el('th', { text: 'رصيد' }))), top))),
        el('div', { class: 'card' }, el('div', { class: 'card-title' }, ic('cpu'), 'السيرفر'),
          el('div', { class: 'env-list' },
            envItem('PHP', s.env.php, true),
            envItem('cURL', s.env.curl ? 'مفعّل' : 'غير مفعّل', s.env.curl),
            envItem('GD (الصور)', s.env.gd ? 'مفعّل' : 'غير مفعّل', s.env.gd),
            envItem('التشفير', s.env.sodium ? 'sodium' : 'openssl', true),
            envItem('حجم الرفع', s.env.upload_max, true),
            envItem('HTTPS', s.env.https ? 'آمن ✓' : 'غير مفعّل', s.env.https)))));
    }).catch(function (e) { K.clear(box).appendChild(errBox(e)); });
  }
  function envItem(k, v, ok) { return el('div', {}, el('div', { class: 'tiny muted', text: k }), el('b', { style: { color: ok ? 'inherit' : 'var(--red)' }, text: String(v) })); }

  // ---------- نماذج الذكاء الاصطناعي ----------
  function viewModels() {
    var box = el('div', {}, loading());
    render(shell('models', 'نماذج الذكاء الاصطناعي', 'النماذج اللي بتقرأ الكشوفات — أضف، عدّل، واختار الافتراضي', box,
      el('button', { class: 'btn btn-primary btn-sm', type: 'button', on: { click: function () { modelForm(null); } } }, ic('plus'), 'إضافة نموذج')));
    K.api.get('models').then(function (r) {
      K.clear(box);
      var grid = el('div', { class: 'grid-2' });
      r.items.forEach(function (m) {
        grid.appendChild(el('div', { class: 'card model-card' },
          el('div', { class: 'row' }, el('div', { class: 'grow' }, el('b', { style: { 'font-size': '16px' }, text: m.label }), el('div', { class: 'mid', text: m.model_id })),
            Number(m.is_default) ? el('span', { class: 'badge green' }, ic('star'), 'افتراضي') : null,
            el('span', { class: 'badge ' + (Number(m.is_active) ? 'blue' : 'red'), text: Number(m.is_active) ? 'مفعّل' : 'معطّل' })),
          m.description ? el('div', { class: 'small muted', text: m.description }) : null,
          el('div', { class: 'row wrap' },
            el('span', { class: 'badge' }, 'إدخال $' + money(m.price_in) + ' / إخراج $' + money(m.price_out)),
            el('span', { class: 'badge' }, 'الجهد: ' + (m.effort || 'افتراضي')),
            el('span', { class: 'badge' }, m.page_cost + ' صفحة / قراءة'),
            Number(m.use_fallbacks) ? el('span', { class: 'badge violet' }, 'نموذج بديل عند الرفض') : null),
          el('div', { class: 'tiny muted', text: 'استُخدم ' + K.num(m.uses) + ' مرة • التكلفة ' + usd(m.cost) }),
          el('div', { class: 'row wrap' },
            el('button', { class: 'btn btn-sm', type: 'button', on: { click: function () { modelForm(m); } } }, ic('edit'), 'تعديل'),
            el('button', { class: 'btn btn-sm btn-soft', type: 'button', on: { click: function (e) { testModel(m.model_id, e.currentTarget); } } }, ic('bolt'), 'اختبار'),
            !Number(m.is_default) ? el('button', { class: 'btn btn-sm', type: 'button', on: { click: function () { saveModel(Object.assign({}, m, { is_default: true, is_active: true })); } } }, ic('star'), 'تعيين افتراضي') : null,
            el('button', { class: 'btn btn-sm btn-danger', type: 'button', on: { click: function () { delModel(m); } } }, ic('trash')))));
      });
      box.appendChild(grid);
    }).catch(function (e) { K.clear(box).appendChild(errBox(e)); });
  }

  function testModel(modelId, btn) {
    K.busy(btn, true);
    K.api.post('ai_test', { model_id: modelId }).then(function (r) {
      K.toast(r.result.message + ' (' + (r.result.ms / 1000).toFixed(1) + ' ث)', r.result.ok ? 'success' : 'error', 7000);
    }).catch(function (e) { K.toast(e.message, 'error'); }).then(function () { K.busy(btn, false); });
  }

  function saveModel(m) {
    return K.api.post('model_save', {
      id: m.id || 0, model_id: m.model_id, label: m.label, description: m.description || '',
      price_in: m.price_in, price_out: m.price_out, effort: m.effort, max_tokens: m.max_tokens || 16000,
      page_cost: m.page_cost || 1, use_fallbacks: !!Number(m.use_fallbacks), is_active: !!Number(m.is_active), is_default: !!Number(m.is_default), sort: m.sort || 0
    }).then(function () { K.toast('تم الحفظ ✓', 'success'); viewModels(); }).catch(function (e) { K.toast(e.message, 'error'); throw e; });
  }

  function delModel(m) {
    K.confirm('حذف النموذج ' + m.model_id + '؟', { danger: true, ok: 'حذف' }).then(function (ok) {
      if (!ok) return;
      K.api.post('model_delete', { id: m.id }).then(function () { K.toast('تم الحذف', 'success'); viewModels(); }).catch(function (e) { K.toast(e.message, 'error'); });
    });
  }

  function modelForm(m) {
    m = m || { model_id: '', label: '', description: '', price_in: '', price_out: '', effort: 'medium', max_tokens: 16000, page_cost: 1, use_fallbacks: 1, is_active: 1, is_default: 0, sort: 0 };
    var dl = el('datalist', { id: 'known-models' }, KNOWN_MODELS.map(function (k) { return el('option', { value: k[0], text: k[1] }); }));
    var mid = el('input', { class: 'input ltr', value: m.model_id, list: 'known-models', placeholder: 'claude-opus-5-5', autocomplete: 'off', spellcheck: 'false' });
    var label = el('input', { class: 'input', value: m.label, placeholder: 'مثال: دقة عالية' });
    var desc = el('input', { class: 'input', value: m.description, placeholder: 'وصف قصير يظهر للمستخدم' });
    var pin = el('input', { class: 'input ltr', type: 'number', step: '0.01', min: '0', value: String(m.price_in) });
    var pout = el('input', { class: 'input ltr', type: 'number', step: '0.01', min: '0', value: String(m.price_out) });
    var effort = selectOf(EFFORTS, m.effort || 'medium');
    var maxT = el('input', { class: 'input ltr', type: 'number', min: '1000', max: '64000', step: '1000', value: String(m.max_tokens || 16000) });
    var cost = el('input', { class: 'input ltr', type: 'number', min: '1', max: '100', value: String(m.page_cost || 1) });
    var sort = el('input', { class: 'input ltr', type: 'number', value: String(m.sort || 0) });
    var fb = sw('نموذج بديل تلقائي عند الرفض', Number(m.use_fallbacks), 'إذا رفض النموذج الطلب، الخدمة بتعيده على نموذج بديل');
    var act = sw('مفعّل', Number(m.is_active));
    var def = sw('النموذج الافتراضي', Number(m.is_default));
    mid.addEventListener('change', function () {
      var k = KNOWN_MODELS.filter(function (x) { return x[0] === mid.value.trim(); })[0];
      if (k) {
        if (!pin.value || pin.value === '0') pin.value = k[2];
        if (!pout.value || pout.value === '0') pout.value = k[3];
        if (!label.value) label.value = k[1].split(' — ')[1] || k[1];
        if (/haiku/.test(k[0])) fb.input.checked = false;
      }
    });
    K.sheet({
      title: m.id ? 'تعديل النموذج' : 'إضافة نموذج',
      wide: true,
      body: el('div', { class: 'form-grid' }, dl,
        el('div', { class: 'full' }, field('اسم النموذج (Model ID)', mid, 'انسخه من وثائق Anthropic — مثل claude-opus-5-5. اختار من القائمة أو اكتب أي نموذج جديد.')),
        field('الاسم الظاهر للمستخدم', label),
        field('الوصف', desc),
        field('سعر الإدخال $ / مليون توكن', pin),
        field('سعر الإخراج $ / مليون توكن', pout),
        field('مستوى التفكير (Effort)', effort, 'الأعلى أدق وأبطأ وأغلى'),
        field('تكلفة القراءة على المستخدم (صفحات)', cost),
        field('حد التوكنز للإخراج', maxT, 'زيده إذا الكشوفات طويلة جداً'),
        field('الترتيب', sort),
        el('div', { class: 'full card flat', style: { padding: '2px 14px' } }, fb.node, act.node, def.node)),
      actions: [{ label: 'إلغاء' }, {
        label: 'حفظ', primary: true, icon: 'check', onClick: function () {
          return saveModel({ id: m.id, model_id: mid.value.trim(), label: label.value.trim(), description: desc.value.trim(), price_in: pin.value, price_out: pout.value, effort: effort.value, max_tokens: maxT.value, page_cost: cost.value, sort: sort.value, use_fallbacks: fb.input.checked ? 1 : 0, is_active: act.input.checked ? 1 : 0, is_default: def.input.checked ? 1 : 0 });
        }
      }]
    });
  }

  // ---------- مفاتيح API ----------
  function viewKeys() {
    var box = el('div', {}, loading());
    render(shell('keys', 'مفاتيح API', 'مفاتيح Anthropic اللي بيستخدمها التطبيق للقراءة', box,
      el('button', { class: 'btn btn-primary btn-sm', type: 'button', on: { click: function () { keyForm(null); } } }, ic('plus'), 'إضافة مفتاح')));
    K.api.get('keys').then(function (r) {
      K.clear(box);
      if (r.env_key) box.appendChild(el('div', { class: 'notice green', style: { 'margin-bottom': '14px' } }, ic('check'), el('div', { class: 'small', text: 'ANTHROPIC_API_KEY ✓' })));
      if (!r.items.length && !r.env_key) {
        box.appendChild(el('div', { class: 'empty' }, ic('key'), el('h3', { text: 'ما في مفاتيح' }),  el('button', { class: 'btn btn-primary', type: 'button', style: { 'margin-top': '14px' }, on: { click: function () { keyForm(null); } } }, ic('plus'), 'إضافة مفتاح')));
        return;
      }
      var list = el('div', { class: 'stack' });
      r.items.forEach(function (k) {
        list.appendChild(el('div', { class: 'card stack' },
          el('div', { class: 'row' }, el('div', { class: 'thumb', style: { width: '44px', height: '44px', 'border-radius': '13px', display: 'grid', 'place-items': 'center', background: 'var(--primary-soft)', color: 'var(--primary)', flex: 'none' } }, ic('key')),
            el('div', { class: 'grow' }, el('b', { text: k.label || 'مفتاح' }), el('div', { class: 'mono small muted ltr', style: { 'text-align': 'right' }, text: k.key_hint })),
            el('span', { class: 'badge ' + (Number(k.is_active) ? 'green' : 'red'), text: Number(k.is_active) ? 'مفعّل' : 'معطّل' }),
            el('span', { class: 'badge', text: 'أولوية ' + k.priority })),
          el('div', { class: 'row wrap small muted' },
            el('span', {}, '✓ ' + K.num(k.ok_count) + ' ناجح'),
            el('span', {}, '✗ ' + K.num(k.fail_count) + ' فاشل'),
            el('span', {}, 'آخر استخدام: ' + (k.last_used_at ? K.ago(k.last_used_at) : '—'))),
          k.last_error ? el('div', { class: 'notice red' }, ic('alert'), el('div', { class: 'small' }, el('b', { text: 'آخر خطأ' + (k.last_error_at ? ' (' + K.ago(k.last_error_at) + ')' : '') + ': ' }), el('span', { class: 'ltr', text: k.last_error }))) : null,
          el('div', { class: 'row wrap' },
            el('button', { class: 'btn btn-sm btn-soft', type: 'button', on: { click: function (e) { testKey(k.id, e.currentTarget); } } }, ic('bolt'), 'اختبار الاتصال'),
            el('button', { class: 'btn btn-sm', type: 'button', on: { click: function () { keyForm(k); } } }, ic('edit'), 'تعديل'),
            el('button', { class: 'btn btn-sm btn-danger', type: 'button', on: { click: function () { delKey(k); } } }, ic('trash'), 'حذف'))));
      });
      box.appendChild(list);
    }).catch(function (e) { K.clear(box).appendChild(errBox(e)); });
  }

  function testKey(id, btn) {
    K.busy(btn, true);
    K.api.post('ai_test', { key_id: id }).then(function (r) {
      K.toast(r.result.message, r.result.ok ? 'success' : 'error', 8000);
      viewKeys();
    }).catch(function (e) { K.toast(e.message, 'error'); }).then(function () { K.busy(btn, false); });
  }

  function keyForm(k) {
    var label = el('input', { class: 'input', value: k ? k.label : '', placeholder: 'مثال: الحساب الرئيسي' });
    var key = el('input', { class: 'input ltr mono', type: 'password', placeholder: k ? 'اتركه فاضي إذا ما بدك تغيره' : 'sk-ant-…', autocomplete: 'off', spellcheck: 'false' });
    var pr = el('input', { class: 'input ltr', type: 'number', value: String(k ? k.priority : 0) });
    var act = sw('مفعّل', k ? Number(k.is_active) : 1);
    K.sheet({
      title: k ? 'تعديل المفتاح' : 'إضافة مفتاح API',
      body: el('div', { class: 'stack' }, field('الاسم', label), field('المفتاح', key, 'بيبدأ عادةً بـ sk-ant-'), field('الأولوية', pr, 'الرقم الأصغر بينستخدم أولاً'), act.node),
      actions: [{ label: 'إلغاء' }, {
        label: 'حفظ واختبار', primary: true, icon: 'check', onClick: function () {
          return K.api.post('key_save', { id: k ? k.id : 0, label: label.value, key: key.value.trim(), priority: pr.value, is_active: act.input.checked }).then(function (r) {
            K.toast('تم الحفظ ✓ — عم نختبر الاتصال…', 'success');
            var saved = k ? k : r.items[r.items.length - 1];
            viewKeys();
            if (saved && (key.value.trim() || !k)) {
              K.api.post('ai_test', { key_id: saved.id }).then(function (t) { K.toast(t.result.message, t.result.ok ? 'success' : 'error', 8000); viewKeys(); }).catch(function () { });
            }
          });
        }
      }]
    });
  }

  function delKey(k) {
    K.confirm('حذف المفتاح "' + (k.label || k.key_hint) + '"؟', { danger: true, ok: 'حذف' }).then(function (ok) {
      if (!ok) return;
      K.api.post('key_delete', { id: k.id }).then(function () { K.toast('تم الحذف', 'success'); viewKeys(); }).catch(function (e) { K.toast(e.message, 'error'); });
    });
  }

  // ---------- الأكواد والكروت ----------
  var codeState = { status: '', batch: '', q: '', page: 1 };
  function viewCodes() {
    var cur = (S.app && S.app.currency) || '₪';
    var count = el('input', { class: 'input ltr', type: 'number', min: '1', max: '1000', value: '24' });
    var pages = el('input', { class: 'input ltr', type: 'number', min: '1', value: '10' });
    var price = el('input', { class: 'input ltr', type: 'number', min: '0', step: '0.5', value: '10' });
    var batch = el('input', { class: 'input', placeholder: 'مثال: كروت 10 صفحات — نقطة البيع 1' });
    var gbtn = el('button', { class: 'btn btn-primary', type: 'submit' }, ic('sparkles'), 'توليد الأكواد');
    var gen = el('form', { class: 'card stack' },
      el('div', { class: 'card-title' }, ic('ticket'), 'توليد أكواد جديدة'),
      el('div', { class: 'form-grid' }, field('عدد الأكواد', count, '24 كود = صفحة A4 كاملة'), field('عدد الصفحات بكل كود', pages), field('سعر الكرت (' + cur + ')', price, 'لحساب الإيرادات'), field('اسم الدفعة', batch)),
      gbtn);
    gen.addEventListener('submit', function (e) {
      e.preventDefault();
      K.busy(gbtn, true);
      K.api.post('codes_generate', { count: count.value, pages: pages.value, price: price.value, batch: batch.value }).then(function (r) {
        batch.value = '';
        showGenerated(r);
        loadCodes();
      }).catch(function (e2) { K.toast(e2.message, 'error'); }).then(function () { K.busy(gbtn, false); });
    });
    var batches = el('div', {});
    var table = el('div', {});
    var search = el('input', { class: 'input ltr', placeholder: 'ابحث بالكود', value: codeState.q });
    var status = selectOf([['', 'كل الحالات'], ['new', 'جاهز'], ['used', 'مستخدم'], ['disabled', 'موقوف']], codeState.status);
    search.addEventListener('input', K.debounce(function () { codeState.q = search.value; codeState.page = 1; loadCodes(); }, 300));
    status.addEventListener('change', function () { codeState.status = status.value; codeState.page = 1; loadCodes(); });
    var content = el('div', { class: 'stack lg' }, gen,
      el('div', {}, el('div', { class: 'section-title' }, el('h2', { text: 'الدفعات' })), batches),
      el('div', {}, el('div', { class: 'section-title' }, el('h2', { text: 'كل الأكواد' })),
        el('div', { class: 'toolbar' }, el('div', { class: 'input-wrap grow' }, ic('search'), search), el('div', { style: { width: '160px' } }, status)),
        table));
    render(shell('codes', 'الأكواد والكروت', 'كل كود فيه عدد صفحات — بتطبعها كروت وبتبيعها زي كروت النت', content));

    function loadCodes() {
      K.clear(table).appendChild(loading());
      K.api.get('codes', { status: codeState.status, batch: codeState.batch, q: codeState.q, page: codeState.page }).then(function (r) {
        // الدفعات
        K.clear(batches);
        if (codeState.batch) batches.appendChild(el('div', { class: 'row', style: { 'margin-bottom': '10px' } }, el('span', { class: 'badge blue', text: 'فلتر الدفعة: ' + codeState.batch }), el('button', { class: 'btn btn-ghost btn-sm', type: 'button', on: { click: function () { codeState.batch = ''; codeState.page = 1; loadCodes(); } } }, ic('x'), 'إلغاء الفلتر')));
        if (!r.batches.length) batches.appendChild(el('p', { class: 'muted small', text: 'ما في دفعات لسا' }));
        else {
          var bt = el('tbody', {});
          r.batches.forEach(function (b) {
            bt.appendChild(el('tr', {},
              el('td', {}, el('button', { class: 'btn btn-ghost btn-sm', type: 'button', on: { click: function () { codeState.batch = b.batch; codeState.page = 1; loadCodes(); } } }, b.batch)),
              el('td', { text: K.num(b.n) }), el('td', { text: K.num(b.used) }), el('td', { text: b.pages + ' صفحة' }), el('td', { text: money(b.price) + ' ' + cur }),
              el('td', {}, el('div', { class: 'row' },
                el('button', { class: 'btn btn-sm btn-soft', type: 'button', on: { click: function () { printBatch(b); } } }, ic('printer'), 'طباعة'),
                el('button', { class: 'btn btn-sm', type: 'button', on: { click: function () { csvBatch(b); } } }, ic('download'), 'CSV')))));
          });
          batches.appendChild(el('div', { class: 'dt-wrap' }, el('table', { class: 'dt' }, el('thead', {}, el('tr', {}, el('th', { text: 'الدفعة' }), el('th', { text: 'العدد' }), el('th', { text: 'مستخدم' }), el('th', { text: 'الكرت' }), el('th', { text: 'السعر' }), el('th', { text: '' }))), bt)));
        }
        // الأكواد
        K.clear(table);
        var tb = el('tbody', {});
        var stName = { new: ['جاهز', 'green'], used: ['مستخدم', 'blue'], disabled: ['موقوف', 'red'] };
        r.items.forEach(function (c) {
          var s = stName[c.status] || [c.status, ''];
          tb.appendChild(el('tr', {},
            el('td', { class: 'mono ltr', style: { 'font-weight': '600' }, text: c.code }),
            el('td', { text: c.pages }),
            el('td', {}, el('span', { class: 'badge ' + s[1], text: s[0] })),
            el('td', { class: 'small', text: c.used_by_name ? c.used_by_name + ' — ' + K.fmtDate(c.used_at) : '—' }),
            el('td', { class: 'small muted', text: c.batch }),
            el('td', {}, el('div', { class: 'row' },
              el('button', { class: 'btn btn-icon sm', type: 'button', 'aria-label': 'نسخ', on: { click: function () { K.copy(c.code); } } }, ic('copy')),
              c.status === 'new' ? el('button', { class: 'btn btn-icon sm', type: 'button', title: 'إيقاف', 'aria-label': 'إيقاف', on: { click: function () { codeOp(c, 'disable'); } } }, ic('lock')) : null,
              c.status === 'disabled' ? el('button', { class: 'btn btn-icon sm', type: 'button', title: 'تفعيل', 'aria-label': 'تفعيل', on: { click: function () { codeOp(c, 'enable'); } } }, ic('check')) : null,
              c.status !== 'used' ? el('button', { class: 'btn btn-icon sm btn-danger', type: 'button', 'aria-label': 'حذف', on: { click: function () { codeOp(c, 'delete'); } } }, ic('trash')) : null))));
        });
        if (!r.items.length) tb.appendChild(el('tr', {}, el('td', { colspan: '6', class: 'center muted', style: { padding: '24px' }, text: 'ما في أكواد' })));
        table.appendChild(el('div', { class: 'dt-wrap' }, el('table', { class: 'dt' }, el('thead', {}, el('tr', {}, el('th', { text: 'الكود' }), el('th', { text: 'صفحات' }), el('th', { text: 'الحالة' }), el('th', { text: 'استخدمه' }), el('th', { text: 'الدفعة' }), el('th', { text: '' }))), tb)));
        var pagesN = Math.max(1, Math.ceil(r.total / r.per));
        if (pagesN > 1) {
          table.appendChild(el('div', { class: 'pager' },
            el('button', { class: 'btn btn-sm', type: 'button', disabled: codeState.page <= 1, on: { click: function () { codeState.page--; loadCodes(); } } }, ic('chevr'), 'السابق'),
            el('span', { class: 'small muted', text: codeState.page + ' / ' + pagesN }),
            el('button', { class: 'btn btn-sm', type: 'button', disabled: codeState.page >= pagesN, on: { click: function () { codeState.page++; loadCodes(); } } }, 'التالي', ic('chevl'))));
        }
      }).catch(function (e) { K.clear(table).appendChild(errBox(e)); });
    }
    function codeOp(c, op) {
      var go = op === 'delete' ? K.confirm('حذف الكود ' + c.code + '؟', { danger: true, ok: 'حذف' }) : Promise.resolve(true);
      go.then(function (ok) {
        if (!ok) return;
        K.api.post('code_update', { id: c.id, op: op }).then(function () { K.toast('تم ✓', 'success'); loadCodes(); }).catch(function (e) { K.toast(e.message, 'error'); });
      });
    }
    loadCodes();
  }

  function showGenerated(r) {
    var cur = (S.app && S.app.currency) || '₪';
    K.sheet({
      title: 'تم توليد ' + r.codes.length + ' كود ✓',
      wide: true,
      body: el('div', { class: 'stack' },
        el('div', { class: 'row wrap' }, el('span', { class: 'badge green', text: r.pages + ' صفحة' }), el('span', { class: 'badge', text: money(r.price) + ' ' + cur }), el('span', { class: 'badge blue', text: r.batch })),
        el('div', { class: 'code-list' }, r.codes.map(function (c) { return el('div', { class: 'code-pill', text: c }); }))),
      actions: [
        { label: 'نسخ الكل', icon: 'copy', onClick: function () { K.copy(r.codes.join('\n')); return true; } },
        { label: 'CSV', icon: 'download', onClick: function () { downloadCsv(r.codes.map(function (c) { return { code: c, pages: r.pages, price: r.price, batch: r.batch }; }), r.batch); return true; } },
        { label: 'طباعة الكروت', primary: true, icon: 'printer', onClick: function () { printCards(r.codes.map(function (c) { return { code: c, pages: r.pages, price: r.price }; })); return true; } }
      ]
    });
  }

  function fetchBatch(b) {
    return K.api.get('codes', { batch: b.batch, status: 'new', all: 1 }).then(function (r) { return r.items; });
  }
  function printBatch(b) {
    fetchBatch(b).then(function (items) {
      if (!items.length) { K.toast('ما في أكواد جاهزة بهذه الدفعة', 'warn'); return; }
      printCards(items);
    }).catch(function (e) { K.toast(e.message, 'error'); });
  }
  function csvBatch(b) {
    K.api.get('codes', { batch: b.batch, all: 1 }).then(function (r) { downloadCsv(r.items, b.batch); }).catch(function (e) { K.toast(e.message, 'error'); });
  }
  function downloadCsv(items, name) {
    var lines = ['code,pages,price,status,batch'];
    items.forEach(function (c) { lines.push([c.code, c.pages, c.price, c.status || 'new', '"' + String(c.batch || name || '').replace(/"/g, '""') + '"'].join(',')); });
    K.downloadBlob(new Blob(['﻿' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8' }), 'codes-' + String(name || 'batch').replace(/[\\/:*?"<>|]+/g, ' ').slice(0, 40) + '.csv');
  }

  /** طباعة الكروت على ورق A4 (3 أعمدة) */
  function printCards(items) {
    var cur = (S.app && S.app.currency) || '₪';
    var name = (S.app && S.app.name) || 'MADIX Convert';
    var area = document.getElementById('print-area');
    K.clear(area);
    var grid = el('div', { class: 'cards-print' });
    items.forEach(function (c) {
      grid.appendChild(el('div', { class: 'card-print' },
        el('div', { class: 'cp-top' }, el('div', { class: 'row', style: { gap: '2mm' } }, el('img', { src: iconUrl(192), alt: '' }), el('span', { text: name })), el('span', { class: 'cp-pages', text: c.pages + ' صفحة' })),
        el('div', { class: 'cp-code', text: c.code }),
        el('div', { class: 'cp-foot' }, el('span', { text: 'اشحن من: المحفظة ← شحن بكود' }), el('span', { text: Number(c.price) ? money(c.price) + ' ' + cur : '' })),
        el('div', { class: 'cp-foot' }, el('span', { text: 'محوّل الكشوفات الذكي' }), el('span', { style: { direction: 'ltr' }, text: 'Py | Sohaib' }))));
    });
    area.appendChild(grid);
    var imgs = area.querySelectorAll('img');
    var waits = Array.prototype.map.call(imgs, function (im) { return im.complete ? Promise.resolve() : new Promise(function (r) { im.onload = im.onerror = r; }); });
    Promise.all(waits).then(function () { setTimeout(function () { window.print(); }, 80); });
  }

  // ---------- المستخدمين ----------
  var userState = { q: '', page: 1 };
  function viewUsers() {
    var table = el('div', {}, loading());
    var search = el('input', { class: 'input', placeholder: 'ابحث بالاسم أو الجوال', value: userState.q });
    search.addEventListener('input', K.debounce(function () { userState.q = search.value; userState.page = 1; load(); }, 300));
    render(shell('users', 'المستخدمين', 'إدارة الحسابات والأرصدة', el('div', { class: 'stack' }, el('div', { class: 'input-wrap' }, ic('search'), search), table)));
    function load() {
      K.api.get('users', { q: userState.q, page: userState.page }).then(function (r) {
        K.clear(table);
        var tb = el('tbody', {});
        r.items.forEach(function (u) {
          var tr = el('tr', { style: { cursor: 'pointer' } },
            el('td', {}, el('b', { text: u.name }), u.role === 'admin' ? el('span', { class: 'badge violet', style: { 'margin-inline-start': '6px' }, text: 'مدير' }) : null, Number(u.is_blocked) ? el('span', { class: 'badge red', style: { 'margin-inline-start': '6px' }, text: 'موقوف' }) : null),
            el('td', { class: 'ltr', style: { 'text-align': 'right' }, text: u.phone }),
            el('td', {}, el('b', { text: K.num(u.pages) })),
            el('td', { text: K.num(u.pages_used) }),
            el('td', { class: 'small muted', text: K.fmtDay(u.created_at) }),
            el('td', { class: 'small muted', text: u.last_login_at ? K.ago(u.last_login_at) : '—' }));
          tr.addEventListener('click', function () { userSheet(u, load); });
          tb.appendChild(tr);
        });
        if (!r.items.length) tb.appendChild(el('tr', {}, el('td', { colspan: '6', class: 'center muted', style: { padding: '24px' }, text: 'ما في مستخدمين' })));
        table.appendChild(el('div', { class: 'small muted', style: { 'margin-bottom': '8px' }, text: 'العدد: ' + K.num(r.total) }));
        table.appendChild(el('div', { class: 'dt-wrap' }, el('table', { class: 'dt' }, el('thead', {}, el('tr', {}, el('th', { text: 'الاسم' }), el('th', { text: 'الجوال' }), el('th', { text: 'الرصيد' }), el('th', { text: 'استخدم' }), el('th', { text: 'التسجيل' }), el('th', { text: 'آخر دخول' }))), tb)));
        var pagesN = Math.max(1, Math.ceil(r.total / r.per));
        if (pagesN > 1) {
          table.appendChild(el('div', { class: 'pager' },
            el('button', { class: 'btn btn-sm', type: 'button', disabled: userState.page <= 1, on: { click: function () { userState.page--; load(); } } }, ic('chevr'), 'السابق'),
            el('span', { class: 'small muted', text: userState.page + ' / ' + pagesN }),
            el('button', { class: 'btn btn-sm', type: 'button', disabled: userState.page >= pagesN, on: { click: function () { userState.page++; load(); } } }, 'التالي', ic('chevl'))));
        }
      }).catch(function (e) { K.clear(table).appendChild(errBox(e)); });
    }
    load();
  }

  function userSheet(u, reload) {
    var delta = el('input', { class: 'input ltr', type: 'number', placeholder: 'مثال: 10 أو -5' });
    var note = el('input', { class: 'input', placeholder: 'ملاحظة (اختياري)' });
    var ledger = el('div', {}, el('div', { class: 'skeleton', style: { height: '60px' } }));
    var isSelf = S.user && Number(u.id) === Number(S.user.id);
    function op(body, msg) {
      return K.api.post('user_update', Object.assign({ id: u.id }, body)).then(function (r) {
        K.toast(msg || 'تم ✓', 'success');
        reload();
        return r;
      });
    }
    var sh = K.sheet({
      title: u.name,
      wide: true,
      body: el('div', { class: 'stack lg' },
        el('div', { class: 'row wrap' },
          el('span', { class: 'badge ltr', text: u.phone }),
          el('span', { class: 'badge green', text: 'الرصيد: ' + K.num(u.pages) }),
          el('span', { class: 'badge', text: 'استخدم: ' + K.num(u.pages_used) }),
          el('span', { class: 'badge', text: 'سجّل: ' + K.fmtDate(u.created_at) })),
        el('div', { class: 'card flat stack' },
          el('div', { class: 'card-title' }, ic('wallet'), 'تعديل الرصيد'),
          el('div', { class: 'form-grid' }, field('عدد الصفحات (+ إضافة / - خصم)', delta), field('ملاحظة', note)),
          el('button', { class: 'btn btn-primary', type: 'button', on: { click: function (e) {
            var b = e.currentTarget;
            K.busy(b, true);
            op({ op: 'pages', delta: Number(delta.value), note: note.value }, 'تم تعديل الرصيد ✓').then(function () { sh.close(); }).catch(function (er) { K.toast(er.message, 'error'); }).then(function () { K.busy(b, false); });
          } } }, ic('check'), 'تطبيق')),
        el('div', { class: 'row wrap' },
          el('button', { class: 'btn btn-sm', type: 'button', on: { click: function () {
            op({ op: 'reset' }).then(function (r) {
              sh.close();
              K.sheet({ title: 'كلمة السر المؤقتة', body: el('div', { class: 'stack center' }, el('div', { class: 'code-pill', style: { 'font-size': '26px' }, text: r.temp_password }), el('p', { class: 'muted small', text: 'أعطها للمستخدم — يدخل فيها ويغيرها من الإعدادات.' })), actions: [{ label: 'نسخ', icon: 'copy', onClick: function () { K.copy(r.temp_password); return true; } }, { label: 'تم', primary: true }] });
            }).catch(function (er) { K.toast(er.message, 'error'); });
          } } }, ic('key'), 'كلمة سر مؤقتة'),
          !isSelf ? el('button', { class: 'btn btn-sm', type: 'button', on: { click: function () { op({ op: 'block', value: !Number(u.is_blocked) }, Number(u.is_blocked) ? 'تم تفعيل الحساب' : 'تم إيقاف الحساب').then(function () { sh.close(); }).catch(function (er) { K.toast(er.message, 'error'); }); } } }, ic('lock'), Number(u.is_blocked) ? 'تفعيل الحساب' : 'إيقاف الحساب') : null,
          !isSelf ? el('button', { class: 'btn btn-sm', type: 'button', on: { click: function () {
            var to = u.role === 'admin' ? 'user' : 'admin';
            K.confirm(to === 'admin' ? 'إعطاء صلاحية مدير لهذا الحساب؟' : 'إزالة صلاحية المدير؟').then(function (ok) { if (ok) op({ op: 'role', value: to }).then(function () { sh.close(); }).catch(function (er) { K.toast(er.message, 'error'); }); });
          } } }, ic('shield'), u.role === 'admin' ? 'إزالة صلاحية المدير' : 'جعله مدير') : null,
          !isSelf ? el('button', { class: 'btn btn-sm btn-danger', type: 'button', on: { click: function () {
            K.confirm('حذف حساب ' + u.name + ' نهائياً؟', { danger: true, ok: 'حذف' }).then(function (ok) { if (ok) op({ op: 'delete' }, 'تم حذف الحساب').then(function () { sh.close(); }).catch(function (er) { K.toast(er.message, 'error'); }); });
          } } }, ic('trash'), 'حذف') : null),
        el('div', {}, el('div', { class: 'card-title' }, ic('list'), 'سجل الحركات'), ledger))
    });
    K.api.get('user_ledger', { id: u.id }).then(function (r) {
      K.clear(ledger);
      if (!r.items.length) { ledger.appendChild(el('p', { class: 'muted small', text: 'ما في حركات' })); return; }
      r.items.forEach(function (it) {
        var plus = Number(it.delta) > 0;
        ledger.appendChild(el('div', { class: 'ledger-item' },
          el('div', { class: 'ic ' + (plus ? 'plus' : 'minus') }, ic(plus ? 'plus' : 'minus')),
          el('div', { class: 'grow' }, el('div', { style: { 'font-weight': '600' }, text: it.note || it.kind }), el('div', { class: 'tiny muted', text: K.fmtDate(it.created_at) + (it.ref ? ' • ' + it.ref : '') })),
          el('div', { class: 'd ' + (plus ? 'plus' : 'minus'), dir: 'ltr', text: (plus ? '+' : '') + it.delta })));
      });
    }).catch(function (e) { K.clear(ledger).appendChild(errBox(e)); });
  }

  // ---------- الإعدادات ----------
  function viewSettings() {
    var box = el('div', {}, loading());
    render(shell('settings', 'الإعدادات', 'اسم التطبيق، الأسعار، التسجيل، والأيقونة', box));
    K.api.get('settings').then(function (r) { drawSettings(box, r); }).catch(function (e) { K.clear(box).appendChild(errBox(e)); });
  }

  function drawSettings(box, r) {
    var s = r.settings;
    var f = {
      app_name: el('input', { class: 'input', value: s.app_name }),
      tagline: el('input', { class: 'input', value: s.tagline }),
      footer_text: el('input', { class: 'input', value: s.footer_text }),
      contact_phone: el('input', { class: 'input ltr', type: 'tel', value: s.contact_phone, placeholder: '0599xxxxxx' }),
      currency: el('input', { class: 'input', value: s.currency }),
      prices: el('textarea', { class: 'textarea', value: s.prices }),
      announcement: el('textarea', { class: 'textarea', value: s.announcement, placeholder: 'رسالة تظهر لكل المستخدمين بالرئيسية (اختياري)' }),
      signup_pages: el('input', { class: 'input ltr', type: 'number', min: '0', value: s.signup_pages }),
      free_retries: el('input', { class: 'input ltr', type: 'number', min: '0', max: '10', value: s.free_retries }),
      scan_rate_limit: el('input', { class: 'input ltr', type: 'number', min: '5', value: s.scan_rate_limit }),
      max_image_mb: el('input', { class: 'input ltr', type: 'number', min: '1', max: '20', value: s.max_image_mb })
    };
    var reg = sw('السماح بالتسجيل', s.allow_register === '1', 'إذا سكّرته، بس المدير بيقدر يضيف حسابات');
    var maint = sw('وضع الصيانة', s.maintenance === '1');
    var webApp = sw('نسخة الويب للمستخدمين (للآيفون)', s.web_app === '1');
    var save = el('button', { class: 'btn btn-primary btn-lg', type: 'submit' }, ic('check'), 'حفظ الإعدادات');
    var form = el('form', { class: 'stack lg' },
      el('div', { class: 'card stack' }, el('div', { class: 'card-title' }, ic('sparkles'), 'هوية التطبيق'),
        el('div', { class: 'form-grid' },
          field('اسم التطبيق', f.app_name), field('رقم واتساب للتواصل وبيع الأكواد', f.contact_phone),
          el('div', { class: 'full' }, field('الوصف (يظهر بشاشة البداية)', f.tagline)),
          el('div', { class: 'full' }, field('نص التذييل', f.footer_text, 'تحته بيظهر دائماً: Py | Sohaib')))),
      el('div', { class: 'card stack' }, el('div', { class: 'card-title' }, ic('coins'), 'الأسعار والرصيد'),
        el('div', { class: 'form-grid' },
          field('العملة', f.currency), field('صفحات هدية عند التسجيل', f.signup_pages),
          el('div', { class: 'full' }, field('قائمة الأسعار (كل سطر باقة)', f.prices)),
          field('إعادة قراءة مجانية لنفس الصورة', f.free_retries, 'خلال 24 ساعة — لو النت قطع أو بدك تعيد'),
          field('حد القراءات كل 10 دقائق للمستخدم', f.scan_rate_limit))),
      el('div', { class: 'card stack' }, el('div', { class: 'card-title' }, ic('shield'), 'التشغيل'),
        field('إعلان للمستخدمين', f.announcement),
        field('أقصى حجم صورة (ميجا)', f.max_image_mb),
        el('div', {}, reg.node, maint.node, webApp.node)),
      save);
    form.addEventListener('submit', function (e) {
      e.preventDefault();
      var body = {};
      Object.keys(f).forEach(function (k) { body[k] = f[k].value; });
      body.allow_register = reg.input.checked;
      body.maintenance = maint.input.checked;
      body.web_app = webApp.input.checked;
      K.busy(save, true);
      K.api.post('settings_save', body).then(function () {
        K.toast('تم حفظ الإعدادات ✓', 'success');
        return K.api.get('me').then(function (m) { S.app = m.app; });
      }).catch(function (e2) { K.toast(e2.message, 'error'); }).then(function () { K.busy(save, false); });
    });

    // الأيقونة
    var preview = el('img', { class: 'icon-preview', src: iconUrl(512) + '&t=' + Date.now(), alt: 'أيقونة التطبيق' });
    var file = el('input', { type: 'file', accept: 'image/png,image/jpeg,image/webp', hidden: true });
    var up = el('button', { class: 'btn btn-soft', type: 'button', on: { click: function () { file.click(); } } }, ic('upload'), 'رفع أيقونة جديدة');
    file.addEventListener('change', function () {
      var fl = file.files && file.files[0];
      file.value = '';
      if (!fl) return;
      var fd = new FormData();
      fd.append('icon', fl);
      K.busy(up, true);
      K.api.upload('icon', fd).then(function (res) {
        S.app.icon_v = res.icon_v;
        preview.src = iconUrl(512) + '&t=' + Date.now();
        K.toast('تم تغيير الأيقونة ✓', 'success');
      }).catch(function (e) { K.toast(e.message, 'error'); }).then(function () { K.busy(up, false); });
    });
    var resetBtn = el('button', { class: 'btn', type: 'button', on: { click: function () {
      K.confirm('إرجاع الأيقونة الأصلية؟').then(function (ok) {
        if (!ok) return;
        K.api.post('icon', { op: 'reset' }).then(function (res) { S.app.icon_v = res.icon_v; preview.src = iconUrl(512) + '&t=' + Date.now(); K.toast('تم ✓', 'success'); }).catch(function (e) { K.toast(e.message, 'error'); });
      });
    } } }, ic('refresh'), 'الأيقونة الأصلية');
    var iconCard = el('div', { class: 'card stack' }, el('div', { class: 'card-title' }, ic('image'), 'أيقونة التطبيق'),
      el('div', { class: 'row wrap', style: { gap: '18px' } }, preview,
        el('div', { class: 'stack grow' },
          el('div', { class: 'row wrap' }, up, r.has_custom_icon ? resetBtn : null))), file);

    K.clear(box).appendChild(el('div', { class: 'stack lg' }, iconCard, form));
  }

  // ---------- تطبيق أندرويد ----------
  function viewAndroid() {
    var box = el('div', {}, loading());
    render(shell('android', 'تطبيق أندرويد', null, box));
    function load() {
      K.api.get('app_info').then(function (r) {
        K.clear(box);
        var file = el('input', { type: 'file', accept: '.apk,application/vnd.android.package-archive', hidden: true });
        var ver = el('input', { class: 'input ltr', placeholder: '1.0.0', value: r.version || '' });
        var up = el('button', { class: 'btn btn-primary', type: 'button', on: { click: function () { file.click(); } } }, ic('upload'), r.apk ? 'رفع نسخة جديدة' : 'رفع ملف APK');
        file.addEventListener('change', function () {
          var f = file.files && file.files[0];
          file.value = '';
          if (!f) return;
          var fd = new FormData();
          fd.append('apk', f);
          fd.append('version', ver.value.trim());
          K.busy(up, true, ' 0%');
          K.api.upload('apk_upload', fd, function (p, phase) {
            if (phase === 'up') { K.clear(up).appendChild(el('span', { class: 'spin' })); up.appendChild(document.createTextNode(' ' + Math.round(p * 100) + '%')); }
          }).then(function () { K.toast('تم رفع التطبيق ✓', 'success'); load(); }).catch(function (e) { K.toast(e.message, 'error'); K.busy(up, false); });
        });
        function linkRow(label, url) {
          return el('div', { class: 'field' }, el('label', { text: label }),
            el('div', { class: 'row' }, el('input', { class: 'input ltr mono', value: url, readOnly: true, style: { 'font-size': '13px' } }),
              el('button', { class: 'btn btn-icon', type: 'button', 'aria-label': 'نسخ', on: { click: function () { K.copy(url); } } }, ic('copy'))));
        }
        box.appendChild(el('div', { class: 'stack lg' },
          el('div', { class: 'card stack' },
            el('div', { class: 'row' },
              el('img', { src: iconUrl(192), alt: '', style: { width: '58px', height: '58px', 'border-radius': '16px' } }),
              el('div', { class: 'grow' }, el('b', { style: { 'font-size': '17px' }, text: (S.app && S.app.name) || 'MADIX Convert' }),
                el('div', { class: 'small muted', text: r.apk ? ((r.version ? 'الإصدار ' + r.version + ' • ' : '') + (r.size / 1048576).toFixed(1) + ' MB • ' + K.fmtDate(r.updated)) : 'ما في ملف تطبيق مرفوع' })),
              el('span', { class: 'badge ' + (r.apk ? 'green' : 'amber'), text: r.apk ? 'جاهز للتحميل' : 'غير متوفر' })),
            el('div', { class: 'form-grid' }, field('رقم الإصدار', ver), el('div', { class: 'field' }, el('label', { text: ' ' }), el('div', { class: 'row wrap' }, up,
              r.apk ? el('a', { class: 'btn', href: r.download_url }, ic('download'), 'تحميل') : null,
              r.uploaded ? el('button', { class: 'btn btn-danger btn-icon', type: 'button', 'aria-label': 'حذف', on: { click: function () {
                K.confirm('حذف ملف التطبيق المرفوع؟', { danger: true, ok: 'حذف' }).then(function (ok) { if (ok) K.api.post('apk_delete', {}).then(load).catch(function (e) { K.toast(e.message, 'error'); }); });
              } } }, ic('trash')) : null))),
            file),
          el('div', { class: 'card stack' },
            el('div', { class: 'card-title' }, ic('link'), 'الروابط'),
            linkRow('رابط تحميل التطبيق', r.download_url),
            linkRow('صفحة التحميل', r.landing_url),
            linkRow('رابط السيرفر (للتطبيق)', r.server_url))));
      }).catch(function (e) { K.clear(box).appendChild(errBox(e)); });
    }
    load();
  }

  // ---------- السجلات ----------
  var logTab = 'scans';
  function viewLogs() {
    var box = el('div', {}, loading());
    var seg = el('div', { class: 'seg', style: { 'max-width': '420px', 'margin-bottom': '14px' } },
      el('button', { type: 'button', class: logTab === 'scans' ? 'is-active' : '', on: { click: function () { logTab = 'scans'; viewLogs(); } } }, 'عمليات القراءة'),
      el('button', { type: 'button', class: logTab === 'audit' ? 'is-active' : '', on: { click: function () { logTab = 'audit'; viewLogs(); } } }, 'سجل العمليات'));
    render(shell('logs', 'السجلات', 'آخر 300 عملية', el('div', {}, seg, box)));
    if (logTab === 'scans') {
      K.api.get('scans').then(function (r) {
        K.clear(box);
        var tb = el('tbody', {});
        var stName = { ok: ['نجحت', 'green'], fail: ['فشلت', 'red'], empty: ['بدون جدول', 'amber'] };
        r.items.forEach(function (s) {
          var st = stName[s.status] || [s.status, ''];
          tb.appendChild(el('tr', {},
            el('td', { class: 'small', text: K.fmtDate(s.created_at) }),
            el('td', { text: s.name || '—' }),
            el('td', { class: 'mono small ltr', text: s.model }),
            el('td', {}, el('span', { class: 'badge ' + st[1], text: st[0] })),
            el('td', { text: s.rows_count }),
            el('td', { text: s.charged }),
            el('td', { class: 'small ltr', text: K.num(s.input_tokens) + ' / ' + K.num(s.output_tokens) }),
            el('td', { class: 'small ltr', text: usd(s.cost_usd) }),
            el('td', { class: 'small', text: (s.ms / 1000).toFixed(1) + 'ث' }),
            el('td', { class: 'small muted', style: { 'max-width': '260px', 'white-space': 'normal' }, text: s.error || '' })));
        });
        if (!r.items.length) tb.appendChild(el('tr', {}, el('td', { colspan: '10', class: 'center muted', style: { padding: '24px' }, text: 'ما في عمليات لسا' })));
        box.appendChild(el('div', { class: 'dt-wrap' }, el('table', { class: 'dt' }, el('thead', {}, el('tr', {}, ['الوقت', 'المستخدم', 'النموذج', 'الحالة', 'صفوف', 'خصم', 'توكنز (دخول/خروج)', 'التكلفة', 'المدة', 'ملاحظة'].map(function (t) { return el('th', { text: t }); }))), tb)));
      }).catch(function (e) { K.clear(box).appendChild(errBox(e)); });
    } else {
      K.api.get('audit').then(function (r) {
        K.clear(box);
        var tb = el('tbody', {});
        r.items.forEach(function (a) {
          tb.appendChild(el('tr', {}, el('td', { class: 'small', text: K.fmtDate(a.created_at) }), el('td', { text: a.name || 'نظام' }), el('td', {}, el('b', { text: a.action })), el('td', { class: 'small', style: { 'white-space': 'normal' }, text: a.details }), el('td', { class: 'small muted ltr', text: a.ip })));
        });
        if (!r.items.length) tb.appendChild(el('tr', {}, el('td', { colspan: '5', class: 'center muted', style: { padding: '24px' }, text: 'السجل فاضي' })));
        box.appendChild(el('div', { class: 'dt-wrap' }, el('table', { class: 'dt' }, el('thead', {}, el('tr', {}, ['الوقت', 'المستخدم', 'العملية', 'التفاصيل', 'IP'].map(function (t) { return el('th', { text: t }); }))), tb)));
      }).catch(function (e) { K.clear(box).appendChild(errBox(e)); });
    }
  }

  // ---------- التشغيل ----------
  function guard(fn) {
    return function (p) {
      if (S.setup) { viewSetup(); return; }
      if (!S.user || S.user.role !== 'admin') { viewLogin(S.user ? 'هذا الحساب مش حساب مدير — ادخل بحساب المدير' : ''); return; }
      fn(p);
    };
  }
  K.route('/overview', guard(viewOverview));
  K.route('/', guard(viewOverview));
  K.route('/models', guard(viewModels));
  K.route('/keys', guard(viewKeys));
  K.route('/codes', guard(viewCodes));
  K.route('/users', guard(viewUsers));
  K.route('/settings', guard(viewSettings));
  K.route('/logs', guard(viewLogs));
  K.route('/android', guard(viewAndroid));

  K.api.onAuthLost = function () { S.user = null; viewLogin('انتهت الجلسة، ادخل مرة ثانية'); };

  function boot() {
    K.setTheme(K.getTheme());
    K.api.get('me').then(function (r) {
      S.app = r.app; S.user = r.user; S.setup = !!r.setup;
      document.title = 'لوحة الإدارة — ' + (S.app.name || 'MADIX Convert');
      K.startRouter();
      K.hideSplash();
    }).catch(function (e) {
      K.hideSplash();
      render(el('div', { class: 'main narrow' }, errBox(e), el('button', { class: 'btn btn-primary btn-block', type: 'button', style: { 'margin-top': '12px' }, on: { click: function () { location.reload(); } } }, 'إعادة المحاولة')));
    });
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
})();
