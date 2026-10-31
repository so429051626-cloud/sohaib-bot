<?php
/**
 * MADIX Convert — لوحة الإدارة
 * مربوطة مع index.php وبتستخدم نفس قاعدة البيانات:
 * نماذج الذكاء الاصطناعي، مفاتيح API، الأكواد، المستخدمين، الإعدادات، الأيقونة، والسجلات.
 * Py | Sohaib
 */
declare(strict_types=1);
define('MADIX', true);

require __DIR__ . '/app/bootstrap.php';
require __DIR__ . '/app/shell.php';

boot_or_die();

if (isset($_GET['api'])) {
    require __DIR__ . '/app/api.php';
    api_dispatch((string)$_GET['api'], 'admin');
    exit;
}

render_shell('admin');
