<?php
/**
 * هيكل صفحات HTML وملف manifest للتثبيت كتطبيق
 * Py | Sohaib
 */
declare(strict_types=1);

if (!defined('MADIX')) {
    http_response_code(403);
    exit;
}

function render_shell(string $kind): void
{
    send_security_headers(true);
    header('Content-Type: text/html; charset=utf-8');
    header('Cache-Control: no-cache');
    $v = asset_version();
    $iv = rawurlencode(setting('icon_version', '0'));
    $name = app_name();
    $tagline = setting('tagline', 'حوّل الكشوفات المكتوبة بخط اليد لجدول إكسل خلال ثواني');
    $isAdmin = $kind === 'admin';
    $title = $isAdmin ? ('لوحة الإدارة — ' . $name) : ($name . ' — محوّل الكشوفات الذكي');
    $scripts = $isAdmin
        ? array('core.js', 'admin.js')
        : array('core.js', 'imaging.js', 'analysis.js', 'xlsx.js', 'pdf.js', 'app.js');
    ?>
<!doctype html>
<html lang="ar" dir="rtl" data-kind="<?= h($kind) ?>">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title><?= h($title) ?></title>
<meta name="description" content="<?= h($tagline) ?>">
<meta name="theme-color" content="#0b1220">
<meta name="color-scheme" content="light dark">
<meta name="robots" content="<?= $isAdmin ? 'noindex, nofollow' : 'index, follow' ?>">
<link rel="manifest" href="index.php?asset=manifest&amp;v=<?= $iv ?>">
<link rel="icon" type="image/png" sizes="192x192" href="index.php?asset=icon&amp;s=192&amp;v=<?= $iv ?>">
<link rel="apple-touch-icon" href="index.php?asset=icon&amp;s=180&amp;v=<?= $iv ?>">
<meta name="mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
<meta name="apple-mobile-web-app-title" content="<?= h($name) ?>">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=IBM+Plex+Sans+Arabic:wght@300;400;500;600;700&amp;display=swap">
<link rel="stylesheet" href="assets/css/app.css?v=<?= h($v) ?>">
<script src="assets/js/boot.js?v=<?= h($v) ?>"></script>
</head>
<body class="<?= $isAdmin ? 'is-admin' : 'is-app' ?>" data-version="<?= h($v) ?>" data-icon-v="<?= $iv ?>">
<div id="splash" class="splash" role="status" aria-label="جاري التحميل">
  <div class="splash-bg"></div>
  <div class="splash-inner">
    <div class="splash-logo">
      <img src="index.php?asset=icon&amp;s=512&amp;v=<?= $iv ?>" alt="" width="112" height="112">
      <span class="splash-beam"></span>
    </div>
    <h1 class="splash-name"><?= wordmark_html($name) ?></h1>
    <p class="splash-tag"><?= h($isAdmin ? 'لوحة الإدارة' : $tagline) ?></p>
    <div class="splash-bar"><span></span></div>
  </div>
  <div class="splash-foot">Py | Sohaib</div>
</div>
<div id="app" class="app" hidden></div>
<div id="layer" class="layer"></div>
<div id="toasts" class="toasts" aria-live="polite"></div>
<div id="print-area" class="print-area" aria-hidden="true"></div>
<noscript><div class="noscript">لازم تفعّل JavaScript حتى يشتغل التطبيق.</div></noscript>
<?php foreach ($scripts as $s): ?>
<script src="assets/js/<?= h($s) ?>?v=<?= h($v) ?>" defer></script>
<?php endforeach; ?>
</body>
</html>
<?php
}

