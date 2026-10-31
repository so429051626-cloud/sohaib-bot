/* MADIX Convert — تحديد الثيم قبل الرسم لتجنب الوميض | Py | Sohaib */
(function () {
  try {
    var t = localStorage.getItem('madix.theme');
    document.documentElement.setAttribute('data-theme', t === 'light' || t === 'dark' ? t : 'auto');
  } catch (e) {
    document.documentElement.setAttribute('data-theme', 'auto');
  }
})();
