/* ==========================================================
   MADIX Convert — معالجة الصور على الجهاز
   تصغير وضغط للنت الضعيف، تدوير، قص بزوايا (تصحيح المنظور)، تحسين الوضوح
   Py | Sohaib
   ========================================================== */
(function () {
  'use strict';
  var K = window.K;
  var el = K.el;
  var MAX_SIDE = 2400;
  var MAX_BYTES = 1.6 * 1024 * 1024;

  function canvas(w, h) {
    var c = document.createElement('canvas');
    c.width = Math.max(1, Math.round(w));
    c.height = Math.max(1, Math.round(h));
    return c;
  }
  function ctx2d(c) { return c.getContext('2d', { willReadFrequently: false }); }

  /** يفتح ملف صورة (مع احترام اتجاه الكاميرا) ويرجع canvas بحجم مناسب */
  function load(file) {
    return new Promise(function (resolve, reject) {
      if (!file || !/^image\//.test(file.type || 'image/')) return reject(new Error('الملف المختار ليس صورة'));
      var url = URL.createObjectURL(file);
      var img = new Image();
      img.decoding = 'async';
      img.onload = function () {
        try {
          var w = img.naturalWidth, h = img.naturalHeight;
          if (!w || !h) throw new Error('صورة فارغة');
          var s = Math.min(1, MAX_SIDE / Math.max(w, h));
          var c = canvas(w * s, h * s);
          var g = ctx2d(c);
          g.imageSmoothingEnabled = true;
          g.imageSmoothingQuality = 'high';
          g.fillStyle = '#fff';
          g.fillRect(0, 0, c.width, c.height);
          g.drawImage(img, 0, 0, c.width, c.height);
          URL.revokeObjectURL(url);
          resolve(c);
        } catch (e) {
          URL.revokeObjectURL(url);
          reject(new Error('ما قدرنا نفتح الصورة'));
        }
      };
      img.onerror = function () { URL.revokeObjectURL(url); reject(new Error('ما قدرنا نفتح الصورة — جرّب صورة JPG أو PNG')); };
      img.src = url;
    });
  }

  function toBlob(c, type, q) {
    return new Promise(function (resolve, reject) {
      if (c.toBlob) {
        c.toBlob(function (b) { b ? resolve(b) : reject(new Error('تعذّر حفظ الصورة')); }, type, q);
      } else {
        try {
          var data = c.toDataURL(type, q);
          var bin = atob(data.split(',')[1]);
          var arr = new Uint8Array(bin.length);
          for (var i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
          resolve(new Blob([arr], { type: type }));
        } catch (e) { reject(e); }
      }
    });
  }

  /** ضغط الصورة لحجم مناسب للرفع (يحافظ على وضوح الخط) */
  function compress(c) {
    var quality = 0.86;
    var cur = c;
    function attempt(n) {
      return toBlob(cur, 'image/jpeg', quality).then(function (b) {
        if (b.size <= MAX_BYTES || n >= 6) return { blob: b, w: cur.width, h: cur.height };
        if (quality > 0.68) quality -= 0.08;
        else cur = scale(cur, 0.85);
        return attempt(n + 1);
      });
    }
    return attempt(0);
  }

  function scale(c, f) {
    var o = canvas(c.width * f, c.height * f);
    var g = ctx2d(o);
    g.imageSmoothingQuality = 'high';
    g.drawImage(c, 0, 0, o.width, o.height);
    return o;
  }

  function thumb(c) {
    var s = Math.min(1, 360 / Math.max(c.width, c.height));
    return toBlob(scale(c, s), 'image/jpeg', 0.72);
  }

  function rotate(c, dir) {
    var o = canvas(c.height, c.width);
    var g = ctx2d(o);
    g.translate(o.width / 2, o.height / 2);
    g.rotate(dir > 0 ? Math.PI / 2 : -Math.PI / 2);
    g.drawImage(c, -c.width / 2, -c.height / 2);
    return o;
  }

  // ---------- تصحيح المنظور (Homography) ----------
  function solve(A, b) {
    var n = b.length, i, j, k;
    for (i = 0; i < n; i++) A[i].push(b[i]);
    for (i = 0; i < n; i++) {
      var max = i;
      for (k = i + 1; k < n; k++) if (Math.abs(A[k][i]) > Math.abs(A[max][i])) max = k;
      var tmp = A[i]; A[i] = A[max]; A[max] = tmp;
      if (Math.abs(A[i][i]) < 1e-12) return null;
      for (k = i + 1; k < n; k++) {
        var f = A[k][i] / A[i][i];
        for (j = i; j <= n; j++) A[k][j] -= f * A[i][j];
      }
    }
    var x = new Array(n);
    for (i = n - 1; i >= 0; i--) {
      var s = A[i][n];
      for (j = i + 1; j < n; j++) s -= A[i][j] * x[j];
      x[i] = s / A[i][i];
    }
    return x;
  }
  /** يحسب المصفوفة التي تحول نقاط المستطيل الناتج إلى نقاط الصورة الأصلية */
  function homography(dst, src) {
    var A = [], b = [];
    for (var i = 0; i < 4; i++) {
      var x = dst[i].x, y = dst[i].y, u = src[i].x, v = src[i].y;
      A.push([x, y, 1, 0, 0, 0, -x * u, -y * u]); b.push(u);
      A.push([0, 0, 0, x, y, 1, -x * v, -y * v]); b.push(v);
    }
    return solve(A, b);
  }
  function dist(a, b) { return Math.hypot(a.x - b.x, a.y - b.y); }

  /** يقص الصورة حسب 4 زوايا (أعلى يسار، أعلى يمين، أسفل يمين، أسفل يسار) ويعدّل المنظور */
  function warp(src, q) {
    var W = Math.max(dist(q[0], q[1]), dist(q[3], q[2]));
    var H = Math.max(dist(q[0], q[3]), dist(q[1], q[2]));
    var s = Math.min(1, MAX_SIDE / Math.max(W, H));
    W = Math.max(50, Math.round(W * s));
    H = Math.max(50, Math.round(H * s));
    var m = homography([{ x: 0, y: 0 }, { x: W, y: 0 }, { x: W, y: H }, { x: 0, y: H }], q);
    if (!m) return src;
    var sw = src.width, sh = src.height;
    var sd = ctx2d(src).getImageData(0, 0, sw, sh).data;
    var out = canvas(W, H);
    var og = ctx2d(out);
    var od = og.createImageData(W, H);
    var o = od.data;
    var a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5], g = m[6], h = m[7];
    var p = 0;
    for (var y = 0; y < H; y++) {
      for (var x = 0; x < W; x++, p += 4) {
        var den = g * x + h * y + 1;
        var u = (a * x + b * y + c) / den;
        var v = (d * x + e * y + f) / den;
        if (u < 0 || v < 0 || u > sw - 1 || v > sh - 1) {
          o[p] = o[p + 1] = o[p + 2] = 255; o[p + 3] = 255;
          continue;
        }
        var x0 = u | 0, y0 = v | 0;
        var x1 = x0 + 1 < sw ? x0 + 1 : x0, y1 = y0 + 1 < sh ? y0 + 1 : y0;
        var fx = u - x0, fy = v - y0;
        var i00 = (y0 * sw + x0) * 4, i10 = (y0 * sw + x1) * 4, i01 = (y1 * sw + x0) * 4, i11 = (y1 * sw + x1) * 4;
        var w00 = (1 - fx) * (1 - fy), w10 = fx * (1 - fy), w01 = (1 - fx) * fy, w11 = fx * fy;
        o[p] = sd[i00] * w00 + sd[i10] * w10 + sd[i01] * w01 + sd[i11] * w11;
        o[p + 1] = sd[i00 + 1] * w00 + sd[i10 + 1] * w10 + sd[i01 + 1] * w01 + sd[i11 + 1] * w11;
        o[p + 2] = sd[i00 + 2] * w00 + sd[i10 + 2] * w10 + sd[i01 + 2] * w01 + sd[i11 + 2] * w11;
        o[p + 3] = 255;
      }
    }
    og.putImageData(od, 0, 0);
    return out;
  }

  /** تحسين الوضوح: تمديد الإضاءة والتباين وتبييض الخلفية مع الحفاظ على ألوان الحبر */
  function enhance(src) {
    var w = src.width, hgt = src.height;
    var out = canvas(w, hgt);
    var g = ctx2d(out);
    g.drawImage(src, 0, 0);
    var img = g.getImageData(0, 0, w, hgt);
    var d = img.data;
    var hist = new Uint32Array(256);
    var step = Math.max(1, Math.floor((w * hgt) / 400000));
    var count = 0, i;
    for (i = 0; i < d.length; i += 4 * step) {
      hist[(d[i] * 0.299 + d[i + 1] * 0.587 + d[i + 2] * 0.114) | 0]++;
      count++;
    }
    var lo = 0, hi = 255, acc = 0;
    for (i = 0; i < 256; i++) { acc += hist[i]; if (acc > count * 0.015) { lo = i; break; } }
    acc = 0;
    for (i = 255; i >= 0; i--) { acc += hist[i]; if (acc > count * 0.12) { hi = i; break; } }
    if (hi - lo < 40) { lo = Math.max(0, lo - 20); hi = Math.min(255, hi + 20); }
    var lut = new Uint8ClampedArray(256);
    for (i = 0; i < 256; i++) {
      var t = (i - lo) / (hi - lo);
      t = t < 0 ? 0 : t > 1 ? 1 : t;
      lut[i] = Math.round(Math.pow(t, 1.15) * 255);
    }
    for (i = 0; i < d.length; i += 4) {
      d[i] = lut[d[i]];
      d[i + 1] = lut[d[i + 1]];
      d[i + 2] = lut[d[i + 2]];
    }
    g.putImageData(img, 0, 0);
    return out;
  }

  function blobToCanvas(blob) {
    return new Promise(function (resolve, reject) {
      var url = URL.createObjectURL(blob);
      var img = new Image();
      img.onload = function () {
        var c = canvas(img.naturalWidth, img.naturalHeight);
        ctx2d(c).drawImage(img, 0, 0);
        URL.revokeObjectURL(url);
        resolve(c);
      };
      img.onerror = function () { URL.revokeObjectURL(url); reject(new Error('تعذّر فتح الصورة')); };
      img.src = url;
    });
  }

  /** يقص شريط صف من صورة الصفحة لعرضه جنب بيانات الصف */
  function strip(blob, yTop, yBottom, maxW) {
    return blobToCanvas(blob).then(function (c) {
      var pad = Math.max(10, (yBottom - yTop) * 0.45);
      var y0 = Math.max(0, Math.floor(yTop - pad));
      var y1 = Math.min(c.height, Math.ceil(yBottom + pad));
      var hh = y1 - y0;
      if (hh < 8) throw new Error('حدود الصف غير صالحة');
      var s = Math.min(1, (maxW || 900) / c.width);
      var o = canvas(c.width * s, hh * s);
      var g = ctx2d(o);
      g.imageSmoothingQuality = 'high';
      g.drawImage(c, 0, y0, c.width, hh, 0, 0, o.width, o.height);
      // تظليل خفيف لحدود الصف الحقيقية
      g.fillStyle = 'rgba(16,185,129,0.10)';
      g.fillRect(0, (yTop - y0) * s, o.width, (yBottom - yTop) * s);
      g.strokeStyle = 'rgba(5,150,105,0.85)';
      g.lineWidth = 2;
      g.strokeRect(1, (yTop - y0) * s, o.width - 2, (yBottom - yTop) * s);
      return o;
    });
  }

  // ---------- محرر الصورة (قص بالزوايا + تدوير + تحسين) ----------
  function edit(source, opts) {
    opts = opts || {};
    return new Promise(function (resolve) {
      var work = source;
      var enhanced = !!opts.enhance;
      var quad = full(work);
      var display = canvas(1, 1);
      var dscale = 1;
      var poly = document.createElementNS('http://www.w3.org/2000/svg', 'polygon');
      var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
      svg.setAttribute('class', 'editor-overlay');
      svg.appendChild(poly);
      var handles = [0, 1, 2, 3].map(function (i) { return el('div', { class: 'handle', dataset: { i: String(i) }, role: 'slider', 'aria-label': 'زاوية ' + (i + 1) }); });
      var loupeCanvas = canvas(220, 220);
      var loupe = el('div', { class: 'loupe' }, loupeCanvas);
      var wrap = el('div', { class: 'editor-canvas-wrap' }, display, svg, handles, loupe);
      var stage = el('div', { class: 'editor-stage' }, wrap);
      var enhBtn = tool('wand', 'تحسين', function () { enhanced = !enhanced; enhBtn.classList.toggle('is-on', enhanced); draw(); });
      enhBtn.classList.toggle('is-on', enhanced);
      var root = el('div', { class: 'editor', role: 'dialog', 'aria-label': 'تعديل الصورة' },
        el('div', { class: 'editor-top' },
          el('button', { class: 'btn btn-icon', type: 'button', 'aria-label': 'إلغاء', on: { click: function () { finish(null); } } }, K.icon('x')),
          el('div', { class: 't', text: opts.title || 'عدّل الصورة' }),
          el('button', { class: 'btn btn-primary btn-sm', type: 'button', on: { click: done } }, K.icon('check'), 'تم')),
        stage,
        el('div', { class: 'editor-hint', text: 'اسحب النقاط الخضراء على زوايا الكشف حتى نعدّل الصورة ونقصّها' }),
        el('div', { class: 'editor-bar' },
          tool('rotr', 'تدوير', function () { work = rotate(work, 1); quad = full(work); draw(); }),
          tool('rotl', 'تدوير', function () { work = rotate(work, -1); quad = full(work); draw(); }),
          tool('crop', 'كامل الصورة', function () { quad = full(work); place(); }),
          enhBtn));

      function tool(ic, label, fn) {
        return el('button', { class: 'tool', type: 'button', on: { click: fn } }, K.icon(ic), label);
      }
      function full(c) {
        var ix = c.width * 0.02, iy = c.height * 0.02;
        return [{ x: ix, y: iy }, { x: c.width - ix, y: iy }, { x: c.width - ix, y: c.height - iy }, { x: ix, y: c.height - iy }];
      }
      function draw() {
        var rect = stage.getBoundingClientRect();
        var aw = Math.max(100, rect.width - 40), ah = Math.max(100, rect.height - 40);
        dscale = Math.min(aw / work.width, ah / work.height, 1.5);
        display.width = Math.round(work.width * dscale);
        display.height = Math.round(work.height * dscale);
        var g = ctx2d(display);
        g.imageSmoothingQuality = 'high';
        g.drawImage(work, 0, 0, display.width, display.height);
        if (enhanced) {
          var e2 = enhance(display);
          g.drawImage(e2, 0, 0);
        }
        display.style.width = display.width + 'px';
        display.style.height = display.height + 'px';
        svg.setAttribute('viewBox', '0 0 ' + display.width + ' ' + display.height);
        svg.setAttribute('width', display.width);
        svg.setAttribute('height', display.height);
        place();
      }
      function place() {
        handles.forEach(function (hd, i) {
          hd.style.left = quad[i].x * dscale + 'px';
          hd.style.top = quad[i].y * dscale + 'px';
        });
        poly.setAttribute('points', quad.map(function (p) { return (p.x * dscale).toFixed(1) + ',' + (p.y * dscale).toFixed(1); }).join(' '));
      }
      var dragging = -1;
      function onDown(e) {
        var i = Number(e.currentTarget.dataset.i);
        dragging = i;
        e.currentTarget.classList.add('is-drag');
        try { e.currentTarget.setPointerCapture(e.pointerId); } catch (x) { /* */ }
        e.preventDefault();
        onMove(e);
      }
      function onMove(e) {
        if (dragging < 0) return;
        var r = display.getBoundingClientRect();
        var x = (e.clientX - r.left) / dscale, y = (e.clientY - r.top) / dscale;
        x = Math.max(0, Math.min(work.width, x));
        y = Math.max(0, Math.min(work.height, y));
        quad[dragging] = { x: x, y: y };
        place();
        // عدسة تكبير فوق الإصبع
        loupe.style.display = 'block';
        var lx = x * dscale, ly = y * dscale - 90;
        if (ly < 0) ly = y * dscale + 90;
        loupe.style.left = (lx - 55) + 'px';
        loupe.style.top = (ly - 55) + 'px';
        var lg = ctx2d(loupeCanvas);
        lg.fillStyle = '#000';
        lg.fillRect(0, 0, 220, 220);
        var z = 3 / dscale;
        var sw = 220 / (dscale * 3);
        lg.drawImage(work, x - sw / 2, y - sw / 2, sw, sw, 0, 0, 220, 220);
        lg.strokeStyle = '#10b981';
        lg.lineWidth = 2;
        lg.beginPath(); lg.moveTo(110, 90); lg.lineTo(110, 130); lg.moveTo(90, 110); lg.lineTo(130, 110); lg.stroke();
        void z;
      }
      function onUp(e) {
        if (dragging >= 0) handles[dragging].classList.remove('is-drag');
        dragging = -1;
        loupe.style.display = 'none';
        try { e.currentTarget.releasePointerCapture(e.pointerId); } catch (x) { /* */ }
      }
      handles.forEach(function (hd) {
        hd.addEventListener('pointerdown', onDown);
        hd.addEventListener('pointermove', onMove);
        hd.addEventListener('pointerup', onUp);
        hd.addEventListener('pointercancel', onUp);
      });
      function isFull() {
        var f = full(work);
        var tol = Math.max(work.width, work.height) * 0.015;
        for (var i = 0; i < 4; i++) if (dist(f[i], quad[i]) > tol) return false;
        return true;
      }
      function done() {
        var btn = this;
        K.busy(btn, true);
        setTimeout(function () {
          var outC = work;
          try {
            if (!isFull()) outC = warp(work, quad);
            if (enhanced) outC = enhance(outC);
          } catch (e) {
            K.toast('ما قدرنا نعدّل الصورة، رح نستخدمها كما هي', 'warn');
          }
          finish(outC);
        }, 40);
      }
      function onResize() { draw(); }
      function finish(val) {
        window.removeEventListener('resize', onResize);
        document.body.classList.remove('no-scroll');
        root.remove();
        resolve(val);
      }
      document.body.appendChild(root);
      document.body.classList.add('no-scroll');
      window.addEventListener('resize', onResize);
      requestAnimationFrame(draw);
    });
  }

  K.img = {
    load: load,
    compress: compress,
    thumb: thumb,
    rotate: rotate,
    warp: warp,
    enhance: enhance,
    strip: strip,
    edit: edit,
    blobToCanvas: blobToCanvas,
    MAX_SIDE: MAX_SIDE
  };
})();