/** صفحة تحميل التطبيق */
function render_landing(): void
{
    send_security_headers(true);
    header('Content-Type: text/html; charset=utf-8');
    header('Cache-Control: no-cache');
    $v = asset_version();
    $iv = rawurlencode(setting('icon_version', '0'));
    $name = app_name();
    $tagline = setting('tagline', 'حوّل الكشوفات المكتوبة بخط اليد لجدول إكسل خلال ثواني');
    $apk = apk_file();
    $size = $apk !== '' ? number_format(filesize($apk) / 1048576, 1) . ' MB' : '';
    $ver = setting('apk_version', '');
    $web = setting('web_app', '0') === '1';
    $footer = setting('footer_text', 'MADIX Convert — محوّل الكشوفات الذكي');
    ?>
<!doctype html>
<html lang="ar" dir="rtl">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title><?= h($name) ?></title>
<meta name="description" content="<?= h($tagline) ?>">
<meta name="theme-color" content="#0b1220">
<link rel="icon" type="image/png" sizes="192x192" href="index.php?asset=icon&amp;s=192&amp;v=<?= $iv ?>">
<link rel="apple-touch-icon" href="index.php?asset=icon&amp;s=180&amp;v=<?= $iv ?>">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=IBM+Plex+Sans+Arabic:wght@300;400;500;600;700&amp;display=swap">
<link rel="stylesheet" href="assets/css/app.css?v=<?= h($v) ?>">
<script src="assets/js/boot.js?v=<?= h($v) ?>"></script>
</head>
<body class="landing">
<div class="auth">
  <div class="auth-hero land-hero">
    <div class="auth-hero-in">
      <img src="index.php?asset=icon&amp;s=512&amp;v=<?= $iv ?>" alt="" width="96" height="96">
      <h1><?= wordmark_html($name) ?></h1>
      <p><?= h($tagline) ?></p>
      <div class="auth-feats">
        <div class="auth-feat"><svg class="ico" viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3l1.8 4.7L18.5 9.5l-4.7 1.8L12 16l-1.8-4.7L5.5 9.5l4.7-1.8z"/></svg>قراءة خط اليد</div>
        <div class="auth-feat"><svg class="ico" viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="5" width="18" height="14" rx="2.5"/><circle cx="9" cy="11" r="2"/><path d="M6 16a3 3 0 0 1 6 0M14 10h4M14 14h4"/></svg>المكررات برقم الهوية</div>
        <div class="auth-feat"><svg class="ico" viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="3" width="18" height="18" rx="2.5"/><path d="M3 9h18M3 15h18M9 3v18"/></svg>Excel و PDF</div>
      </div>
    </div>
  </div>
  <div class="auth-card stack lg land-card">
<?php if ($apk !== ''): ?>
    <a class="btn btn-primary btn-block btn-lg" href="index.php?download=apk" download>
      <svg class="ico" viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3v12M7 10l5 5 5-5M5 21h14"/></svg>
      تحميل التطبيق لأندرويد
    </a>
    <div class="land-meta"><?= h(trim(($ver !== '' ? 'الإصدار ' . $ver . ' • ' : '') . $size)) ?></div>
<?php else: ?>
    <button class="btn btn-block btn-lg" type="button" disabled>التطبيق قريباً</button>
<?php endif; ?>
<?php if ($web): ?>
    <a class="btn btn-block" href="index.php?web=1">فتح نسخة الويب</a>
<?php endif; ?>
    <a class="btn btn-ghost btn-sm land-admin" href="admin.php">لوحة الإدارة</a>
  </div>
  <footer class="footer"><div class="f-main"><?= h($footer) ?> © <?= gmdate('Y') ?></div><div class="f-sign">Py | Sohaib</div></footer>
</div>
</body>
</html>
<?php
}

function render_manifest(): void
{
    header('Content-Type: application/manifest+json; charset=utf-8');
    header('Cache-Control: no-cache');
    $iv = rawurlencode(setting('icon_version', '0'));
    $name = app_name();
    $manifest = array(
        'id' => './',
        'name' => $name,
        'short_name' => (function_exists('mb_strlen') ? mb_strlen($name) : strlen($name)) > 12 ? (string)preg_split('/\s+/u', $name)[0] : $name,
        'description' => setting('tagline'),
        'lang' => 'ar',
        'dir' => 'rtl',
        'start_url' => './?source=pwa',
        'scope' => './',
        'display' => 'standalone',
        'display_override' => array('standalone', 'minimal-ui'),
        'orientation' => 'portrait',
        'background_color' => '#0b1220',
        'theme_color' => '#0b1220',
        'categories' => array('productivity', 'business', 'utilities'),
        'icons' => array(
            array('src' => 'index.php?asset=icon&s=192&v=' . $iv, 'sizes' => '192x192', 'type' => 'image/png', 'purpose' => 'any'),
            array('src' => 'index.php?asset=icon&s=512&v=' . $iv, 'sizes' => '512x512', 'type' => 'image/png', 'purpose' => 'any'),
            array('src' => 'index.php?asset=icon&s=0&v=' . $iv, 'sizes' => '512x512', 'type' => 'image/png', 'purpose' => 'maskable'),
        ),
        'shortcuts' => array(
            array('name' => 'كشف جديد', 'url' => './?go=new', 'icons' => array(array('src' => 'index.php?asset=icon&s=192&v=' . $iv, 'sizes' => '192x192'))),
            array('name' => 'المحفظة', 'url' => './?go=wallet', 'icons' => array(array('src' => 'index.php?asset=icon&s=192&v=' . $iv, 'sizes' => '192x192'))),
        ),
    );
    echo json_encode($manifest, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT);
}

/** يخدم الملفات الديناميكية: manifest والأيقونات */
function serve_asset(string $asset): void
{
    if ($asset === 'manifest') {
        render_manifest();
        return;
    }
    if ($asset === 'icon') {
        require_once __DIR__ . '/icons.php';
        serve_icon(isset($_GET['s']) ? (int)$_GET['s'] : 512);
        return;
    }
    http_response_code(404);
}
