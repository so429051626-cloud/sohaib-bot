<?php
/**
 * MADIX Convert — محوّل الكشوفات الذكي
 * الملف الأساسي: الإعدادات، قاعدة البيانات، الجلسات، والأدوات المشتركة.
 * Py | Sohaib
 */
declare(strict_types=1);

if (!defined('MADIX')) {
    http_response_code(403);
    exit;
}

const APP_VERSION = '1.0.0';
const ANTHROPIC_URL = 'https://api.anthropic.com/v1/messages';
const ANTHROPIC_VERSION = '2026-10-21';
const FALLBACK_BETA = 'server-side-fallback-2026-07-01';

date_default_timezone_set('UTC');
ini_set('display_errors', '0');
error_reporting(E_ALL);

/** خطأ مخصص يرجع للمستخدم برسالة عربية واضحة */
final class ApiError extends RuntimeException
{
    /** @var int */
    public $status;
    /** @var array */
    public $extra;

    public function __construct(string $message, int $status = 400, array $extra = array())
    {
        parent::__construct($message);
        $this->status = $status;
        $this->extra = $extra;
    }
}

// ============ المسارات والإعدادات ============

function app_dir(): string
{
    return dirname(__DIR__);
}

function data_dir(): string
{
    static $dir = null;
    if ($dir !== null) {
        return $dir;
    }
    $env = getenv('MADIX_DATA_DIR');
    $dir = ($env !== false && $env !== '') ? rtrim($env, '/\\') : app_dir() . '/data';
    if (!is_dir($dir) && !@mkdir($dir, 0775, true) && !is_dir($dir)) {
        throw new RuntimeException('تعذّر إنشاء مجلد البيانات: ' . $dir);
    }
    protect_dir($dir);
    return $dir;
}

/** يمنع الوصول المباشر لمجلد من المتصفح (Apache) ويضيف صفحة فارغة (لباقي السيرفرات) */
function protect_dir(string $dir): void
{
    $ht = $dir . '/.htaccess';
    if (!is_file($ht)) {
        @file_put_contents($ht, "<IfModule mod_authz_core.c>\n  Require all denied\n</IfModule>\n<IfModule !mod_authz_core.c>\n  Order allow,deny\n  Deny from all\n</IfModule>\n");
    }
    $idx = $dir . '/index.html';
    if (!is_file($idx)) {
        @file_put_contents($idx, '');
    }
}

function sub_data_dir(string $name): string
{
    $d = data_dir() . '/' . $name;
    if (!is_dir($d)) {
        @mkdir($d, 0775, true);
    }
    protect_dir($d);
    return $d;
}

/** ملف إعدادات سري يُنشأ تلقائياً أول مرة (اسم قاعدة البيانات العشوائي + مفتاح التشفير) */
function app_config(): array
{
    static $cfg = null;
    if ($cfg !== null) {
        return $cfg;
    }
    $file = data_dir() . '/config.php';
    if (is_file($file)) {
        $cfg = include $file;
        if (is_array($cfg) && !empty($cfg['db']) && !empty($cfg['secret'])) {
            return $cfg;
        }
    }
    $cfg = array(
        'db' => 'madix_' . bin2hex(random_bytes(10)) . '.sqlite',
        'secret' => bin2hex(random_bytes(32)),
        'created' => gmdate('Y-m-d H:i:s'),
    );
    $php = "<?php\n// ملف تم إنشاؤه تلقائياً — لا تشاركه مع أحد\nreturn " . var_export($cfg, true) . ";\n";
    if (@file_put_contents($file, $php, LOCK_EX) === false) {
        throw new RuntimeException('تعذّر كتابة ملف الإعدادات. تأكد من صلاحيات الكتابة على مجلد data');
    }
    @chmod($file, 0640);
    return $cfg;
}

// ============ قاعدة البيانات ============

function db(): PDO
{
    static $pdo = null;
    if ($pdo !== null) {
        return $pdo;
    }
    if (!extension_loaded('pdo_sqlite')) {
        throw new RuntimeException('امتداد pdo_sqlite غير مفعّل على السيرفر');
    }
    $path = data_dir() . '/' . app_config()['db'];
    $pdo = new PDO('sqlite:' . $path, null, null, array(
        PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
        PDO::ATTR_EMULATE_PREPARES => false,
        PDO::ATTR_TIMEOUT => 10,
    ));
    try {
        $pdo->exec('PRAGMA journal_mode = WAL');
    } catch (Throwable $e) {
        // بعض الاستضافات لا تدعم WAL — نكمل بالوضع العادي
    }
    $pdo->exec('PRAGMA busy_timeout = 10000');
    $pdo->exec('PRAGMA foreign_keys = ON');
    $pdo->exec('PRAGMA synchronous = NORMAL');
    require_once __DIR__ . '/schema.php';
    migrate($pdo);
    return $pdo;
}

