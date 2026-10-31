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
