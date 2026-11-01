<?php
/**
 * MADIX Convert — محوّل الكشوفات الذكي
 * صوّر الكشف المكتوب بخط اليد ← جدول إكسل + كشف المكررات برقم الهوية
 * الملف الرئيسي للتطبيق (لوحة الإدارة في admin.php)
 * Py | Sohaib
 */
declare(strict_types=1);
define('MADIX', true);

require __DIR__ . '/app/bootstrap.php';
require __DIR__ . '/app/shell.php';

if (isset($_GET['asset'])) {
    try {
        db();
    } catch (Throwable $e) {
        log_error($e);
    }
    serve_asset((string)$_GET['asset']);
    exit;
}

if (isset($_GET['api'])) {
    send_cors();
}

boot_or_die();

if (isset($_GET['api'])) {
    require __DIR__ . '/app/api.php';
    api_dispatch((string)$_GET['api'], 'app');
    exit;
}

if (isset($_GET['download'])) {
    serve_apk();
    exit;
}

// الموقع للإدارة وصفحة تحميل التطبيق؛ نسخة الويب بس إذا المدير فعّلها
if (setting('web_app', '0') === '1' && (isset($_GET['web']) || isset($_GET['source']) || isset($_GET['go']))) {
    render_shell('app');
} else {
    render_landing();
}