function q(string $sql, array $params = array()): PDOStatement
{
    $st = db()->prepare($sql);
    $st->execute($params);
    return $st;
}

function q_one(string $sql, array $params = array()): ?array
{
    $row = q($sql, $params)->fetch();
    return $row === false ? null : $row;
}

function q_val(string $sql, array $params = array())
{
    $v = q($sql, $params)->fetchColumn();
    return $v === false ? null : $v;
}

function now_utc(): string
{
    return gmdate('Y-m-d H:i:s');
}

// ============ الإعدادات العامة ============

function setting(string $key, string $default = ''): string
{
    static $cache = null;
    if ($cache === null || $key === '__reset__') {
        $cache = array();
        foreach (q('SELECT k, v FROM settings')->fetchAll() as $r) {
            $cache[$r['k']] = (string)$r['v'];
        }
        if ($key === '__reset__') {
            return '';
        }
    }
    return array_key_exists($key, $cache) ? $cache[$key] : $default;
}

function set_setting(string $key, string $value): void
{
    q('INSERT OR REPLACE INTO settings (k, v, updated_at) VALUES (?, ?, ?)', array($key, $value, now_utc()));
    setting('__reset__');
}

// ============ التشفير (لمفاتيح API) ============

function secret_key(): string
{
    return hash('sha256', app_config()['secret'] . '|madix-keys', true);
}

function encrypt_secret(string $plain): string
{
    if (function_exists('sodium_crypto_secretbox')) {
        $nonce = random_bytes(SODIUM_CRYPTO_SECRETBOX_NONCEBYTES);
        return 's1:' . base64_encode($nonce . sodium_crypto_secretbox($plain, $nonce, secret_key()));
    }
    if (function_exists('openssl_encrypt')) {
        $iv = random_bytes(16);
        $ct = openssl_encrypt($plain, 'aes-256-cbc', secret_key(), OPENSSL_RAW_DATA, $iv);
        $mac = hash_hmac('sha256', $iv . $ct, secret_key(), true);
        return 'o1:' . base64_encode($iv . $mac . $ct);
    }
    return 'p0:' . base64_encode($plain);
}

function decrypt_secret(string $stored): string
{
    $kind = substr($stored, 0, 3);
    $raw = base64_decode(substr($stored, 3), true);
    if ($raw === false) {
        return '';
    }
    if ($kind === 's1:' && function_exists('sodium_crypto_secretbox_open')) {
        $nonce = substr($raw, 0, SODIUM_CRYPTO_SECRETBOX_NONCEBYTES);
        $out = sodium_crypto_secretbox_open(substr($raw, SODIUM_CRYPTO_SECRETBOX_NONCEBYTES), $nonce, secret_key());
        return $out === false ? '' : $out;
    }
    if ($kind === 'o1:' && function_exists('openssl_decrypt')) {
        $iv = substr($raw, 0, 16);
        $mac = substr($raw, 16, 32);
        $ct = substr($raw, 48);
        if (!hash_equals(hash_hmac('sha256', $iv . $ct, secret_key(), true), $mac)) {
            return '';
        }
        $out = openssl_decrypt($ct, 'aes-256-cbc', secret_key(), OPENSSL_RAW_DATA, $iv);
        return $out === false ? '' : $out;
    }
    if ($kind === 'p0:') {
        return $raw;
    }
    return '';
}

// ============ الطلب والاستجابة ============

function is_https(): bool
{
    if (!empty($_SERVER['HTTPS']) && strtolower((string)$_SERVER['HTTPS']) !== 'off') {
        return true;
    }
    if (isset($_SERVER['HTTP_X_FORWARDED_PROTO']) && strtolower((string)$_SERVER['HTTP_X_FORWARDED_PROTO']) === 'https') {
        return true;
    }
    return isset($_SERVER['SERVER_PORT']) && (string)$_SERVER['SERVER_PORT'] === '443';
}

