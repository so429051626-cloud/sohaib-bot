/* ==========================================================
   MADIX Convert — Service Worker: فتح سريع وعمل بدون نت للكشوفات المحفوظة
   Py | Sohaib
   ========================================================== */
'use strict';
var VERSION = new URL(self.location.href).searchParams.get('v') || '1';
var CACHE = 'madix-' + VERSION;
var FONT_CACHE = 'madix-fonts';
var SHELL = [
  './',
  'assets/css/app.css?v=' + VERSION,
  'assets/js/boot.js?v=' + VERSION,
  'assets/js/core.js?v=' + VERSION,
  'assets/js/imaging.js?v=' + VERSION,
  'assets/js/analysis.js?v=' + VERSION,
  'assets/js/xlsx.js?v=' + VERSION,
  'assets/js/pdf.js?v=' + VERSION,
  'assets/js/app.js?v=' + VERSION
];

self.addEventListener('install', function (e) {
  e.waitUntil(caches.open(CACHE).then(function (c) {
    return Promise.all(SHELL.map(function (u) { return c.add(u).catch(function () { /* تجاهل */ }); }));
  }).then(function () { return self.skipWaiting(); }));
});

self.addEventListener('activate', function (e) {
  e.waitUntil(caches.keys().then(function (keys) {
    return Promise.all(keys.filter(function (k) { return k !== CACHE && k !== FONT_CACHE && k.indexOf('madix-') === 0; }).map(function (k) { return caches.delete(k); }));
  }).then(function () { return self.clients.claim(); }));
});

self.addEventListener('fetch', function (e) {
  var req = e.request;
  if (req.method !== 'GET') return;
  var url = new URL(req.url);

  // الخطوط من Google: من الكاش أولاً
  if (url.hostname === 'fonts.googleapis.com' || url.hostname === 'fonts.gstatic.com') {
    e.respondWith(caches.open(FONT_CACHE).then(function (c) {
      return c.match(req).then(function (hit) {
        return hit || fetch(req).then(function (res) { if (res && (res.ok || res.type === 'opaque')) c.put(req, res.clone()); return res; });
      });
    }));
    return;
  }
  if (url.origin !== self.location.origin) return;
  // طلبات البيانات والإدارة دائماً من السيرفر
  if (url.searchParams.has('api') || /admin\.php$/.test(url.pathname)) return;

  // الصفحة الرئيسية: من الشبكة أولاً، ولو ما في نت من الكاش
  if (req.mode === 'navigate') {
    e.respondWith(fetch(req).then(function (res) {
      if (res && res.ok) { var copy = res.clone(); caches.open(CACHE).then(function (c) { c.put('./', copy); }); }
      return res;
    }).catch(function () {
      return caches.match('./').then(function (hit) { return hit || new Response('<!doctype html><meta charset="utf-8"><body dir="rtl" style="font-family:Tahoma;padding:30px;text-align:center">ما في اتصال بالإنترنت</body>', { headers: { 'Content-Type': 'text/html; charset=utf-8' } }); });
    }));
    return;
  }

  // الأيقونات والـ manifest: من الكاش مع تحديث بالخلفية
  if (url.searchParams.has('asset')) {
    e.respondWith(caches.open(CACHE).then(function (c) {
      return c.match(req).then(function (hit) {
        var net = fetch(req).then(function (res) { if (res && res.ok) c.put(req, res.clone()); return res; }).catch(function () { return hit; });
        return hit || net;
      });
    }));
    return;
  }

  // باقي الملفات: من الكاش أولاً
  if (/\/assets\//.test(url.pathname)) {
    e.respondWith(caches.match(req).then(function (hit) {
      return hit || fetch(req).then(function (res) {
        if (res && res.ok) { var copy = res.clone(); caches.open(CACHE).then(function (c) { c.put(req, copy); }); }
        return res;
      });
    }));
  }
});
