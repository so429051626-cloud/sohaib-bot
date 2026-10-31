/* ==========================================================
   MADIX Convert — تصدير PDF بدقة عالية (حوالي 216 DPI)
   الجدول بيترسم على الجهاز بخط عربي واضح وبيتحول لصفحات A4 جاهزة للطباعة
   بدون أي مكتبات خارجية
   Py | Sohaib
   ========================================================== */
(function () {
  'use strict';
  var K = window.K;
  var PT = 3; // بكسل لكل نقطة PDF ≈ 216 DPI
  var FONT = '"IBM Plex Sans Arabic", "Segoe UI", Tahoma, sans-serif';
  var MONO = 'ui-monospace, "SF Mono", Menlo, Consolas, monospace';
  var C = {
    ink: '#0f172a', muted: '#64748b', line: '#d6dde6', head: '#059669', headInk: '#ffffff',
    zebra: '#f7f9fb', dup: '#fdf0d2', dupBar: '#d97706', bad: '#fde4e4', badInk: '#b91c1c', unclear: '#fef9c3', miss: '#e0eafd', brand: '#047857'
  };

  // ---------- كاتب PDF بسيط يدعم صور JPEG ----------
  var enc = new TextEncoder();
  function utf16hex(s) {
    var h = 'FEFF';
    for (var i = 0; i < s.length; i++) h += ('0000' + s.charCodeAt(i).toString(16)).slice(-4).toUpperCase();
    return '<' + h + '>';
  }
  function buildPdf(pages, meta) {
    // pages: [{w, h (نقاط), jpeg: Uint8Array, pw, ph (بكسل)}]
    var parts = [];
    var offsets = [];
    var size = 0;
    function push(x) { var b = typeof x === 'string' ? enc.encode(x) : x; parts.push(b); size += b.length; }
    function obj(n, body, stream) {
      offsets[n] = size;
      push(n + ' 0 obj\n');
      push(body);
      if (stream) { push('\nstream\n'); push(stream); push('\nendstream'); }
      push('\nendobj\n');
    }
    push('%PDF-1.4\n%\xE2\xE3\xCF\xD3\n');
    var n = pages.length;
    var kids = [];
    for (var i = 0; i < n; i++) kids.push((3 + i * 3) + ' 0 R');
    obj(1, '<< /Type /Catalog /Pages 2 0 R /ViewerPreferences << /Direction /R2L >> >>');
    obj(2, '<< /Type /Pages /Kids [' + kids.join(' ') + '] /Count ' + n + ' >>');
    pages.forEach(function (p, i) {
      var pg = 3 + i * 3, im = pg + 1, ct = pg + 2;
      var w = p.w.toFixed(2), h = p.h.toFixed(2);
      obj(pg, '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ' + w + ' ' + h + '] /Resources << /XObject << /Im0 ' + im + ' 0 R >> /ProcSet [/PDF /ImageC] >> /Contents ' + ct + ' 0 R >>');
      obj(im, '<< /Type /XObject /Subtype /Image /Width ' + p.pw + ' /Height ' + p.ph + ' /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ' + p.jpeg.length + ' >>', p.jpeg);
      var c = 'q ' + w + ' 0 0 ' + h + ' 0 0 cm /Im0 Do Q';
      obj(ct, '<< /Length ' + c.length + ' >>', enc.encode(c));
    });
    var info = 3 + n * 3;
    var d = new Date();
    var pd = 'D:' + d.getUTCFullYear() + ('0' + (d.getUTCMonth() + 1)).slice(-2) + ('0' + d.getUTCDate()).slice(-2) + ('0' + d.getUTCHours()).slice(-2) + ('0' + d.getUTCMinutes()).slice(-2) + '00Z';
    obj(info, '<< /Title ' + utf16hex(meta.title || 'كشف') + ' /Author ' + utf16hex(meta.author || 'MADIX Convert') + ' /Producer (MADIX Convert - Py | Sohaib) /CreationDate (' + pd + ') >>');
    var xref = size;
    var total = info + 1;
    var x = 'xref\n0 ' + total + '\n0000000000 65535 f \n';
    for (var k = 1; k < total; k++) x += ('0000000000' + offsets[k]).slice(-10) + ' 00000 n \n';
    push(x);
    push('trailer\n<< /Size ' + total + ' /Root 1 0 R /Info ' + info + ' 0 R >>\nstartxref\n' + xref + '\n%%EOF\n');
    return new Blob(parts, { type: 'application/pdf' });
  }

  function toJpeg(canvas, q) {
    return new Promise(function (resolve, reject) {
      canvas.toBlob(function (b) {
        if (!b) return reject(new Error('تعذّر إنشاء صفحة PDF'));
        b.arrayBuffer().then(function (ab) { resolve(new Uint8Array(ab)); }, reject);
      }, 'image/jpeg', q);
    });
  }

  function loadImg(src) {
    return new Promise(function (resolve) {
      var im = new Image();
      im.onload = function () { resolve(im); };
      im.onerror = function () { resolve(null); };
      im.src = src;
    });
  }

  // ---------- رسم الجدول ----------
  function fit(g, text, maxW) {
    if (g.measureText(text).width <= maxW) return text;
    var s = text;
    while (s.length > 1 && g.measureText(s + '…').width > maxW) s = s.slice(0, -1);
    return s + '…';
  }

  /**
   * يبني PDF للكشف
   * opts: {appName, iconUrl}
   */
  function fromStatement(st, A, opts) {
    opts = opts || {};
    var cols = st.columns;
    var rows = A.rows;
    var landscape = cols.length > 5;
    var PW = landscape ? 841.89 : 595.28, PH = landscape ? 595.28 : 841.89;
    var M = 28; // الهوامش بالنقاط
    var fs = cols.length > 8 ? 8.5 : 9.5; // حجم الخط
    var rowH = fs * 2.15;
    var headH = rowH * 1.25;
    var ready = (document.fonts && document.fonts.ready) ? document.fonts.ready.catch(function () { }) : Promise.resolve();

    return ready.then(function () { return opts.iconUrl ? loadImg(opts.iconUrl) : null; }).then(function (icon) {
      var meas = document.createElement('canvas').getContext('2d');
      function setFont(g, w, size, mono) { g.font = w + ' ' + (size * PT) + 'px ' + (mono ? MONO : FONT); }

      // عرض الأعمدة
      var avail = PW - M * 2;
      var numW = Math.max(26, fs * 3.2);
      setFont(meas, '600', fs);
      var widths = cols.map(function (c) {
        var isMono = c.kind === 'national_id' || c.kind === 'phone';
        var w = meas.measureText(c.label).width / PT + 16;
        setFont(meas, '400', fs, isMono);
        for (var i = 0; i < rows.length; i++) {
          var v = rows[i].cells[c.id] != null ? String(rows[i].cells[c.id]) : '';
          var mw = meas.measureText(v).width / PT + 14;
          if (mw > w) w = mw;
        }
        setFont(meas, '600', fs);
        return Math.max(40, Math.min(w, c.kind === 'name' ? 230 : c.kind === 'text' ? 200 : 140));
      });
      var sum = widths.reduce(function (a, b) { return a + b; }, 0) + numW;
      if (sum > avail) {
        var over = sum - avail;
        var flex = widths.map(function (w) { return Math.max(0, w - 46); });
        var flexSum = flex.reduce(function (a, b) { return a + b; }, 0) || 1;
        widths = widths.map(function (w, i) { return w - over * (flex[i] / flexSum); });
      } else {
        var extra = (avail - sum);
        var nameIdx = cols.findIndex(function (c) { return c.kind === 'name'; });
        if (nameIdx >= 0) widths[nameIdx] += extra; else widths = widths.map(function (w) { return w + extra / widths.length; });
      }

      var topH = 74; // رأس الصفحة الأولى
      var topH2 = 34; // رأس الصفحات التالية
      var footH = 30;
      var firstRows = Math.max(1, Math.floor((PH - M * 2 - topH - headH - footH) / rowH));
      var nextRows = Math.max(1, Math.floor((PH - M * 2 - topH2 - headH - footH) / rowH));
      var chunks = [];
      var i0 = 0;
      chunks.push(rows.slice(0, firstRows));
      i0 = firstRows;
      while (i0 < rows.length) { chunks.push(rows.slice(i0, i0 + nextRows)); i0 += nextRows; }
      var totalPages = chunks.length;
      var c = A.counts;
      var dateStr = K.fmtDate(new Date());
      var out = [];

      function drawPage(pi) {
        var cv = document.createElement('canvas');
        cv.width = Math.round(PW * PT);
        cv.height = Math.round(PH * PT);
        var g = cv.getContext('2d');
        g.fillStyle = '#ffffff';
        g.fillRect(0, 0, cv.width, cv.height);
        g.textBaseline = 'middle';
        var X = function (pt) { return pt * PT; };
        var right = PW - M;
        var y = M;

        if (pi === 0) {
          // الشعار والعنوان
          if (icon) { try { g.drawImage(icon, X(right - 40), X(y), X(40), X(40)); } catch (e) { /* */ } }
          g.direction = 'rtl';
          g.textAlign = 'right';
          g.fillStyle = C.ink;
          setFont(g, '700', 17);
          g.fillText(fit(g, st.title || 'كشف', X(avail - 220)), X(right - (icon ? 50 : 0)), X(y + 13));
          setFont(g, '400', 9);
          g.fillStyle = C.muted;
          g.fillText((opts.appName || 'MADIX Convert') + ' — ' + dateStr, X(right - (icon ? 50 : 0)), X(y + 32));
          // ملخص على اليسار
          g.textAlign = 'left';
          g.direction = 'rtl';
          var chipsTxt = [
            ['الصفوف', c.rows, C.ink],
            ['مكرر', c.dupRows, c.dupRows ? C.dupBar : C.ink],
            ['هوية غير صحيحة', c.badRows, c.badRows ? C.badInk : C.ink],
            ['تحتاج مراجعة', c.unclearRows, C.ink]
          ];
          var cx = M;
          chipsTxt.forEach(function (t) {
            setFont(g, '700', 12);
            g.fillStyle = t[2];
            var numTxt = String(t[1]);
            var nw = g.measureText(numTxt).width / PT;
            setFont(g, '400', 8);
            var lw = g.measureText(t[0]).width / PT;
            var bw = Math.max(nw, lw) + 16;
            g.fillStyle = '#f3f6f9';
            roundRect(g, X(cx), X(y + 2), X(bw), X(36), X(6));
            g.fill();
            g.textAlign = 'center';
            setFont(g, '700', 12);
            g.fillStyle = t[2];
            g.fillText(numTxt, X(cx + bw / 2), X(y + 14));
            setFont(g, '400', 8);
            g.fillStyle = C.muted;
            g.fillText(t[0], X(cx + bw / 2), X(y + 30));
            cx += bw + 6;
          });
          y += 50;
          g.fillStyle = C.brand;
          g.fillRect(X(M), X(y), X(avail), X(1.6));
          y += topH - 50;
        } else {
          g.direction = 'rtl';
          g.textAlign = 'right';
          setFont(g, '600', 10);
          g.fillStyle = C.ink;
          g.fillText(fit(g, st.title || 'كشف', X(avail - 60)), X(right), X(y + 10));
          g.textAlign = 'left';
          setFont(g, '400', 8.5);
          g.fillStyle = C.muted;
          g.fillText('تابع', X(M), X(y + 10));
          y += 20;
          g.fillStyle = C.brand;
          g.fillRect(X(M), X(y), X(avail), X(1.2));
          y += topH2 - 20;
        }

        // رأس الجدول
        var xs = [];
        var xr = right;
        xs.push([xr - numW, numW]);
        xr -= numW;
        widths.forEach(function (w) { xs.push([xr - w, w]); xr -= w; });
        g.fillStyle = C.head;
        roundRect(g, X(M), X(y), X(avail), X(headH), X(5), true);
        g.fill();
        g.fillStyle = C.headInk;
        g.textAlign = 'center';
        g.direction = 'rtl';
        setFont(g, '700', fs);
        g.fillText('#', X(xs[0][0] + xs[0][1] / 2), X(y + headH / 2));
        cols.forEach(function (col, ci) {
          var b = xs[ci + 1];
          g.fillText(fit(g, col.label, X(b[1] - 8)), X(b[0] + b[1] / 2), X(y + headH / 2));
        });
        y += headH;

        // الصفوف
        chunks[pi].forEach(function (r, ri) {
          var f = A.flags[r.id];
          var bg = f.dup ? C.dup : (ri % 2 ? C.zebra : '#ffffff');
          g.fillStyle = bg;
          g.fillRect(X(M), X(y), X(avail), X(rowH));
          if (f.dup) { g.fillStyle = C.dupBar; g.fillRect(X(right - 2.2), X(y), X(2.2), X(rowH)); }
          // رقم الصف
          g.textAlign = 'center';
          g.direction = 'ltr';
          setFont(g, '600', fs - 0.5);
          g.fillStyle = f.dup ? C.dupBar : C.muted;
          g.fillText(String(f.n) + (f.dup ? ' ×' + f.dup : ''), X(xs[0][0] + xs[0][1] / 2), X(y + rowH / 2));
          cols.forEach(function (col, ci) {
            var b = xs[ci + 1];
            var v = r.cells[col.id] != null ? String(r.cells[col.id]) : '';
            var isLtr = col.kind === 'national_id' || col.kind === 'phone' || col.kind === 'number' || col.kind === 'serial';
            if (f.bad[col.id]) { g.fillStyle = C.bad; g.fillRect(X(b[0]), X(y), X(b[1]), X(rowH)); }
            else if (f.miss && f.miss[col.id]) { g.fillStyle = C.miss; g.fillRect(X(b[0]), X(y), X(b[1]), X(rowH)); }
            else if (f.unclear[col.id]) { g.fillStyle = C.unclear; g.fillRect(X(b[0]), X(y), X(b[1]), X(rowH)); }
            g.fillStyle = f.bad[col.id] ? C.badInk : C.ink;
            setFont(g, f.bad[col.id] ? '700' : '400', fs, col.kind === 'national_id' || col.kind === 'phone');
            g.direction = isLtr ? 'ltr' : 'rtl';
            g.textAlign = 'right';
            g.fillText(fit(g, v, X(b[1] - 10)), X(b[0] + b[1] - 5), X(y + rowH / 2 + 0.5));
          });
          // خط فاصل
          g.fillStyle = C.line;
          g.fillRect(X(M), X(y + rowH - 0.5), X(avail), X(0.5));
          y += rowH;
        });
        // خطوط الأعمدة
        g.fillStyle = C.line;
        var yTop = (pi === 0 ? M + topH : M + topH2) + headH;
        xs.slice(1).forEach(function (b) { g.fillRect(X(b[0] + b[1]), X(yTop), X(0.5), X(y - yTop)); });
        g.strokeStyle = C.line;
        g.lineWidth = X(0.6);
        g.strokeRect(X(M), X(yTop - headH), X(avail), X(y - yTop + headH));

        // دليل الألوان بآخر صفحة
        if (pi === totalPages - 1 && y + 24 < PH - M - footH) {
          y += 12;
          var lx = right;
          g.direction = 'rtl';
          g.textAlign = 'right';
          [[C.dup, 'مكرر برقم الهوية'], [C.bad, 'رقم هوية غير صحيح'], [C.miss, 'خانة ناقصة'], [C.unclear, 'خانة غير واضحة']].forEach(function (lg) {
            g.fillStyle = lg[0];
            g.fillRect(X(lx - 10), X(y), X(10), X(10));
            setFont(g, '400', 8.5);
            g.fillStyle = C.muted;
            g.fillText(lg[1], X(lx - 14), X(y + 5.5));
            lx -= 14 + g.measureText(lg[1]).width / PT + 18;
          });
        }

        // التذييل
        var fy = PH - M - 8;
        g.fillStyle = C.line;
        g.fillRect(X(M), X(fy - 12), X(avail), X(0.6));
        g.direction = 'rtl';
        g.textAlign = 'right';
        setFont(g, '400', 8);
        g.fillStyle = C.muted;
        g.fillText((opts.appName || 'MADIX Convert') + ' — محوّل الكشوفات الذكي', X(right), X(fy));
        g.textAlign = 'center';
        g.direction = 'ltr';
        g.fillText((pi + 1) + ' / ' + totalPages, X(PW / 2), X(fy));
        g.textAlign = 'left';
        setFont(g, '700', 8.5, true);
        g.fillStyle = C.brand;
        g.fillText('Py | Sohaib', X(M), X(fy));
        return cv;
      }

      // نرسم الصفحات وحدة وحدة لتوفير الذاكرة على الجوال
      var chain = Promise.resolve();
      chunks.forEach(function (_, pi) {
        chain = chain.then(function () {
          var cv = drawPage(pi);
          return toJpeg(cv, 0.93).then(function (jpeg) {
            out.push({ w: PW, h: PH, pw: cv.width, ph: cv.height, jpeg: jpeg });
            cv.width = cv.height = 1;
            if (opts.onProgress) opts.onProgress((pi + 1) / totalPages);
          });
        });
      });
      return chain.then(function () { return buildPdf(out, { title: st.title, author: (opts.appName || 'MADIX Convert') + ' — Py | Sohaib' }); });
    });
  }

  function roundRect(g, x, y, w, h, r, topOnly) {
    g.beginPath();
    g.moveTo(x + r, y);
    g.lineTo(x + w - r, y);
    g.quadraticCurveTo(x + w, y, x + w, y + r);
    if (topOnly) { g.lineTo(x + w, y + h); g.lineTo(x, y + h); }
    else {
      g.lineTo(x + w, y + h - r);
      g.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
      g.lineTo(x + r, y + h);
      g.quadraticCurveTo(x, y + h, x, y + h - r);
    }
    g.lineTo(x, y + r);
    g.quadraticCurveTo(x, y, x + r, y);
    g.closePath();
  }

  K.pdf = { fromStatement: fromStatement, build: buildPdf };
})();