function base_path(): string
{
    $script = isset($_SERVER['SCRIPT_NAME']) ? (string)$_SERVER['SCRIPT_NAME'] : '/index.php';
    $dir = str_replace('\\', '/', dirname($script));
    if ($dir === '/' || $dir === '.' || $dir === '') {
        return '/';
    }
    return rtrim($dir, '/') . '/';
}

function client_ip(): string
{
    return isset($_SERVER['REMOTE_ADDR']) ? substr((string)$_SERVER['REMOTE_ADDR'], 0, 64) : '0.0.0.0';
}

function send_security_headers(bool $html = false): void
{
    header('X-Content-Type-Options: nosniff');
    header('Referrer-Policy: same-origin');
    header('X-Frame-Options: SAMEORIGIN');
    header('Permissions-Policy: camera=(self), microphone=(), geolocation=()');
    if ($html) {
        header("Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src 'self' data: https://fonts.gstatic.com; img-src 'self' data: blob:; connect-src 'self'; worker-src 'self'; manifest-src 'self'; frame-ancestors 'self'; base-uri 'self'; form-action 'self'");
    }
}

function json_out(array $data, int $status = 200): void
{
    if (!headers_sent()) {
        http_response_code($status);
        header('Content-Type: application/json; charset=utf-8');
        header('Cache-Control: no-store');
    }
    echo json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_INVALID_UTF8_SUBSTITUTE);
}

/** جسم الطلب كـ JSON أو حقول نموذج */
function input(): array
{
    static $data = null;
    if ($data !== null) {
        return $data;
    }
    $ct = isset($_SERVER['CONTENT_TYPE']) ? strtolower((string)$_SERVER['CONTENT_TYPE']) : '';
    if (strpos($ct, 'application/json') !== false) {
        $raw = file_get_contents('php://input');
        $decoded = json_decode($raw === false ? '' : $raw, true);
        $data = is_array($decoded) ? $decoded : array();
    } else {
        $data = $_POST;
    }
    return $data;
}

function in_str(string $key, int $max = 500): string
{
    $v = input()[$key] ?? '';
    if (!is_scalar($v)) {
        return '';
    }
    $v = trim((string)$v);
    return str_cut($v, $max);
}

/** قص نص بأمان مع دعم العربي حتى لو امتداد mbstring غير موجود */
function str_cut(string $s, int $max): string
{
    if (function_exists('mb_substr')) {
        return mb_substr($s, 0, $max, 'UTF-8');
    }
    if (preg_match_all('/./us', $s, $m)) {
        return implode('', array_slice($m[0], 0, $max));
    }
    return substr($s, 0, $max);
}

function str_len(string $s): int
{
    if (function_exists('mb_strlen')) {
        return mb_strlen($s, 'UTF-8');
    }
    return (int)preg_match_all('/./us', $s);
}

function in_int(string $key, int $default = 0): int
{
    $v = input()[$key] ?? null;
    if (is_int($v)) {
        return $v;
    }
    if (is_string($v) && preg_match('/^-?\d+$/', trim(to_latin_digits($v)))) {
        return (int)trim(to_latin_digits($v));
    }
    if (is_float($v)) {
        return (int)$v;
    }
    return $default;
}

function in_float(string $key, float $default = 0.0): float
{
    $v = input()[$key] ?? null;
    if (is_int($v) || is_float($v)) {
        return (float)$v;
    }
    if (is_string($v)) {
        $s = str_replace(array(',', '٫'), '.', trim(to_latin_digits($v)));
        if (is_numeric($s)) {
            return (float)$s;
        }
    }
    return $default;
}

function in_bool(string $key): bool
{
    $v = input()[$key] ?? false;
    return $v === true || $v === 1 || $v === '1' || $v === 'true' || $v === 'on';
}

function to_latin_digits(string $s): string
{
    return strtr($s, array(
        '٠' => '0', '١' => '1', '٢' => '2', '٣' => '3', '٤' => '4', '٥' => '5', '٦' => '6', '٧' => '7', '٨' => '8', '٩' => '9',
        '۰' => '0', '۱' => '1', '۲' => '2', '۳' => '3', '۴' => '4', '۵' => '5', '۶' => '6', '۷' => '7', '۸' => '8', '۹' => '9',
    ));
}

function normalize_phone(string $phone): string
{
    $p = preg_replace('/\D+/', '', to_latin_digits($phone));
    $p = $p === null ? '' : $p;
    if (strpos($p, '00970') === 0 || strpos($p, '00972') === 0) {
        $p = '0' . substr($p, 5);
    } elseif ((strpos($p, '970') === 0 || strpos($p, '972') === 0) && strlen($p) === 12) {
        $p = '0' . substr($p, 3);
    }
    return $p;
}

