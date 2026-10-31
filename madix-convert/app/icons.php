<?php
/**
 * أيقونة التطبيق: الافتراضية أو المرفوعة من لوحة الإدارة
 * Py | Sohaib
 */
declare(strict_types=1);

if (!defined('MADIX')) {
    http_response_code(403);
    exit;
}

const ICON_SIZES = array(512 => 'icon-512.png', 192 => 'icon-192.png', 180 => 'apple-touch-icon.png', 0 => 'icon-maskable-512.png');

function icon_file(int $size): string
{
    if (!array_key_exists($size, ICON_SIZES)) {
        $size = 512;
    }
    $name = ICON_SIZES[$size];
    $custom = sub_data_dir('icons') . '/' . $name;
    if (is_file($custom)) {
        return $custom;
    }
    return app_dir() . '/assets/img/' . $name;
}

function serve_icon(int $size): void
{
    $file = icon_file($size);
    if (!is_file($file)) {
        http_response_code(404);
        return;
    }
    header('Content-Type: image/png');
    header('Cache-Control: public, max-age=604800');
    header('Content-Length: ' . (string)filesize($file));
    readfile($file);
}

function icon_reset(): void
{
    foreach (ICON_SIZES as $name) {
        $f = sub_data_dir('icons') . '/' . $name;
        if (is_file($f)) {
            @unlink($f);
        }
    }
}

function icon_save_upload(string $tmp): void
{
    if (!function_exists('imagecreatefromstring') || !function_exists('imagepng')) {
        throw new ApiError('مكتبة GD غير مفعّلة على السيرفر — ما بنقدر نعالج الصورة');
    }
    $size = (int)@filesize($tmp);
    if ($size <= 0 || $size > 8 * 1024 * 1024) {
        throw new ApiError('حجم الصورة كبير (الحد 8 ميجا)');
    }
    $info = @getimagesize($tmp);
    if (!$info || !in_array($info[2], array(IMAGETYPE_JPEG, IMAGETYPE_PNG, IMAGETYPE_WEBP, IMAGETYPE_GIF), true)) {
        throw new ApiError('الملف لازم يكون صورة PNG أو JPG أو WEBP');
    }
    $src = @imagecreatefromstring((string)file_get_contents($tmp));
    @unlink($tmp);
    if (!$src) {
        throw new ApiError('ما قدرنا نقرأ الصورة');
    }
    $w = imagesx($src);
    $h = imagesy($src);
    $side = min($w, $h);
    $sx = (int)(($w - $side) / 2);
    $sy = (int)(($h - $side) / 2);
    $dir = sub_data_dir('icons');
    foreach (array(512, 192, 180) as $s) {
        $dst = imagecreatetruecolor($s, $s);
        imagealphablending($dst, false);
        imagesavealpha($dst, true);
        imagefill($dst, 0, 0, imagecolorallocatealpha($dst, 0, 0, 0, 127));
        imagecopyresampled($dst, $src, 0, 0, $sx, $sy, $s, $s, $side, $side);
        imagepng($dst, $dir . '/' . ICON_SIZES[$s], 6);
        imagedestroy($dst);
    }
    // نسخة maskable: الصورة بنسبة 80% على خلفية بلون التطبيق حتى ما تنقص أطرافها
    $m = imagecreatetruecolor(512, 512);
    imagefill($m, 0, 0, imagecolorallocate($m, 11, 18, 32));
    $inner = 410;
    $off = (int)((512 - $inner) / 2);
    imagealphablending($m, true);
    imagecopyresampled($m, $src, $off, $off, $sx, $sy, $inner, $inner, $side, $side);
    imagepng($m, $dir . '/' . ICON_SIZES[0], 6);
    imagedestroy($m);
    imagedestroy($src);
}
