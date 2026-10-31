/* ==========================================================
   MADIX Convert — إنشاء ملف Excel (xlsx) حقيقي على الجهاز بدون أي مكتبات خارجية
   ملف مضغوط ZIP + أوراق منسقة من اليمين لليسار + ألوان للمكررات والأخطاء
   Py | Sohaib
   ========================================================== */
(function () {
  'use strict';
  var K = window.K;

  // ---------- ZIP (تخزين بدون ضغط — يفتح بكل البرامج) ----------
  var CRC_TABLE = (function () {
    var t = new Uint32Array(256);
    for (var n = 0; n < 256; n++) {
      var c = n;
      for (var k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      t[n] = c >>> 0;
    }
    return t;
  })();
  function crc32(bytes) {
    var c = 0xffffffff;
    for (var i = 0; i < bytes.length; i++) c = CRC_TABLE[(c ^ bytes[i]) & 0xff] ^ (c >>> 8);
    return (c ^ 0xffffffff) >>> 0;
  }
  var enc = new TextEncoder();

  function zip(files) {
    var now = new Date();
    var dosTime = ((now.getHours() << 11) | (now.getMinutes() << 5) | (Math.floor(now.getSeconds() / 2))) & 0xffff;
    var dosDate = (((now.getFullYear() - 1980) << 9) | ((now.getMonth() + 1) << 5) | now.getDate()) & 0xffff;
    var parts = [], central = [], offset = 0;
    files.forEach(function (f) {
      var name = enc.encode(f.name);
      var data = typeof f.data === 'string' ? enc.encode(f.data) : f.data;
      var crc = crc32(data);
      var lh = new DataView(new ArrayBuffer(30));
      lh.setUint32(0, 0x04034b50, true);
      lh.setUint16(4, 20, true);
      lh.setUint16(6, 0x0800, true);
      lh.setUint16(8, 0, true);
      lh.setUint16(10, dosTime, true);
      lh.setUint16(12, dosDate, true);
      lh.setUint32(14, crc, true);
      lh.setUint32(18, data.length, true);
      lh.setUint32(22, data.length, true);
      lh.setUint16(26, name.length, true);
      lh.setUint16(28, 0, true);
      parts.push(new Uint8Array(lh.buffer), name, data);
      var ch = new DataView(new ArrayBuffer(46));
      ch.setUint32(0, 0x02014b50, true);
      ch.setUint16(4, 20, true);
      ch.setUint16(6, 20, true);
      ch.setUint16(8, 0x0800, true);
      ch.setUint16(10, 0, true);
      ch.setUint16(12, dosTime, true);
      ch.setUint16(14, dosDate, true);
      ch.setUint32(16, crc, true);
      ch.setUint32(20, data.length, true);
      ch.setUint32(24, data.length, true);
      ch.setUint16(28, name.length, true);
      ch.setUint16(30, 0, true);
      ch.setUint16(32, 0, true);
      ch.setUint16(34, 0, true);
      ch.setUint16(36, 0, true);
      ch.setUint32(38, 0, true);
      ch.setUint32(42, offset, true);
      central.push(new Uint8Array(ch.buffer), name);
      offset += 30 + name.length + data.length;
    });
    var cdSize = central.reduce(function (s, p) { return s + p.length; }, 0);
    var end = new DataView(new ArrayBuffer(22));
    end.setUint32(0, 0x06054b50, true);
    end.setUint16(4, 0, true);
    end.setUint16(6, 0, true);
    end.setUint16(8, files.length, true);
    end.setUint16(10, files.length, true);
    end.setUint32(12, cdSize, true);
    end.setUint32(16, offset, true);
    end.setUint16(20, 0, true);
    return new Blob(parts.concat(central, [new Uint8Array(end.buffer)]), { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' });
  }

  // ---------- XLSX ----------
  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F￾￿]/g, '')
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }
  function colName(i) {
    var s = '';
    i++;
    while (i > 0) { var m = (i - 1) % 26; s = String.fromCharCode(65 + m) + s; i = Math.floor((i - 1) / 26); }
    return s;
  }
  function sheetName(n, used) {
    var s = String(n || 'ورقة').replace(/[\[\]:*?\/\\]/g, ' ').trim().slice(0, 31) || 'ورقة';
    var base = s, k = 2;
    while (used[s]) { s = base.slice(0, 28) + ' ' + k++; }
    used[s] = 1;
    return s;
  }

  // أنماط الخلايا (أرقامها مستخدمة في التطبيق)
  var ST = { def: 0, head: 1, cell: 2, dup: 3, bad: 4, unclear: 5, title: 6, muted: 7, key: 8, center: 9, dupCenter: 10, ok: 11, miss: 12 };
  var STYLES = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
    '<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">' +
    '<fonts count="7">' +
    '<font><sz val="11"/><name val="Arial"/><family val="2"/></font>' +
    '<font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Arial"/><family val="2"/></font>' +
    '<font><b/><sz val="11"/><color rgb="FFB91C1C"/><name val="Arial"/><family val="2"/></font>' +
    '<font><b/><sz val="15"/><color rgb="FF0B1220"/><name val="Arial"/><family val="2"/></font>' +
    '<font><i/><sz val="10"/><color rgb="FF6B7280"/><name val="Arial"/><family val="2"/></font>' +
    '<font><b/><sz val="11"/><name val="Arial"/><family val="2"/></font>' +
    '<font><b/><sz val="11"/><color rgb="FF047857"/><name val="Arial"/><family val="2"/></font>' +
    '</fonts>' +
    '<fills count="9">' +
    '<fill><patternFill patternType="none"/></fill>' +
    '<fill><patternFill patternType="gray125"/></fill>' +
    '<fill><patternFill patternType="solid"><fgColor rgb="FF059669"/><bgColor indexed="64"/></patternFill></fill>' +
    '<fill><patternFill patternType="solid"><fgColor rgb="FFFDF0D2"/><bgColor indexed="64"/></patternFill></fill>' +
    '<fill><patternFill patternType="solid"><fgColor rgb="FFFDE4E4"/><bgColor indexed="64"/></patternFill></fill>' +
    '<fill><patternFill patternType="solid"><fgColor rgb="FFFEF9C3"/><bgColor indexed="64"/></patternFill></fill>' +
    '<fill><patternFill patternType="solid"><fgColor rgb="FFF3F4F6"/><bgColor indexed="64"/></patternFill></fill>' +
    '<fill><patternFill patternType="solid"><fgColor rgb="FFD9F5E9"/><bgColor indexed="64"/></patternFill></fill>' +
    '<fill><patternFill patternType="solid"><fgColor rgb="FFE0EAFD"/><bgColor indexed="64"/></patternFill></fill>' +
    '</fills>' +
    '<borders count="2">' +
    '<border><left/><right/><top/><bottom/><diagonal/></border>' +
    '<border><left style="thin"><color rgb="FFD1D5DB"/></left><right style="thin"><color rgb="FFD1D5DB"/></right><top style="thin"><color rgb="FFD1D5DB"/></top><bottom style="thin"><color rgb="FFD1D5DB"/></bottom><diagonal/></border>' +
    '</borders>' +
    '<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>' +
    '<cellXfs count="13">' +
    '<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>' +
    '<xf numFmtId="0" fontId="1" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf>' +
    '<xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyAlignment="1"><alignment vertical="center"/></xf>' +
    '<xf numFmtId="0" fontId="0" fillId="3" borderId="1" xfId="0" applyFill="1" applyBorder="1" applyAlignment="1"><alignment vertical="center"/></xf>' +
    '<xf numFmtId="0" fontId="2" fillId="4" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment vertical="center"/></xf>' +
    '<xf numFmtId="0" fontId="0" fillId="5" borderId="1" xfId="0" applyFill="1" applyBorder="1" applyAlignment="1"><alignment vertical="center"/></xf>' +
    '<xf numFmtId="0" fontId="3" fillId="0" borderId="0" xfId="0" applyFont="1"/>' +
    '<xf numFmtId="0" fontId="4" fillId="0" borderId="0" xfId="0" applyFont="1"/>' +
    '<xf numFmtId="0" fontId="5" fillId="6" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1"/>' +
    '<xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf>' +
    '<xf numFmtId="0" fontId="0" fillId="3" borderId="1" xfId="0" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf>' +
    '<xf numFmtId="0" fontId="6" fillId="7" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1"/>' +
    '<xf numFmtId="0" fontId="0" fillId="8" borderId="1" xfId="0" applyFill="1" applyBorder="1" applyAlignment="1"><alignment vertical="center"/></xf>' +
    '</cellXfs>' +
    '<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>' +
    '</styleSheet>';

  /** خلية: {v: قيمة, n: true للأرقام, s: نمط} */
  function cellXml(ref, c) {
    if (c === null || c === undefined) return '';
    if (typeof c !== 'object') c = { v: c };
    var s = c.s ? ' s="' + c.s + '"' : '';
    var v = c.v == null ? '' : c.v;
    if (c.n && v !== '' && isFinite(Number(v))) return '<c r="' + ref + '"' + s + '><v>' + Number(v) + '</v></c>';
    if (v === '') return s ? '<c r="' + ref + '"' + s + '/>' : '';
    return '<c r="' + ref + '"' + s + ' t="inlineStr"><is><t xml:space="preserve">' + esc(v) + '</t></is></c>';
  }

  function sheetXml(sh) {
    var rows = sh.rows || [];
    var maxCols = 1;
    rows.forEach(function (r) { if (r && r.length > maxCols) maxCols = r.length; });
    var out = ['<?xml version="1.0" encoding="UTF-8" standalone="yes"?>',
      '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">',
      '<sheetPr><pageSetUpPr fitToPage="1"/></sheetPr>',
      '<dimension ref="A1:' + colName(maxCols - 1) + Math.max(1, rows.length) + '"/>',
      '<sheetViews><sheetView workbookViewId="0" rightToLeft="1"' + (sh.tabSelected ? ' tabSelected="1"' : '') + '>'];
    if (sh.freeze) {
      out.push('<pane ySplit="' + sh.freeze + '" topLeftCell="A' + (sh.freeze + 1) + '" activePane="bottomLeft" state="frozen"/><selection pane="bottomLeft" activeCell="A' + (sh.freeze + 1) + '" sqref="A' + (sh.freeze + 1) + '"/>');
    }
    out.push('</sheetView></sheetViews>', '<sheetFormatPr defaultRowHeight="19"/>');
    if (sh.widths && sh.widths.length) {
      out.push('<cols>');
      sh.widths.forEach(function (w, i) { out.push('<col min="' + (i + 1) + '" max="' + (i + 1) + '" width="' + Math.max(4, Math.min(70, w)).toFixed(1) + '" customWidth="1"/>'); });
      out.push('</cols>');
    }
    out.push('<sheetData>');
    rows.forEach(function (r, ri) {
      if (!r) return;
      var cells = '';
      for (var ci = 0; ci < r.length; ci++) cells += cellXml(colName(ci) + (ri + 1), r[ci]);
      var ht = sh.heights && sh.heights[ri] ? ' ht="' + sh.heights[ri] + '" customHeight="1"' : '';
      out.push('<row r="' + (ri + 1) + '"' + ht + '>' + cells + '</row>');
    });
    out.push('</sheetData>');
    if (sh.filter) out.push('<autoFilter ref="' + sh.filter + '"/>');
    if (sh.merges && sh.merges.length) {
      out.push('<mergeCells count="' + sh.merges.length + '">');
      sh.merges.forEach(function (m) { out.push('<mergeCell ref="' + m + '"/>'); });
      out.push('</mergeCells>');
    }
    out.push('<pageMargins left="0.4" right="0.4" top="0.55" bottom="0.55" header="0.3" footer="0.3"/>');
    out.push('<pageSetup paperSize="9" orientation="' + (maxCols > 6 ? 'landscape' : 'portrait') + '" fitToWidth="1" fitToHeight="0"/>');
    out.push('<headerFooter><oddFooter>&amp;C' + esc('Py | Sohaib') + '  —  &amp;P / &amp;N</oddFooter></headerFooter>');
    out.push('<ignoredErrors><ignoredError sqref="A1:' + colName(Math.max(maxCols, 1) - 1) + Math.max(1, rows.length) + '" numberStoredAsText="1"/></ignoredErrors>');
    out.push('</worksheet>');
    return out.join('');
  }

  /** يبني ملف Excel من أوراق: [{name, rows, widths, freeze, filter, merges}] */
  function build(sheets, meta) {
    meta = meta || {};
    var used = {};
    sheets.forEach(function (s, i) { s._name = sheetName(s.name, used); s.tabSelected = i === 0; });
    var files = [];
    var ct = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">' +
      '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>' +
      '<Default Extension="xml" ContentType="application/xml"/>' +
      '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>' +
      sheets.map(function (s, i) { return '<Override PartName="/xl/worksheets/sheet' + (i + 1) + '.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'; }).join('') +
      '<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>' +
      '<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>' +
      '<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>' +
      '</Types>';
    files.push({ name: '[Content_Types].xml', data: ct });
    files.push({ name: '_rels/.rels', data: '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' +
      '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>' +
      '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>' +
      '<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>' +
      '</Relationships>' });
    var iso = new Date().toISOString().replace(/\.\d+Z$/, 'Z');
    files.push({ name: 'docProps/core.xml', data: '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:dcmitype="http://purl.org/dc/dcmitype/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">' +
      '<dc:title>' + esc(meta.title || 'كشف') + '</dc:title><dc:creator>' + esc(meta.creator || 'MADIX Convert — Py | Sohaib') + '</dc:creator>' +
      '<dcterms:created xsi:type="dcterms:W3CDTF">' + iso + '</dcterms:created><dcterms:modified xsi:type="dcterms:W3CDTF">' + iso + '</dcterms:modified></cp:coreProperties>' });
    files.push({ name: 'docProps/app.xml', data: '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"><Application>MADIX Convert</Application></Properties>' });
    var defined = sheets.map(function (s, i) {
      if (!s.filter) return '';
      var parts = s.filter.split(':');
      var abs = parts.map(function (p) { return p.replace(/^([A-Z]+)(\d+)$/, '$$$1$$$2'); }).join(':');
      return '<definedName name="_xlnm._FilterDatabase" localSheetId="' + i + '" hidden="1">\'' + esc(s._name.replace(/'/g, "''")) + '\'!' + abs + '</definedName>';
    }).join('');
    files.push({ name: 'xl/workbook.xml', data: '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">' +
      '<workbookPr/><bookViews><workbookView xWindow="0" yWindow="0" windowWidth="16000" windowHeight="9000" activeTab="0"/></bookViews><sheets>' +
      sheets.map(function (s, i) { return '<sheet name="' + esc(s._name) + '" sheetId="' + (i + 1) + '" r:id="rId' + (i + 1) + '"/>'; }).join('') +
      '</sheets>' + (defined ? '<definedNames>' + defined + '</definedNames>' : '') + '</workbook>' });
    files.push({ name: 'xl/_rels/workbook.xml.rels', data: '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' +
      sheets.map(function (s, i) { return '<Relationship Id="rId' + (i + 1) + '" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet' + (i + 1) + '.xml"/>'; }).join('') +
      '<Relationship Id="rId' + (sheets.length + 1) + '" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>' +
      '</Relationships>' });
    files.push({ name: 'xl/styles.xml', data: STYLES });
    sheets.forEach(function (s, i) { files.push({ name: 'xl/worksheets/sheet' + (i + 1) + '.xml', data: sheetXml(s) }); });
    return zip(files);
  }

  function textWidth(v) {
    var s = String(v == null ? '' : v);
    var w = 0;
    for (var i = 0; i < s.length; i++) w += /[؀-ۿ]/.test(s[i]) ? 1.15 : 1;
    return w;
  }

  /** يجهز ملف Excel كامل للكشف */
  function fromStatement(st, an, opts) {
    opts = opts || {};
    var cols = st.columns;
    var rows = an.rows;
    var fl = an.flags;
    var idx = {};
    rows.forEach(function (r, i) { idx[r.id] = i + 1; });

    // الورقة الأولى: الكشف
    var header = [{ v: '#', s: ST.head }].concat(cols.map(function (c) { return { v: c.label, s: ST.head }; }), [{ v: 'ملاحظات', s: ST.head }]);
    var widths = [6].concat(cols.map(function (c) { return Math.max(textWidth(c.label) + 4, c.kind === 'national_id' ? 14 : c.kind === 'phone' ? 14 : c.kind === 'name' ? 24 : 10); }), [26]);
    var data = [header];
    rows.forEach(function (r, i) {
      var f = fl[r.id];
      var notes = [];
      if (f.dup) {
        var mates = (an.groups.filter(function (g) { return g.type === 'id' && g.n === f.dup; })[0] || { rows: [] }).rows.filter(function (x) { return x !== r.id; }).map(function (x) { return idx[x]; });
        notes.push('مكرر برقم الهوية' + (mates.length ? ' (مع ' + mates.join('، ') + ')' : ''));
      }
      if (Object.keys(f.bad).length) notes.push('رقم هوية غير صحيح');
      if (f.miss && Object.keys(f.miss).length) notes.push('بيانات ناقصة');
      if (f.nameDup) notes.push('تشابه اسم');
      if (f.ext) notes.push('موجود في: ' + f.ext.join('، '));
      if (Object.keys(f.unclear).length) notes.push('تحتاج مراجعة');
      var line = [{ v: i + 1, n: true, s: f.dup ? ST.dupCenter : ST.center }];
      cols.forEach(function (c, ci) {
        var v = r.cells[c.id] != null ? String(r.cells[c.id]) : '';
        var s = f.dup ? ST.dup : ST.cell;
        if (f.bad[c.id]) s = ST.bad;
        else if (f.miss && f.miss[c.id]) s = ST.miss;
        else if (f.unclear[c.id]) s = ST.unclear;
        var isNum = (c.kind === 'number' || c.kind === 'serial') && /^-?\d+(\.\d+)?$/.test(K.digits(v).trim());
        line.push({ v: isNum ? K.digits(v).trim() : (c.kind === 'national_id' || c.kind === 'phone' ? K.digits(v) : v), n: isNum, s: s });
        widths[ci + 1] = Math.max(widths[ci + 1], Math.min(48, textWidth(v) + 3));
      });
      line.push({ v: notes.join(' • '), s: f.dup ? ST.dup : ST.cell });
      widths[widths.length - 1] = Math.max(widths[widths.length - 1], Math.min(60, textWidth(notes.join(' • ')) + 2));
      data.push(line);
    });
    var lastCol = colName(cols.length + 1);
    var sheets = [{ name: 'الكشف', rows: data, widths: widths, freeze: 1, filter: 'A1:' + lastCol + Math.max(2, data.length), heights: { 0: 26 } }];

    // ورقة المكررات
    if (opts.dupes !== false) {
      var drows = [[{ v: 'المجموعة', s: ST.head }, { v: 'النوع', s: ST.head }, { v: 'رقم الصف', s: ST.head }].concat(cols.map(function (c) { return { v: c.label, s: ST.head }; }))];
      var typeName = { id: 'نفس رقم الهوية', name: 'تشابه الاسم', phone: 'نفس الجوال' };
      var byId = {};
      rows.forEach(function (r) { byId[r.id] = r; });
      an.groups.forEach(function (g, gi) {
        g.rows.forEach(function (rid) {
          var r = byId[rid];
          if (!r) return;
          var st2 = g.type === 'id' ? ST.dup : ST.cell;
          drows.push([{ v: gi + 1, n: true, s: ST.center }, { v: typeName[g.type], s: st2 }, { v: idx[rid], n: true, s: ST.center }].concat(cols.map(function (c) { return { v: r.cells[c.id] != null ? String(r.cells[c.id]) : '', s: st2 }; })));
        });
      });
      if (drows.length === 1) drows.push([{ v: 'لا يوجد مكررات ✓', s: ST.ok }]);
      sheets.push({ name: 'المكررات', rows: drows, widths: [10, 16, 10].concat(widths.slice(1, cols.length + 1)), freeze: 1 });
    }

    // ورقة تدقيق أرقام الهوية
    if (opts.ids !== false && an.idCols.length) {
      var nameCol = an.nameCol;
      var irows = [[{ v: 'رقم الصف', s: ST.head }, { v: 'الاسم', s: ST.head }, { v: 'العمود', s: ST.head }, { v: 'رقم الهوية', s: ST.head }, { v: 'المشكلة', s: ST.head }]];
      rows.forEach(function (r) {
        var f = fl[r.id];
        Object.keys(f.bad).forEach(function (cid) {
          var col = cols.filter(function (c) { return c.id === cid; })[0];
          irows.push([{ v: idx[r.id], n: true, s: ST.center }, { v: nameCol ? (r.cells[nameCol.id] || '') : '', s: ST.cell }, { v: col ? col.label : '', s: ST.cell }, { v: r.cells[cid] || '', s: ST.bad }, { v: f.bad[cid] === 'len' ? 'عدد الأرقام غير 9' : 'رقم التحقق غير صحيح', s: ST.cell }]);
        });
      });
      if (irows.length === 1) irows.push([{ v: 'كل أرقام الهوية صحيحة ✓', s: ST.ok }]);
      sheets.push({ name: 'تدقيق الهويات', rows: irows, widths: [10, 26, 14, 16, 26], freeze: 1 });
    }

    // ورقة الملخص
    var c = an.counts;
    var sum = [
      [{ v: st.title || 'كشف', s: ST.title }],
      [{ v: 'تم إنشاؤه بواسطة ' + (opts.appName || 'MADIX Convert') + ' — ' + K.fmtDate(new Date()), s: ST.muted }],
      [],
      [{ v: 'عدد الصفوف', s: ST.key }, { v: c.rows, n: true, s: ST.cell }],
      [{ v: 'عدد الصفحات', s: ST.key }, { v: (st.pages || []).length, n: true, s: ST.cell }],
      [{ v: 'صفوف مكررة برقم الهوية', s: ST.key }, { v: c.dupRows, n: true, s: c.dupRows ? ST.dup : ST.cell }],
      [{ v: 'مجموعات المكررات', s: ST.key }, { v: c.dupGroups, n: true, s: ST.cell }],
      [{ v: 'صفوف فيها رقم هوية غير صحيح', s: ST.key }, { v: c.badRows, n: true, s: c.badRows ? ST.bad : ST.cell }],
      [{ v: 'تشابه أسماء بأرقام هوية مختلفة', s: ST.key }, { v: c.nameGroups, n: true, s: ST.cell }],
      [{ v: 'صفوف بيانات ناقصة (اسم أو هوية)', s: ST.key }, { v: c.missRows || 0, n: true, s: c.missRows ? ST.miss : ST.cell }],
      [{ v: 'خانات تحتاج مراجعة', s: ST.key }, { v: c.unclear, n: true, s: c.unclear ? ST.unclear : ST.cell }],
      [],
      [{ v: 'دليل الألوان', s: ST.key }],
      [{ v: 'مكرر', s: ST.dup }],
      [{ v: 'رقم هوية غير صحيح', s: ST.bad }],
      [{ v: 'خانة ناقصة', s: ST.miss }],
      [{ v: 'خانة غير واضحة — راجعها مع الورقة', s: ST.unclear }],
      [],
      [{ v: 'Py | Sohaib', s: ST.muted }]
    ];
    sheets.push({ name: 'ملخص', rows: sum, widths: [34, 16], merges: ['A1:B1', 'A2:B2'] });

    return build(sheets, { title: st.title, creator: (opts.appName || 'MADIX Convert') + ' — Py | Sohaib' });
  }

  K.xlsx = { build: build, zip: zip, fromStatement: fromStatement, ST: ST, colName: colName };
})();