// ============ الجلسات والمستخدم ============

function start_session(): void
{
    if (session_status() === PHP_SESSION_ACTIVE) {
        return;
    }
    $life = 60 * 60 * 24 * 30;
    ini_set('session.use_strict_mode', '1');
    ini_set('session.use_only_cookies', '1');
    ini_set('session.gc_maxlifetime', (string)$life);
    ini_set('session.gc_probability', '1');
    ini_set('session.gc_divisor', '200');
    session_save_path(sub_data_dir('sessions'));
    session_name('MADIXSID');
    session_set_cookie_params(array(
        'lifetime' => $life,
        'path' => base_path(),
        'secure' => is_https(),
        'httponly' => true,
        'samesite' => 'Lax',
    ));
    session_start();
    if (empty($_SESSION['csrf'])) {
        $_SESSION['csrf'] = bin2hex(random_bytes(24));
    }
}

function csrf_token(): string
{
    return (string)($_SESSION['csrf'] ?? '');
}

function check_csrf(): void
{
    $sent = isset($_SERVER['HTTP_X_CSRF_TOKEN']) ? (string)$_SERVER['HTTP_X_CSRF_TOKEN'] : '';
    if ($sent === '' || !hash_equals(csrf_token(), $sent)) {
        throw new ApiError('انتهت صلاحية الجلسة، حدّث الصفحة وحاول مرة ثانية', 419);
    }
}

/** توكن تطبيق الجوال (بدل الكوكيز) */
function request_token(): string
{
    $t = isset($_SERVER['HTTP_X_AUTH_TOKEN']) ? (string)$_SERVER['HTTP_X_AUTH_TOKEN'] : '';
    if ($t === '' && isset($_SERVER['HTTP_AUTHORIZATION']) && stripos((string)$_SERVER['HTTP_AUTHORIZATION'], 'Bearer ') === 0) {
        $t = substr((string)$_SERVER['HTTP_AUTHORIZATION'], 7);
    }
    $t = trim($t);
    return preg_match('/^[a-f0-9]{64}$/', $t) ? $t : '';
}

function is_app_client(): bool
{
    return request_token() !== '' || (isset($_SERVER['HTTP_X_CLIENT']) && $_SERVER['HTTP_X_CLIENT'] === 'app');
}

function issue_token(array $u): string
{
    $raw = bin2hex(random_bytes(32));
    $device = isset($_SERVER['HTTP_USER_AGENT']) ? str_cut((string)$_SERVER['HTTP_USER_AGENT'], 160) : '';
    q('INSERT INTO tokens (user_id, token_hash, sv, device, created_at, last_used_at) VALUES (?, ?, ?, ?, ?, ?)', array((int)$u['id'], hash('sha256', $raw), (int)($u['session_v'] ?? 0), $device, now_utc(), now_utc()));
    q('UPDATE users SET last_login_at = ? WHERE id = ?', array(now_utc(), (int)$u['id']));
    if (random_int(1, 20) === 1) {
        q('DELETE FROM tokens WHERE last_used_at < ?', array(gmdate('Y-m-d H:i:s', time() - 180 * 86400)));
    }
    return $raw;
}

function revoke_token(): void
{
    $t = request_token();
    if ($t !== '') {
        q('DELETE FROM tokens WHERE token_hash = ?', array(hash('sha256', $t)));
    }
}

/** السماح لتطبيق الجوال بالاتصال (CORS) */
function send_cors(): void
{
    $origin = isset($_SERVER['HTTP_ORIGIN']) ? (string)$_SERVER['HTTP_ORIGIN'] : '';
    $allowed = array('https://localhost', 'http://localhost', 'capacitor://localhost', 'ionic://localhost');
    $extra = getenv('MADIX_CORS_ORIGINS');
    if ($extra !== false && $extra !== '') {
        $allowed = array_merge($allowed, array_map('trim', explode(',', $extra)));
    }
    if ($origin !== '' && in_array($origin, $allowed, true)) {
        header('Access-Control-Allow-Origin: ' . $origin);
        header('Vary: Origin');
        header('Access-Control-Allow-Headers: Content-Type, Accept, X-Auth-Token, X-Client, X-CSRF-Token, X-Requested-With');
        header('Access-Control-Allow-Methods: GET, POST, OPTIONS');
        header('Access-Control-Max-Age: 86400');
    }
    if (isset($_SERVER['REQUEST_METHOD']) && strtoupper((string)$_SERVER['REQUEST_METHOD']) === 'OPTIONS') {
        http_response_code(204);
        exit;
    }
}

