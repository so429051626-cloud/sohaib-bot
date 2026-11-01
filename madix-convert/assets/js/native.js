/* ==========================================================
   MADIX Convert — ميزات تطبيق أندرويد (Capacitor)
   الكاميرا والمعرض، حفظ ومشاركة الملفات، الحافظة، زر الرجوع، شريط الحالة، ربط السيرفر
   بيشتغل فقط داخل التطبيق — بالمتصفح ما بيعمل شي
   Py | Sohaib
   ========================================================== */
(function () {
  'use strict';
  var K = window.K;
  var Cap = window.Capacitor;
  var on = !!(Cap && typeof Cap.isNativePlatform === 'function' && Cap.isNativePlatform());
  var N = K.nat = { on: on };
  if (!on) return;
  document.documentElement.classList.add('native');

  function plug(name) {
    try { return Cap.registerPlugin(name); } catch (e) { return null; }
  }
  var Camera = plug('Camera');
  var Filesystem = plug('Filesystem');
  var Share = plug('Share');
  var App = plug('App');
  var StatusBar = plug('StatusBar');
  var Splash = plug('SplashScreen');
  var Clip = plug('Clipboard');

  // ---------- الصور ----------
  function fetchBlob(webPath) {
    return fetch(webPath).then(function (r) { return r.blob(); }).then(function (b) {
      return b.type && /^image\//.test(b.type) ? b : new Blob([b], { type: 'image/jpeg' });
    });
  }
  N.isCancel = function (e) { return /cancel|canceled|cancelled|No image picked/i.test(String((e && (e.message || e.errorMessage)) || e || '')); };
  N.takePhoto = function () {
    return Camera.getPhoto({ quality: 92, resultType: 'uri', source: 'CAMERA', correctOrientation: true, saveToGallery: false, allowEditing: false })
      .then(function (p) { return fetchBlob(p.webPath); }).then(function (b) { return [b]; });
  };
  N.pickPhotos = function (limit) {
    return Camera.pickImages({ quality: 92, limit: limit || 40, correctOrientation: true })
      .then(function (r) { return Promise.all((r.photos || []).map(function (p) { return fetchBlob(p.webPath); })); });
  };

  // ---------- الملفات ----------
  function toBase64(blob) {
    return new Promise(function (resolve, reject) {
      var fr = new FileReader();
      fr.onload = function () { resolve(String(fr.result).split(',')[1] || ''); };
      fr.onerror = function () { reject(new Error('تعذّر تجهيز الملف')); };
      fr.readAsDataURL(blob);
    });
  }
  function safeName(n) { return String(n || 'file').replace(/[\\/:*?"<>|]+/g, ' ').trim() || 'file'; }
  /** حفظ الملف ثم فتح قائمة المشاركة (واتساب، درايف، الملفات…) */
  N.share = function (blob, name, title) {
    name = safeName(name);
    return toBase64(blob).then(function (data) {
      return Filesystem.writeFile({ path: name, data: data, directory: 'CACHE' });
    }).then(function (res) {
      return Share.share({ title: title || name, dialogTitle: 'حفظ أو مشاركة الملف', files: [res.uri] });
    });
  };
  /** حفظ الملف بمجلد المستندات على الجوال */
  N.saveToDevice = function (blob, name) {
    name = safeName(name);
    return toBase64(blob).then(function (data) {
      return Filesystem.writeFile({ path: 'MADIX Convert/' + name, data: data, directory: 'DOCUMENTS', recursive: true });
    }).then(function () { return 'Documents/MADIX Convert/' + name; });
  };

  // ---------- الحافظة ----------
  N.copy = function (text) { return Clip.write({ string: String(text) }); };
  N.paste = function () { return Clip.read().then(function (r) { return (r && r.value) || ''; }); };


  // ---------- اجتياز حماية الاستضافة المجانية (تحدي الكوكي) ----------
  // بعض الاستضافات المجانية بترجع صفحة جافاسكربت بدل البيانات أول مرة؛ بنحسب الكوكي ونعيد الطلب
  var AES = (function () {
    var S = new Uint8Array(256), SI = new Uint8Array(256);
    (function () {
      var p = 1, q = 1;
      do {
        p = p ^ ((p << 1) & 0xff) ^ (p & 0x80 ? 0x1b : 0);
        q ^= q << 1; q ^= q << 2; q ^= q << 4; q &= 0xff;
        if (q & 0x80) q ^= 0x09;
        var x = q ^ ((q << 1) | (q >> 7)) ^ ((q << 2) | (q >> 6)) ^ ((q << 3) | (q >> 5)) ^ ((q << 4) | (q >> 4));
        x = (x ^ 0x63) & 0xff;
        S[p] = x; SI[x] = p;
      } while (p !== 1);
      S[0] = 0x63; SI[0x63] = 0;
    })();
    function xt(a) { return ((a << 1) ^ (a & 0x80 ? 0x1b : 0)) & 0xff; }
    function mul(a, b) { var r = 0; while (b) { if (b & 1) r ^= a; a = xt(a); b >>= 1; } return r; }
    function expand(key) {
      var w = new Uint8Array(176), rc = 1, i, t = new Uint8Array(4);
      w.set(key);
      for (i = 16; i < 176; i += 4) {
        t[0] = w[i - 4]; t[1] = w[i - 3]; t[2] = w[i - 2]; t[3] = w[i - 1];
        if (i % 16 === 0) {
          var a = t[0]; t[0] = S[t[1]] ^ rc; t[1] = S[t[2]]; t[2] = S[t[3]]; t[3] = S[a];
          rc = xt(rc);
        }
        for (var j = 0; j < 4; j++) w[i + j] = w[i - 16 + j] ^ t[j];
      }
      return w;
    }
    function decryptBlock(block, key) {
      var w = expand(key), s = new Uint8Array(block), r, c, i, t = new Uint8Array(16);
      function addKey(round) { for (var k = 0; k < 16; k++) s[k] ^= w[round * 16 + k]; }
      function invShiftSub() {
        for (c = 0; c < 4; c++) for (r = 0; r < 4; r++) t[r + 4 * ((c + r) % 4)] = SI[s[r + 4 * c]];
        s.set(t);
      }
      function invMix() {
        for (c = 0; c < 4; c++) {
          var a0 = s[4 * c], a1 = s[4 * c + 1], a2 = s[4 * c + 2], a3 = s[4 * c + 3];
          s[4 * c] = mul(a0, 14) ^ mul(a1, 11) ^ mul(a2, 13) ^ mul(a3, 9);
          s[4 * c + 1] = mul(a0, 9) ^ mul(a1, 14) ^ mul(a2, 11) ^ mul(a3, 13);
          s[4 * c + 2] = mul(a0, 13) ^ mul(a1, 9) ^ mul(a2, 14) ^ mul(a3, 11);
          s[4 * c + 3] = mul(a0, 11) ^ mul(a1, 13) ^ mul(a2, 9) ^ mul(a3, 14);
        }
      }
      addKey(10);
      for (i = 9; i >= 1; i--) { invShiftSub(); addKey(i); invMix(); }
      invShiftSub();
      addKey(0);
      return s;
    }
    return { decryptBlock: decryptBlock };
  })();
  N._aes = AES;
  function hexBytes(h) { var o = new Uint8Array(h.length / 2); for (var i = 0; i < o.length; i++) o[i] = parseInt(h.substr(i * 2, 2), 16); return o; }
  function bytesHex(b) { var s = ''; for (var i = 0; i < b.length; i++) s += (b[i] < 16 ? '0' : '') + b[i].toString(16); return s; }
  N.isChallenge = function (text) { return typeof text === 'string' && /slowAES|aes\.min\.js|aes\.js/.test(text) && /toNumbers\(/.test(text); };
  /** يحسب كوكي الحماية ويحفظه للسيرفر */
  N.solveChallenge = function (html, serverUrl) {
    var m = String(html).match(/toNumbers\("([0-9a-f]+)"\)[\s\S]*?toNumbers\("([0-9a-f]+)"\)[\s\S]*?toNumbers\("([0-9a-f]+)"\)/i);
    if (!m) return Promise.reject(new Error('تعذّر الاتصال بالسيرفر'));
    var key = hexBytes(m[1]), iv = hexBytes(m[2]), ct = hexBytes(m[3]);
    var out = new Uint8Array(ct.length);
    var prev = iv;
    for (var off = 0; off < ct.length; off += 16) {
      var blk = ct.subarray(off, off + 16);
      var d = AES.decryptBlock(blk, key);
      for (var k = 0; k < 16; k++) out[off + k] = d[k] ^ prev[k];
      prev = blk;
    }
    var name = (String(html).match(/document\.cookie\s*=\s*"([A-Za-z0-9_]+)=/) || [])[1] || '__test';
    var value = bytesHex(out);
    var Cookies = plug('CapacitorCookies');
    var exp = 'Thu, 31 Dec 2037 23:55:55 GMT';
    if (Cookies) return Cookies.setCookie({ url: serverUrl, key: name, value: value, path: '/', expires: exp }).then(function () { return value; });
    document.cookie = name + '=' + value + '; expires=' + exp + '; path=/';
    return Promise.resolve(value);
  };

  // ---------- الواجهة ----------
  N.statusBar = function (dark) {
    if (!StatusBar) return;
    StatusBar.setStyle({ style: dark ? 'DARK' : 'LIGHT' }).catch(function () { });
  };
  N.hideSplash = function () { if (Splash) Splash.hide().catch(function () { }); };
  N.onBack = function (handler) { if (App) App.addListener('backButton', handler); };
  N.exit = function () { if (App) App.exitApp(); };
  N.openUrl = function (url) { window.open(url, '_system'); };

  // شريط الحالة: داكن على شاشة البداية
  N.statusBar(true);
})();
