/* ==========================================================
   MADIX Convert — التحليل: فحص رقم الهوية، كشف المكررات، توحيد الأسماء، دمج الكشوفات
   Py | Sohaib
   ========================================================== */
(function () {
  'use strict';
  var K = window.K;

  var KINDS = {
    serial: 'تسلسل',
    name: 'اسم',
    national_id: 'رقم هوية',
    phone: 'رقم جوال',
    number: 'رقم',
    date: 'تاريخ',
    text: 'نص'
  };

  function onlyDigits(v) { return K.digits(v).replace(/\D+/g, ''); }

  /** رقم الهوية: أرقام فقط، و8 أرقام تصير 9 بإضافة صفر بالبداية */
  function normId(v) {
    var d = onlyDigits(v);
    if (d.length === 8) d = '0' + d;
    return d;
  }

  /** خوارزمية رقم التحقق لهوية فلسطينية (9 أرقام) */
  function idChecksum(d) {
    if (!/^\d{9}$/.test(d)) return false;
    var sum = 0;
    for (var i = 0; i < 9; i++) {
      var n = Number(d[i]) * ((i % 2) + 1);
      sum += n > 9 ? n - 9 : n;
    }
    return sum % 10 === 0;
  }

  /** حالة رقم الهوية: '' فارغ، ok صحيح، len عدد أرقام غلط، sum رقم التحقق غلط */
  function idStatus(v) {
    var raw = K.digits(String(v == null ? '' : v)).trim();
    if (!raw) return '';
    var d = normId(raw);
    if (!d) return 'len';
    if (d.length !== 9) return 'len';
    if (/^0+$/.test(d)) return 'sum';
    return idChecksum(d) ? 'ok' : 'sum';
  }
  var ID_MSG = { ok: 'رقم هوية صحيح', len: 'رقم الهوية لازم يكون 9 أرقام', sum: 'رقم الهوية غير صحيح (رقم التحقق غلط) — غالباً في رقم انكتب أو انقرأ غلط' };

  function normPhone(v) {
    var d = onlyDigits(v);
    if (d.indexOf('00') === 0) d = d.slice(2);
    if ((d.indexOf('970') === 0 || d.indexOf('972') === 0) && d.length >= 12) d = d.slice(3);
    if (d.length > 9) d = d.slice(-9);
    return d.length >= 7 ? d : '';
  }

  /** توحيد الاسم العربي للمقارنة: الهمزات، التاء المربوطة، الياء، التشكيل، عبد/أبو */
  function normName(v) {
    var s = String(v == null ? '' : v);
    s = s.replace(/[ً-ٰٟـ]/g, '');
    s = s.replace(/[إأآٱ]/g, 'ا').replace(/ة/g, 'ه').replace(/ى/g, 'ي').replace(/ؤ/g, 'و').replace(/ئ/g, 'ي').replace(/ء/g, '');
    s = s.replace(/[^ء-يa-zA-Z\s]/g, ' ');
    s = s.replace(/\s+/g, ' ').trim();
    s = s.replace(/(^|\s)(عبد|ابو|أبو)\s+(?=\S)/g, '$1$2');
    s = s.replace(/(^|\s)ال(?=\S)/g, '$1');
    return s.toLowerCase();
  }

  function cellOf(row, col) { return row.cells && row.cells[col.id] != null ? String(row.cells[col.id]) : ''; }

  /**
   * يحلل الكشف ويرجع علامات كل صف ومجموعات المكررات والإحصائيات
   * opts.ext: Map من رقم هوية موحد → قائمة أسماء كشوفات ثانية
   */
  function analyze(st, opts) {
    opts = opts || {};
    var cols = st.columns || [];
    var rows = (st.rows || []).filter(function (r) {
      if (r.deleted) return false;
      if (opts.skipEmpty) return cols.some(function (c) { return String(r.cells[c.id] == null ? '' : r.cells[c.id]).trim() !== ''; });
      return true;
    });
    var idCols = cols.filter(function (c) { return c.kind === 'national_id'; });
    var nameCol = cols.filter(function (c) { return c.kind === 'name'; })[0] || null;
    var phoneCols = cols.filter(function (c) { return c.kind === 'phone'; });
    var reqCols = cols.filter(function (c) { return c.kind === 'name' || c.kind === 'national_id'; });

    var flags = {};
    var idMap = {}, nameMap = {}, phoneMap = {};
    var counts = { rows: rows.length, dupRows: 0, dupGroups: 0, bad: 0, badRows: 0, unclear: 0, unclearRows: 0, nameGroups: 0, nameRows: 0, phoneGroups: 0, ext: 0, missRows: 0 };

    rows.forEach(function (r, idx) {
      var f = flags[r.id] = { n: idx + 1, dup: 0, bad: {}, unclear: {}, miss: {}, ext: null, nameDup: 0, phoneDup: 0, ids: [] };
      var anyMiss = false;
      reqCols.forEach(function (c) { if (!cellOf(r, c).trim()) { f.miss[c.id] = true; anyMiss = true; } });
      if (anyMiss) counts.missRows++;
      (r.unclear || []).forEach(function (cid) { f.unclear[cid] = true; });
      var uc = Object.keys(f.unclear).length;
      if (uc) { counts.unclear += uc; counts.unclearRows++; }
      var anyBad = false;
      idCols.forEach(function (c) {
        var v = cellOf(r, c);
        var stt = idStatus(v);
        if (stt && stt !== 'ok') { f.bad[c.id] = stt; counts.bad++; anyBad = true; }
        var nid = normId(v);
        if (nid.length >= 5 && f.ids.indexOf(nid) < 0) {
          f.ids.push(nid);
          (idMap[nid] = idMap[nid] || []).push(r.id);
        }
      });
      if (anyBad) counts.badRows++;
      if (nameCol) {
        var nn = normName(cellOf(r, nameCol));
        if (nn && nn.split(' ').length >= 2) (nameMap[nn] = nameMap[nn] || []).push(r.id);
      }
      phoneCols.forEach(function (c) {
        var p = normPhone(cellOf(r, c));
        if (p) (phoneMap[p] = phoneMap[p] || []).push(r.id);
      });
      if (opts.ext) {
        var hits = [];
        f.ids.forEach(function (nid) { var e = opts.ext.get(nid); if (e) hits = hits.concat(e); });
        if (hits.length) { f.ext = unique(hits); counts.ext++; }
      }
    });

    var groups = [];
    var gi = 0;
    Object.keys(idMap).forEach(function (k) {
      var ids = unique(idMap[k]);
      if (ids.length < 2) return;
      gi++;
      groups.push({ type: 'id', key: k, rows: ids, n: gi });
      ids.forEach(function (rid) { if (!flags[rid].dup) { flags[rid].dup = gi; counts.dupRows++; } });
    });
    counts.dupGroups = gi;

    function sameIdGroup(ids) {
      var g = flags[ids[0]].dup;
      if (!g) return false;
      for (var i = 1; i < ids.length; i++) if (flags[ids[i]].dup !== g) return false;
      return true;
    }
    var ng = 0;
    Object.keys(nameMap).forEach(function (k) {
      var ids = unique(nameMap[k]);
      if (ids.length < 2 || sameIdGroup(ids)) return;
      ng++;
      groups.push({ type: 'name', key: k, rows: ids, n: ng });
      ids.forEach(function (rid) { if (!flags[rid].nameDup) { flags[rid].nameDup = ng; counts.nameRows++; } });
    });
    counts.nameGroups = ng;
    var pg = 0;
    Object.keys(phoneMap).forEach(function (k) {
      var ids = unique(phoneMap[k]);
      if (ids.length < 2 || sameIdGroup(ids)) return;
      pg++;
      groups.push({ type: 'phone', key: k, rows: ids, n: pg });
      ids.forEach(function (rid) { flags[rid].phoneDup = pg; });
    });
    counts.phoneGroups = pg;

    return { flags: flags, groups: groups, counts: counts, rows: rows, idCols: idCols, nameCol: nameCol, phoneCols: phoneCols };
  }

  function unique(a) { var s = {}, out = []; a.forEach(function (x) { if (!s[x]) { s[x] = 1; out.push(x); } }); return out; }

  function stats(st) {
    var a = analyze(st, { skipEmpty: true });
    return { miss: a.counts.missRows, rows: a.counts.rows, dups: a.counts.dupRows, dupGroups: a.counts.dupGroups, bad: a.counts.badRows, unclear: a.counts.unclearRows, names: a.counts.nameGroups };
  }

  /** فهرس أرقام الهوية من كشوفات ثانية (للفحص بين الكشوفات) */
  function extIndex(list, exceptId) {
    var map = new Map();
    list.forEach(function (st) {
      if (st.id === exceptId) return;
      var idCols = (st.columns || []).filter(function (c) { return c.kind === 'national_id'; });
      (st.rows || []).forEach(function (r) {
        if (r.deleted) return;
        idCols.forEach(function (c) {
          var nid = normId(cellOf(r, c));
          if (nid.length < 5) return;
          var arr = map.get(nid);
          if (!arr) map.set(nid, arr = []);
          if (arr.indexOf(st.title) < 0) arr.push(st.title);
        });
      });
    });
    return map;
  }

  function normLabel(s) { return normName(s).replace(/\s+/g, ''); }

  /**
   * يربط أعمدة صفحة جديدة بأعمدة الكشف الحالية
   * يرجع: مصفوفة colId لكل عمود جديد (ويضيف أعمدة جديدة عند الحاجة)
   */
  function mapColumns(st, newCols) {
    var cols = st.columns;
    if (!cols.length) {
      return newCols.map(function (c) {
        var col = { id: K.uid(), label: c.label, kind: c.kind };
        cols.push(col);
        return col.id;
      });
    }
    if (newCols.length === cols.length) {
      var allKindsMatch = newCols.every(function (c, i) { return c.kind === cols[i].kind || c.kind === 'text' || cols[i].kind === 'text'; });
      if (allKindsMatch) return cols.map(function (c) { return c.id; });
    }
    var used = {};
    return newCols.map(function (c) {
      var nl = normLabel(c.label);
      var m = cols.filter(function (x) { return !used[x.id] && normLabel(x.label) === nl; })[0];
      if (!m && c.kind !== 'text' && c.kind !== 'number') {
        var same = cols.filter(function (x) { return !used[x.id] && x.kind === c.kind; });
        if (same.length === 1) m = same[0];
      }
      if (!m) {
        m = { id: K.uid(), label: c.label, kind: c.kind };
        cols.push(m);
      }
      used[m.id] = true;
      return m.id;
    });
  }

  /** دمج عدة كشوفات في كشف واحد مع عمود "المصدر" */
  function merge(list, title) {
    var st = { columns: [], rows: [] };
    var srcCol = { id: K.uid(), label: 'المصدر', kind: 'text' };
    list.forEach(function (s) {
      var map = mapColumns(st, s.columns.map(function (c) { return { label: c.label, kind: c.kind }; }));
      (s.rows || []).forEach(function (r) {
        if (r.deleted) return;
        var cells = {};
        s.columns.forEach(function (c, i) { cells[map[i]] = r.cells[c.id] != null ? r.cells[c.id] : ''; });
        cells[srcCol.id] = s.title;
        st.rows.push({ id: K.uid(), page: r.page, srcSt: r.srcSt || s.id, cells: cells, unclear: (r.unclear || []).map(function (cid) { return map[s.columns.findIndex(function (c) { return c.id === cid; })]; }).filter(Boolean), y: r.y || null });
      });
    });
    st.columns.push(srcCol);
    st.title = title;
    return st;
  }

  K.an = {
    KINDS: KINDS,
    normId: normId,
    idStatus: idStatus,
    idChecksum: idChecksum,
    ID_MSG: ID_MSG,
    normPhone: normPhone,
    normName: normName,
    analyze: analyze,
    stats: stats,
    extIndex: extIndex,
    mapColumns: mapColumns,
    merge: merge,
    cellOf: cellOf
  };
})();