function current_user(): ?array
{
    static $loaded = false;
    static $user = null;
    if ($loaded) {
        return $user;
    }
    $loaded = true;
    $tok = request_token();
    if ($tok !== '' || is_app_client()) {
        if ($tok === '') {
            return null;
        }
        $row = q_one('SELECT t.id AS token_id, t.sv AS token_sv, t.last_used_at AS token_used, u.* FROM tokens t JOIN users u ON u.id = t.user_id WHERE t.token_hash = ?', array(hash('sha256', $tok)));
        if (!$row || (int)$row['is_blocked'] === 1 || (int)$row['token_sv'] !== (int)$row['session_v']) {
            return null;
        }
        if (!$row['token_used'] || strtotime((string)$row['token_used'] . ' UTC') < time() - 600) {
            q('UPDATE tokens SET last_used_at = ? WHERE id = ?', array(now_utc(), (int)$row['token_id']));
        }
        $user = $row;
        return $user;
    }
    $uid = isset($_SESSION['uid']) ? (int)$_SESSION['uid'] : 0;
    if ($uid <= 0) {
        return null;
    }
    $u = q_one('SELECT * FROM users WHERE id = ?', array($uid));
    if (!$u || (int)$u['is_blocked'] === 1 || (int)($u['session_v'] ?? 0) !== (int)($_SESSION['sv'] ?? 0)) {
        unset($_SESSION['uid'], $_SESSION['sv']);
        return null;
    }
    $user = $u;
    return $user;
}

function require_user(): array
{
    $u = current_user();
    if (!$u) {
        throw new ApiError('سجّل دخولك أولاً', 401);
    }
    return $u;
}

function require_admin(): array
{
    $u = require_user();
    if ($u['role'] !== 'admin') {
        throw new ApiError('هذه الصفحة للمدير فقط', 403);
    }
    return $u;
}

function login_user(array $u): void
{
    session_regenerate_id(true);
    $_SESSION['uid'] = (int)$u['id'];
    $_SESSION['sv'] = (int)($u['session_v'] ?? 0);
    $_SESSION['csrf'] = bin2hex(random_bytes(24));
    q('UPDATE users SET last_login_at = ? WHERE id = ?', array(now_utc(), (int)$u['id']));
}

function public_user(array $u): array
{
    return array(
        'id' => (int)$u['id'],
        'name' => (string)$u['name'],
        'username' => (string)($u['username'] ?? ''),
        'phone' => (string)$u['phone'],
        'role' => (string)$u['role'],
        'pages' => (int)$u['pages'],
        'pages_used' => (int)$u['pages_used'],
        'created_at' => (string)$u['created_at'],
    );
}

// ============ السجل والحماية من التخمين ============

function audit(string $action, string $details = '', ?int $userId = null): void
{
    try {
        if ($userId === null) {
            $u = current_user();
            $userId = $u ? (int)$u['id'] : null;
        }
        q('INSERT INTO audit_log (user_id, action, details, ip, created_at) VALUES (?, ?, ?, ?, ?)', array($userId, $action, str_cut($details, 1000), client_ip(), now_utc()));
    } catch (Throwable $e) {
        log_error($e);
    }
}

/** يرمي خطأ إذا تجاوز المفتاح الحد المسموح خلال الفترة */
function rate_check(string $key, int $max, int $windowSec, string $message): void
{
    $since = time() - $windowSec;
    $n = (int)q_val('SELECT COUNT(*) FROM rate_hits WHERE k = ? AND t > ?', array($key, $since));
    if ($n >= $max) {
        throw new ApiError($message, 429);
    }
}

function rate_hit(string $key): void
{
    q('INSERT INTO rate_hits (k, t) VALUES (?, ?)', array($key, time()));
    if (random_int(1, 50) === 1) {
        q('DELETE FROM rate_hits WHERE t < ?', array(time() - 172800));
    }
}

function rate_clear(string $key): void
{
    q('DELETE FROM rate_hits WHERE k = ?', array($key));
}

function log_error(Throwable $e): void
{
    try {
        $line = '[' . gmdate('Y-m-d H:i:s') . '] ' . get_class($e) . ': ' . $e->getMessage() . ' @ ' . basename($e->getFile()) . ':' . $e->getLine() . "\n";
        $file = data_dir() . '/errors.log';
        if (is_file($file) && filesize($file) > 2 * 1024 * 1024) {
            @rename($file, $file . '.old');
        }
        @file_put_contents($file, $line, FILE_APPEND | LOCK_EX);
    } catch (Throwable $ignored) {
        // لا شيء
    }
}

function h(string $s): string
{
    return htmlspecialchars($s, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8');
}

/** نسخة الملفات لتحديث الكاش تلقائياً عند تعديل أي ملف */
function asset_version(): string
{
    static $v = null;
    if ($v !== null) {
        return $v;
    }
    $mt = 0;
    foreach (array('assets/css/app.css', 'assets/js/core.js', 'assets/js/imaging.js', 'assets/js/analysis.js', 'assets/js/xlsx.js', 'assets/js/pdf.js', 'assets/js/boot.js', 'assets/js/native.js', 'assets/js/app.js', 'assets/js/admin.js', 'sw.js') as $f) {
        $p = app_dir() . '/' . $f;
        if (is_file($p)) {
            $mt = max($mt, (int)filemtime($p));
        }
    }
    $v = APP_VERSION . '.' . base_convert((string)$mt, 10, 36);
    return $v;
}

function app_name(): string
{
    $n = setting('app_name', 'MADIX Convert');
    return $n === '' ? 'MADIX Convert' : $n;
}

/** ملف تطبيق أندرويد: المرفوع من لوحة الإدارة أو الموجود بمجلد download */
function apk_file(): string
{
    $up = data_dir() . '/app/MADIX-Convert.apk';
    if (is_file($up)) {
        return $up;
    }
    $repo = app_dir() . '/download/MADIX-Convert.apk';
    return is_file($repo) ? $repo : '';
}

function server_base_url(): string
{
    $host = isset($_SERVER['HTTP_HOST']) ? preg_replace('/[^A-Za-z0-9.:\-\[\]]/', '', (string)$_SERVER['HTTP_HOST']) : 'localhost';
    return (is_https() ? 'https' : 'http') . '://' . $host . rtrim(base_path(), '/');
}

function serve_apk(): void
{
    $f = apk_file();
    if ($f === '') {
        http_response_code(404);
        header('Content-Type: text/plain; charset=utf-8');
        echo 'APK not available';
        return;
    }
    header('Content-Type: application/vnd.android.package-archive');
    header('Content-Disposition: attachment; filename="MADIX-Convert.apk"');
    header('Content-Length: ' . (string)filesize($f));
    header('Cache-Control: no-cache');
    header('X-Content-Type-Options: nosniff');
    readfile($f);
}

/** اسم التطبيق كشعار: الكلمة الأولى عريضة والباقي أخف */
function wordmark_html(string $name): string
{
    $parts = preg_split('/\s+/u', trim($name), 2);
    $first = h((string)($parts[0] ?? ''));
    $rest = isset($parts[1]) ? ' <span>' . h((string)$parts[1]) . '</span>' : '';
    $latin = preg_match('/^[\x20-\x7E]+$/', $name) === 1;
    return '<span class="wm"' . ($latin ? ' dir="ltr"' : '') . '><b>' . $first . '</b>' . $rest . '</span>';
}

function boot_or_die(): void
{
    try {
        db();
        start_session();
    } catch (Throwable $e) {
        log_error($e);
        http_response_code(500);
        header('Content-Type: text/html; charset=utf-8');
        echo '<!doctype html><html lang="ar" dir="rtl"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>خطأ في التشغيل</title>'
            . '<body style="font-family:Tahoma,sans-serif;background:#0b1220;color:#e5e7eb;display:grid;place-items:center;min-height:100vh;margin:0;padding:16px">'
            . '<div style="max-width:520px;background:#121a2b;border:1px solid #1f2a40;border-radius:16px;padding:24px;line-height:1.8">'
            . '<h2 style="margin-top:0;color:#f87171">تعذّر تشغيل التطبيق</h2><p>' . h($e->getMessage()) . '</p>'
            . '<p style="color:#94a3b8;font-size:14px">تأكد أن PHP 7.4 أو أحدث، وأن امتداد pdo_sqlite مفعّل، وأن مجلد <b>data</b> قابل للكتابة.</p></div></body></html>';
        exit;
    }
}
