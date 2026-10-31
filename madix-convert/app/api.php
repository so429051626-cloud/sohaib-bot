<?php
/**
 * واجهة البرمجة (API) للتطبيق ولوحة الإدارة
 * Py | Sohaib
 */
declare(strict_types=1);

if (!defined('MADIX')) {
    http_response_code(403);
    exit;
}

require_once __DIR__ . '/ai.php';

/** يشغّل الإجراء المطلوب ويرجع JSON. $scope = 'app' أو 'admin' */
function api_dispatch(string $action, string $scope): void
{
    send_security_headers(false);
    $app = array(
        'me' => array('GET', 'public', 'api_me'),
        'register' => array('POST', 'public', 'api_register'),
        'login' => array('POST', 'public', 'api_login'),
        'logout' => array('POST', 'public', 'api_logout'),
        'profile' => array('POST', 'user', 'api_profile'),
        'password' => array('POST', 'user', 'api_password'),
        'redeem' => array('POST', 'user', 'api_redeem'),
        'ledger' => array('GET', 'user', 'api_ledger'),
        'scan' => array('POST', 'user', 'api_scan'),
    );
    $admin = array(
        'me' => array('GET', 'public', 'api_me'),
        'setup' => array('POST', 'public', 'api_setup'),
        'login' => array('POST', 'public', 'api_login'),
        'logout' => array('POST', 'public', 'api_logout'),
        'stats' => array('GET', 'admin', 'adm_stats'),
        'users' => array('GET', 'admin', 'adm_users'),
        'user_update' => array('POST', 'admin', 'adm_user_update'),
        'user_ledger' => array('GET', 'admin', 'adm_user_ledger'),
        'codes' => array('GET', 'admin', 'adm_codes'),
        'codes_generate' => array('POST', 'admin', 'adm_codes_generate'),
        'code_update' => array('POST', 'admin', 'adm_code_update'),
        'settings' => array('GET', 'admin', 'adm_settings'),
        'settings_save' => array('POST', 'admin', 'adm_settings_save'),
        'models' => array('GET', 'admin', 'adm_models'),
        'model_save' => array('POST', 'admin', 'adm_model_save'),
        'model_delete' => array('POST', 'admin', 'adm_model_delete'),
        'keys' => array('GET', 'admin', 'adm_keys'),
        'key_save' => array('POST', 'admin', 'adm_key_save'),
        'key_delete' => array('POST', 'admin', 'adm_key_delete'),
        'ai_test' => array('POST', 'admin', 'adm_ai_test'),
        'icon' => array('POST', 'admin', 'adm_icon'),
        'audit' => array('GET', 'admin', 'adm_audit'),
        'scans' => array('GET', 'admin', 'adm_scans'),
        'backup' => array('GET', 'admin', 'adm_backup'),
    );
    $routes = $scope === 'admin' ? $admin : $app;

    try {
        if (!isset($routes[$action])) {
            throw new ApiError('طلب غير معروف', 404);
        }
        list($method, $level, $fn) = $routes[$action];
        $reqMethod = isset($_SERVER['REQUEST_METHOD']) ? strtoupper((string)$_SERVER['REQUEST_METHOD']) : 'GET';
        if ($reqMethod !== $method) {
            throw new ApiError('طريقة الطلب غير صحيحة', 405);
        }
        if ($method === 'POST') {
            check_csrf();
        }
        if ($level === 'user') {
            require_user();
        } elseif ($level === 'admin') {
            require_admin();
        }
        $out = $fn();
        if ($out !== null) {
            json_out(array('ok' => true) + $out);
        }
    } catch (ApiError $e) {
        json_out(array('ok' => false, 'error' => $e->getMessage()) + $e->extra, $e->status);
    } catch (Throwable $e) {
        log_error($e);
        json_out(array('ok' => false, 'error' => 'صار خطأ غير متوقع بالسيرفر. حاول مرة ثانية'), 500);
    }
}

// ================================================================
// عام
// ================================================================

function app_public_info(): array
{
    $prices = array();
    foreach (preg_split('/\r?\n/', setting('prices')) as $line) {
        $line = trim($line);
        if ($line !== '') {
            $prices[] = $line;
        }
    }
    $models = array();
    foreach (ai_models_active() as $m) {
        $models[] = array(
            'id' => (string)$m['model_id'],
            'label' => (string)$m['label'],
            'description' => (string)$m['description'],
            'page_cost' => max(1, (int)$m['page_cost']),
            'default' => (int)$m['is_default'] === 1,
        );
    }
    return array(
        'name' => app_name(),
        'tagline' => setting('tagline'),
        'footer' => setting('footer_text', 'MADIX Convert — محوّل الكشوفات الذكي'),
        'contact' => setting('contact_phone'),
        'currency' => setting('currency', '₪'),
        'prices' => $prices,
        'allow_register' => setting('allow_register', '1') === '1',
        'signup_pages' => (int)setting('signup_pages', '2'),
        'free_retries' => (int)setting('free_retries', '2'),
        'max_image_mb' => (int)setting('max_image_mb', '6'),
        'announcement' => setting('announcement'),
        'maintenance' => setting('maintenance', '0') === '1',
        'models' => $models,
        'ai_ready' => ai_ready(),
        'version' => APP_VERSION,
        'icon_v' => setting('icon_version', '0'),
    );
}

function api_me(): array
{
    $u = current_user();
    $hasAdmin = (int)q_val("SELECT COUNT(*) FROM users WHERE role = 'admin'") > 0;
    return array(
        'csrf' => csrf_token(),
        'setup' => !$hasAdmin,
        'app' => app_public_info(),
        'user' => $u ? public_user($u) : null,
        'server_time' => now_utc(),
    );
}

function validate_password(string $pass): void
{
    if (str_len($pass) < 6) {
        throw new ApiError('كلمة السر لازم تكون 6 أحرف أو أكثر');
    }
    if (strlen($pass) > 200) {
        throw new ApiError('كلمة السر طويلة جداً');
    }
}

function validate_phone(string $phone): string
{
    $p = normalize_phone($phone);
    if (strlen($p) < 9 || strlen($p) > 15) {
        throw new ApiError('رقم الجوال غير صحيح');
    }
    return $p;
}

function validate_name(string $name): string
{
    $name = trim(preg_replace('/\s+/u', ' ', $name) ?? '');
    if (str_len($name) < 2) {
        throw new ApiError('اكتب اسمك');
    }
    return str_cut($name, 60);
}

/** إنشاء حساب المدير لأول مرة */
function api_setup(): array
{
    $hasAdmin = (int)q_val("SELECT COUNT(*) FROM users WHERE role = 'admin'") > 0;
    if ($hasAdmin) {
        throw new ApiError('النظام مُعد مسبقاً', 409);
    }
    rate_check('setup:' . client_ip(), 10, 3600, 'محاولات كثيرة، استنى شوي');
    rate_hit('setup:' . client_ip());
    $name = validate_name(in_str('name', 80));
    $phone = validate_phone(in_str('phone', 30));
    $pass = (string)(input()['password'] ?? '');
    validate_password($pass);

    db()->beginTransaction();
    try {
        $existing = q_one('SELECT * FROM users WHERE phone = ?', array($phone));
        if ($existing) {
            q("UPDATE users SET role = 'admin', name = ?, password_hash = ?, is_blocked = 0 WHERE id = ?", array($name, password_hash($pass, PASSWORD_DEFAULT), (int)$existing['id']));
            $uid = (int)$existing['id'];
        } else {
            q("INSERT INTO users (name, phone, password_hash, role, pages, created_at) VALUES (?, ?, ?, 'admin', 0, ?)", array($name, $phone, password_hash($pass, PASSWORD_DEFAULT), now_utc()));
            $uid = (int)db()->lastInsertId();
        }
        db()->commit();
    } catch (Throwable $e) {
        db()->rollBack();
        throw $e;
    }
    $u = q_one('SELECT * FROM users WHERE id = ?', array($uid));
    login_user($u);
    audit('إعداد النظام', 'إنشاء حساب المدير: ' . $phone, $uid);
    return array('user' => public_user($u), 'csrf' => csrf_token());
}

function api_register(): array
{
    if (setting('allow_register', '1') !== '1') {
        throw new ApiError('التسجيل مقفل حالياً، تواصل مع الإدارة', 403);
    }
    $ipKey = 'register:' . client_ip();
    rate_check($ipKey, 6, 3600, 'سجلت حسابات كثيرة من نفس الجهاز، حاول بعد ساعة');
    $name = validate_name(in_str('name', 80));
    $phone = validate_phone(in_str('phone', 30));
    $pass = (string)(input()['password'] ?? '');
    validate_password($pass);
    if (q_one('SELECT id FROM users WHERE phone = ?', array($phone))) {
        throw new ApiError('رقم الجوال مسجل مسبقاً — سجّل دخول بدلاً من ذلك', 409);
    }
    $free = max(0, min(1000, (int)setting('signup_pages', '2')));
    db()->beginTransaction();
    try {
        q('INSERT INTO users (name, phone, password_hash, role, pages, created_at) VALUES (?, ?, ?, \'user\', ?, ?)', array($name, $phone, password_hash($pass, PASSWORD_DEFAULT), $free, now_utc()));
        $uid = (int)db()->lastInsertId();
        if ($free > 0) {
            q('INSERT INTO ledger (user_id, delta, balance_after, kind, ref, note, created_at) VALUES (?, ?, ?, \'signup\', \'\', ?, ?)', array($uid, $free, $free, 'هدية التسجيل', now_utc()));
        }
        db()->commit();
    } catch (Throwable $e) {
        db()->rollBack();
        throw $e;
    }
    rate_hit($ipKey);
    $u = q_one('SELECT * FROM users WHERE id = ?', array($uid));
    login_user($u);
    audit('تسجيل حساب', $phone, $uid);
    return array('user' => public_user($u), 'csrf' => csrf_token());
}

function api_login(): array
{
    $phone = normalize_phone(in_str('phone', 30));
    $pass = (string)(input()['password'] ?? '');
    $ipKey = 'login:' . client_ip();
    $phKey = 'loginp:' . $phone;
    rate_check($ipKey, 15, 900, 'محاولات دخول كثيرة، استنى 15 دقيقة');
    rate_check($phKey, 6, 900, 'محاولات دخول كثيرة على هذا الرقم، استنى 15 دقيقة');
    $u = $phone !== '' ? q_one('SELECT * FROM users WHERE phone = ?', array($phone)) : null;
    if (!$u || !password_verify($pass, (string)$u['password_hash'])) {
        rate_hit($ipKey);
        rate_hit($phKey);
        throw new ApiError('رقم الجوال أو كلمة السر غير صحيحة', 401);
    }
    if ((int)$u['is_blocked'] === 1) {
        throw new ApiError('الحساب موقوف، تواصل مع الإدارة', 403);
    }
    if (password_needs_rehash((string)$u['password_hash'], PASSWORD_DEFAULT)) {
        q('UPDATE users SET password_hash = ? WHERE id = ?', array(password_hash($pass, PASSWORD_DEFAULT), (int)$u['id']));
    }
    rate_clear($phKey);
    login_user($u);
    audit('تسجيل دخول', '', (int)$u['id']);
    return array('user' => public_user($u), 'csrf' => csrf_token());
}

function api_logout(): array
{
    $_SESSION = array();
    session_regenerate_id(true);
    $_SESSION['csrf'] = bin2hex(random_bytes(24));
    return array('csrf' => csrf_token());
}

function api_profile(): array
{
    $u = require_user();
    $name = validate_name(in_str('name', 80));
    q('UPDATE users SET name = ? WHERE id = ?', array($name, (int)$u['id']));
    $u['name'] = $name;
    return array('user' => public_user($u));
}

function api_password(): array
{
    $u = require_user();
    $old = (string)(input()['old'] ?? '');
    $new = (string)(input()['new'] ?? '');
    if (!password_verify($old, (string)$u['password_hash'])) {
        throw new ApiError('كلمة السر الحالية غير صحيحة');
    }
    validate_password($new);
    q('UPDATE users SET password_hash = ? WHERE id = ?', array(password_hash($new, PASSWORD_DEFAULT), (int)$u['id']));
    audit('تغيير كلمة السر', '', (int)$u['id']);
    return array('message' => 'تم تغيير كلمة السر');
}

/** تنسيق الكود: أحرف وأرقام كبيرة بدون مسافات أو شرطات */
function normalize_code(string $code): string
{
    $c = strtoupper(to_latin_digits($code));
    $c = preg_replace('/[^A-Z0-9]/', '', $c);
    return $c === null ? '' : $c;
}

function format_code(string $c): string
{
    return implode('-', str_split($c, 4));
}

function api_redeem(): array
{
    $u = require_user();
    $key = 'redeem:' . (int)$u['id'];
    $ipKey = 'redeemip:' . client_ip();
    rate_check($key, 8, 1800, 'محاولات كثيرة لكود غلط، استنى نص ساعة');
    rate_check($ipKey, 20, 1800, 'محاولات كثيرة من نفس الجهاز، استنى نص ساعة');
    $code = normalize_code(in_str('code', 40));
    if (strlen($code) < 8) {
        throw new ApiError('اكتب الكود كامل');
    }
    $pdo = db();
    $pdo->beginTransaction();
    try {
        $row = q_one('SELECT * FROM codes WHERE code = ?', array($code));
        if (!$row || $row['status'] !== 'new') {
            $pdo->rollBack();
            rate_hit($key);
            rate_hit($ipKey);
            if ($row && $row['status'] === 'used') {
                throw new ApiError('هذا الكود مستخدم مسبقاً');
            }
            if ($row && $row['status'] === 'disabled') {
                throw new ApiError('هذا الكود موقوف');
            }
            throw new ApiError('الكود غير صحيح، تأكد منه وحاول مرة ثانية');
        }
        $upd = q("UPDATE codes SET status = 'used', used_by = ?, used_at = ? WHERE id = ? AND status = 'new'", array((int)$u['id'], now_utc(), (int)$row['id']));
        if ($upd->rowCount() !== 1) {
            $pdo->rollBack();
            throw new ApiError('هذا الكود مستخدم مسبقاً');
        }
        $pages = (int)$row['pages'];
        q('UPDATE users SET pages = pages + ? WHERE id = ?', array($pages, (int)$u['id']));
        $bal = (int)q_val('SELECT pages FROM users WHERE id = ?', array((int)$u['id']));
        q("INSERT INTO ledger (user_id, delta, balance_after, kind, ref, note, created_at) VALUES (?, ?, ?, 'code', ?, ?, ?)", array((int)$u['id'], $pages, $bal, format_code($code), 'شحن بكود', now_utc()));
        $pdo->commit();
    } catch (ApiError $e) {
        if ($pdo->inTransaction()) {
            $pdo->rollBack();
        }
        throw $e;
    } catch (Throwable $e) {
        if ($pdo->inTransaction()) {
            $pdo->rollBack();
        }
        throw $e;
    }
    audit('شحن كود', format_code($code) . ' (+' . $pages . ')', (int)$u['id']);
    return array('added' => $pages, 'pages' => $bal, 'message' => 'تم شحن ' . $pages . ' صفحة ✓');
}

function api_ledger(): array
{
    $u = require_user();
    $rows = q('SELECT delta, balance_after, kind, ref, note, created_at FROM ledger WHERE user_id = ? ORDER BY id DESC LIMIT 200', array((int)$u['id']))->fetchAll();
    return array('items' => $rows, 'pages' => (int)$u['pages']);
}

// ================================================================
// القراءة بالذكاء الاصطناعي
// ================================================================

function api_scan(): array
{
    $u = require_user();
    $uid = (int)$u['id'];
    // نحرر قفل الجلسة حتى ما تتعطل باقي طلبات المستخدم خلال القراءة
    session_write_close();
    @set_time_limit(600);
    ignore_user_abort(true);

    if (setting('maintenance', '0') === '1' && $u['role'] !== 'admin') {
        throw new ApiError('التطبيق بصيانة حالياً، حاول بعد شوي', 503);
    }
    if (!ai_ready()) {
        throw new ApiError('الخدمة غير مفعّلة بعد — المدير لازم يضيف مفتاح API ونموذج من لوحة الإدارة', 503);
    }
    $limit = max(5, (int)setting('scan_rate_limit', '40'));
    rate_check('scan:' . $uid, $limit, 600, 'طلبات كثيرة خلال وقت قصير، استنى دقائق وحاول');

    if (empty($_FILES['image']) || !is_array($_FILES['image']) || (int)$_FILES['image']['error'] !== UPLOAD_ERR_OK) {
        $err = isset($_FILES['image']['error']) ? (int)$_FILES['image']['error'] : -1;
        if ($err === UPLOAD_ERR_INI_SIZE || $err === UPLOAD_ERR_FORM_SIZE) {
            throw new ApiError('حجم الصورة أكبر من المسموح على السيرفر', 413);
        }
        throw new ApiError('ما وصلت الصورة، تأكد من النت وحاول مرة ثانية');
    }
    $tmp = (string)$_FILES['image']['tmp_name'];
    $size = (int)filesize($tmp);
    $maxMb = max(1, (int)setting('max_image_mb', '6'));
    if ($size <= 0 || $size > $maxMb * 1024 * 1024) {
        throw new ApiError('حجم الصورة غير مناسب (الحد ' . $maxMb . ' ميجا)', 413);
    }
    $info = @getimagesize($tmp);
    $allowed = array(IMAGETYPE_JPEG => 'image/jpeg', IMAGETYPE_PNG => 'image/png', IMAGETYPE_WEBP => 'image/webp');
    if (!$info || !isset($allowed[$info[2]])) {
        throw new ApiError('الملف لازم يكون صورة (JPG أو PNG أو WEBP)');
    }
    $mime = $allowed[$info[2]];
    $w = (int)$info[0];
    $h = (int)$info[1];
    if ($w < 200 || $h < 200) {
        throw new ApiError('الصورة صغيرة جداً، صوّر الكشف بشكل أوضح');
    }
    $bytes = (string)file_get_contents($tmp);
    @unlink($tmp); // الصورة ما بتنحفظ على السيرفر أبداً
    $hash = hash('sha256', $bytes);

    if ($w > 2576 || $h > 2576) {
        $resized = shrink_image($bytes, 2576);
        if ($resized !== null) {
            list($bytes, $w, $h) = $resized;
            $mime = 'image/jpeg';
        }
    }

    $model = ai_model_for(in_str('model', 80));
    if (!$model) {
        throw new ApiError('لا يوجد نموذج ذكاء اصطناعي مفعّل', 503);
    }
    $cost = max(1, (int)$model['page_cost']);

    $hint = array();
    $rawHint = json_decode(in_str('columns', 4000), true);
    if (is_array($rawHint)) {
        foreach (array_slice($rawHint, 0, 30) as $c) {
            if (is_array($c) && isset($c['label'])) {
                $kind = isset($c['kind']) && in_array($c['kind'], COLUMN_KINDS, true) ? (string)$c['kind'] : 'text';
                $hint[] = array('label' => str_cut(trim((string)$c['label']), 60), 'kind' => $kind);
            }
        }
    }

    // إعادة قراءة نفس الصورة مجاناً خلال 24 ساعة (لو النت قطع أو بدك تعيد)
    $freeRetries = max(0, (int)setting('free_retries', '2'));
    $since = gmdate('Y-m-d H:i:s', time() - 86400);
    $prevCharged = (int)q_val("SELECT COUNT(*) FROM scans WHERE user_id = ? AND img_hash = ? AND charged > 0 AND status = 'ok' AND created_at > ?", array($uid, $hash, $since));
    $prevFree = (int)q_val("SELECT COUNT(*) FROM scans WHERE user_id = ? AND img_hash = ? AND charged = 0 AND status = 'ok' AND created_at > ?", array($uid, $hash, $since));
    $isAdmin = $u['role'] === 'admin';
    $charge = ($isAdmin || ($prevCharged > 0 && $prevFree < $freeRetries)) ? 0 : $cost;

    rate_hit('scan:' . $uid);

    if ($charge > 0) {
        // نحجز الرصيد قبل القراءة (عملية ذرية تمنع السحب المزدوج)
        $res = q('UPDATE users SET pages = pages - ? WHERE id = ? AND pages >= ?', array($charge, $uid, $charge));
        if ($res->rowCount() !== 1) {
            throw new ApiError('رصيدك ما بكفي. اشحن بكود من المحفظة', 402, array('code' => 'no_balance'));
        }
    }

    try {
        $result = ai_extract($bytes, $mime, $w, $h, $hint, $model);
    } catch (Throwable $e) {
        if ($charge > 0) {
            q('UPDATE users SET pages = pages + ? WHERE id = ?', array($charge, $uid));
        }
        $msg = $e instanceof ApiError ? $e->getMessage() : 'error';
        if (!($e instanceof ApiError)) {
            log_error($e);
        }
        q("INSERT INTO scans (user_id, img_hash, model, status, rows_count, charged, error, created_at) VALUES (?, ?, ?, 'fail', 0, 0, ?, ?)", array($uid, $hash, (string)$model['model_id'], str_cut($msg, 300), now_utc()));
        throw $e;
    }

    $data = $result['data'];
    $rowsCount = count($data['rows']);
    if (!$data['is_table'] || $rowsCount === 0) {
        if ($charge > 0) {
            q('UPDATE users SET pages = pages + ? WHERE id = ?', array($charge, $uid));
        }
        q("INSERT INTO scans (user_id, img_hash, model, status, rows_count, charged, input_tokens, output_tokens, cost_usd, ms, error, created_at) VALUES (?, ?, ?, 'empty', 0, 0, ?, ?, ?, ?, ?, ?)", array($uid, $hash, $result['model'], $result['input_tokens'], $result['output_tokens'], $result['cost'], $result['ms'], str_cut($data['notes'], 300), now_utc()));
        $note = $data['notes'] !== '' ? ' (' . $data['notes'] . ')' : '';
        throw new ApiError('ما لقينا جدول أو كشف بالصورة' . $note . '. ما انخصم شي من رصيدك', 422);
    }

    q("INSERT INTO scans (user_id, img_hash, model, status, rows_count, charged, input_tokens, output_tokens, cost_usd, ms, created_at) VALUES (?, ?, ?, 'ok', ?, ?, ?, ?, ?, ?, ?)", array($uid, $hash, $result['model'], $rowsCount, $charge, $result['input_tokens'], $result['output_tokens'], $result['cost'], $result['ms'], now_utc()));
    $balance = (int)q_val('SELECT pages FROM users WHERE id = ?', array($uid));
    if ($charge > 0) {
        q('UPDATE users SET pages_used = pages_used + ? WHERE id = ?', array($charge, $uid));
        q("INSERT INTO ledger (user_id, delta, balance_after, kind, ref, note, created_at) VALUES (?, ?, ?, 'scan', ?, ?, ?)", array($uid, -$charge, $balance, substr($hash, 0, 10), 'قراءة صفحة (' . $rowsCount . ' صف)', now_utc()));
    }

    return array(
        'result' => $data,
        'charged' => $charge,
        'pages' => $balance,
        'free_retry' => $charge === 0 && !$isAdmin,
        'size' => array('w' => $w, 'h' => $h),
    );
}

/** تصغير الصورة على السيرفر لو كانت أكبر من حد النموذج */
function shrink_image(string $bytes, int $max): ?array
{
    if (!function_exists('imagecreatefromstring')) {
        return null;
    }
    $src = @imagecreatefromstring($bytes);
    if (!$src) {
        return null;
    }
    $w = imagesx($src);
    $h = imagesy($src);
    $scale = $max / max($w, $h);
    $nw = max(1, (int)round($w * $scale));
    $nh = max(1, (int)round($h * $scale));
    $dst = imagecreatetruecolor($nw, $nh);
    imagecopyresampled($dst, $src, 0, 0, 0, 0, $nw, $nh, $w, $h);
    ob_start();
    imagejpeg($dst, null, 88);
    $out = (string)ob_get_clean();
    imagedestroy($src);
    imagedestroy($dst);
    return array($out, $nw, $nh);
}

// ================================================================
// لوحة الإدارة
// ================================================================

function adm_stats(): array
{
    $day = gmdate('Y-m-d H:i:s', time() - 86400);
    $week = gmdate('Y-m-d H:i:s', time() - 7 * 86400);
    $month = gmdate('Y-m-d H:i:s', time() - 30 * 86400);

    $series = array();
    $rows = q("SELECT substr(created_at, 1, 10) AS d, COUNT(*) AS n, SUM(charged) AS pages FROM scans WHERE status = 'ok' AND created_at > ? GROUP BY d", array(gmdate('Y-m-d 00:00:00', time() - 13 * 86400)))->fetchAll();
    $map = array();
    foreach ($rows as $r) {
        $map[$r['d']] = array((int)$r['n'], (int)$r['pages']);
    }
    for ($i = 13; $i >= 0; $i--) {
        $d = gmdate('Y-m-d', time() - $i * 86400);
        $series[] = array('d' => $d, 'scans' => isset($map[$d]) ? $map[$d][0] : 0, 'pages' => isset($map[$d]) ? $map[$d][1] : 0);
    }

    $top = q("SELECT u.id, u.name, u.phone, u.pages, u.pages_used FROM users u ORDER BY u.pages_used DESC LIMIT 5")->fetchAll();

    return array(
        'users' => array(
            'total' => (int)q_val('SELECT COUNT(*) FROM users'),
            'today' => (int)q_val('SELECT COUNT(*) FROM users WHERE created_at > ?', array($day)),
            'blocked' => (int)q_val('SELECT COUNT(*) FROM users WHERE is_blocked = 1'),
            'balance' => (int)q_val('SELECT COALESCE(SUM(pages), 0) FROM users'),
        ),
        'scans' => array(
            'total' => (int)q_val("SELECT COUNT(*) FROM scans WHERE status = 'ok'"),
            'today' => (int)q_val("SELECT COUNT(*) FROM scans WHERE status = 'ok' AND created_at > ?", array($day)),
            'week' => (int)q_val("SELECT COUNT(*) FROM scans WHERE status = 'ok' AND created_at > ?", array($week)),
            'failed_week' => (int)q_val("SELECT COUNT(*) FROM scans WHERE status != 'ok' AND created_at > ?", array($week)),
            'rows' => (int)q_val("SELECT COALESCE(SUM(rows_count), 0) FROM scans WHERE status = 'ok'"),
            'pages_charged' => (int)q_val('SELECT COALESCE(SUM(charged), 0) FROM scans'),
            'avg_ms' => (int)q_val("SELECT COALESCE(AVG(ms), 0) FROM scans WHERE status = 'ok' AND created_at > ?", array($month)),
        ),
        'codes' => array(
            'new' => (int)q_val("SELECT COUNT(*) FROM codes WHERE status = 'new'"),
            'used' => (int)q_val("SELECT COUNT(*) FROM codes WHERE status = 'used'"),
            'pages_sold' => (int)q_val("SELECT COALESCE(SUM(pages), 0) FROM codes WHERE status = 'used'"),
            'revenue' => (float)q_val("SELECT COALESCE(SUM(price), 0) FROM codes WHERE status = 'used'"),
            'revenue_month' => (float)q_val("SELECT COALESCE(SUM(price), 0) FROM codes WHERE status = 'used' AND used_at > ?", array($month)),
        ),
        'cost' => array(
            'total' => round((float)q_val('SELECT COALESCE(SUM(cost_usd), 0) FROM scans'), 4),
            'month' => round((float)q_val('SELECT COALESCE(SUM(cost_usd), 0) FROM scans WHERE created_at > ?', array($month)), 4),
            'per_page' => round((float)q_val("SELECT COALESCE(AVG(cost_usd), 0) FROM scans WHERE status = 'ok' AND cost_usd > 0 AND created_at > ?", array($month)), 4),
        ),
        'series' => $series,
        'top_users' => $top,
        'ai_ready' => ai_ready(),
        'mock' => ai_mock_enabled(),
        'currency' => setting('currency', '₪'),
        'env' => array(
            'php' => PHP_VERSION,
            'gd' => function_exists('imagecreatefromstring'),
            'curl' => function_exists('curl_init'),
            'sodium' => function_exists('sodium_crypto_secretbox'),
            'upload_max' => (string)ini_get('upload_max_filesize'),
            'post_max' => (string)ini_get('post_max_size'),
            'https' => is_https(),
        ),
    );
}

function adm_users(): array
{
    $search = isset($_GET['q']) ? trim((string)$_GET['q']) : '';
    $page = max(1, isset($_GET['page']) ? (int)$_GET['page'] : 1);
    $per = 30;
    $where = '';
    $params = array();
    if ($search !== '') {
        $where = 'WHERE name LIKE ? OR phone LIKE ?';
        $like = '%' . str_replace(array('%', '_'), array('', ''), to_latin_digits($search)) . '%';
        $params = array($like, $like);
    }
    $total = (int)q_val("SELECT COUNT(*) FROM users $where", $params);
    $items = q("SELECT id, name, phone, role, pages, pages_used, is_blocked, note, created_at, last_login_at FROM users $where ORDER BY id DESC LIMIT $per OFFSET " . (($page - 1) * $per), $params)->fetchAll();
    return array('items' => $items, 'total' => $total, 'page' => $page, 'per' => $per);
}

function adm_user_update(): array
{
    $me = require_admin();
    $id = in_int('id');
    $u = q_one('SELECT * FROM users WHERE id = ?', array($id));
    if (!$u) {
        throw new ApiError('المستخدم غير موجود', 404);
    }
    $op = in_str('op', 20);
    $result = array();
    if ($op === 'pages') {
        $delta = in_int('delta');
        if ($delta === 0 || abs($delta) > 100000) {
            throw new ApiError('اكتب عدد صفحات صحيح');
        }
        $note = in_str('note', 200);
        db()->beginTransaction();
        try {
            if ($delta < 0) {
                $r = q('UPDATE users SET pages = pages + ? WHERE id = ? AND pages + ? >= 0', array($delta, $id, $delta));
                if ($r->rowCount() !== 1) {
                    throw new ApiError('رصيد المستخدم أقل من الكمية المطلوب خصمها');
                }
            } else {
                q('UPDATE users SET pages = pages + ? WHERE id = ?', array($delta, $id));
            }
            $bal = (int)q_val('SELECT pages FROM users WHERE id = ?', array($id));
            q("INSERT INTO ledger (user_id, delta, balance_after, kind, ref, note, created_at) VALUES (?, ?, ?, 'admin', ?, ?, ?)", array($id, $delta, $bal, (string)$me['name'], $note === '' ? 'تعديل من الإدارة' : $note, now_utc()));
            db()->commit();
        } catch (Throwable $e) {
            if (db()->inTransaction()) {
                db()->rollBack();
            }
            throw $e;
        }
        audit('تعديل رصيد', $u['phone'] . ' (' . ($delta > 0 ? '+' : '') . $delta . ')');
        $result['pages'] = $bal;
    } elseif ($op === 'block') {
        if ($id === (int)$me['id']) {
            throw new ApiError('ما بتقدر توقف حسابك');
        }
        $block = in_bool('value') ? 1 : 0;
        q('UPDATE users SET is_blocked = ?, session_v = session_v + 1 WHERE id = ?', array($block, $id));
        audit($block ? 'إيقاف حساب' : 'تفعيل حساب', (string)$u['phone']);
    } elseif ($op === 'role') {
        if ($id === (int)$me['id']) {
            throw new ApiError('ما بتقدر تغيّر صلاحية حسابك');
        }
        $role = in_str('value', 10) === 'admin' ? 'admin' : 'user';
        q('UPDATE users SET role = ?, session_v = session_v + 1 WHERE id = ?', array($role, $id));
        audit('تغيير صلاحية', $u['phone'] . ' → ' . $role);
    } elseif ($op === 'reset') {
        $temp = (string)random_int(100000, 999999);
        q('UPDATE users SET password_hash = ?, session_v = session_v + 1 WHERE id = ?', array(password_hash($temp, PASSWORD_DEFAULT), $id));
        audit('إعادة تعيين كلمة السر', (string)$u['phone']);
        $result['temp_password'] = $temp;
    } elseif ($op === 'note') {
        q('UPDATE users SET note = ? WHERE id = ?', array(in_str('value', 300), $id));
    } elseif ($op === 'delete') {
        if ($id === (int)$me['id']) {
            throw new ApiError('ما بتقدر تحذف حسابك');
        }
        db()->beginTransaction();
        q('DELETE FROM ledger WHERE user_id = ?', array($id));
        q('DELETE FROM users WHERE id = ?', array($id));
        db()->commit();
        audit('حذف مستخدم', (string)$u['phone']);
    } else {
        throw new ApiError('عملية غير معروفة');
    }
    return $result;
}

function adm_user_ledger(): array
{
    $id = isset($_GET['id']) ? (int)$_GET['id'] : 0;
    $items = q('SELECT delta, balance_after, kind, ref, note, created_at FROM ledger WHERE user_id = ? ORDER BY id DESC LIMIT 100', array($id))->fetchAll();
    return array('items' => $items);
}

function adm_codes(): array
{
    $status = isset($_GET['status']) ? (string)$_GET['status'] : '';
    $batch = isset($_GET['batch']) ? trim((string)$_GET['batch']) : '';
    $search = isset($_GET['q']) ? normalize_code((string)$_GET['q']) : '';
    $page = max(1, isset($_GET['page']) ? (int)$_GET['page'] : 1);
    $per = isset($_GET['all']) ? 2000 : 50;
    $w = array();
    $p = array();
    if (in_array($status, array('new', 'used', 'disabled'), true)) {
        $w[] = 'c.status = ?';
        $p[] = $status;
    }
    if ($batch !== '') {
        $w[] = 'c.batch = ?';
        $p[] = $batch;
    }
    if ($search !== '') {
        $w[] = 'c.code LIKE ?';
        $p[] = '%' . $search . '%';
    }
    $where = $w ? 'WHERE ' . implode(' AND ', $w) : '';
    $total = (int)q_val("SELECT COUNT(*) FROM codes c $where", $p);
    $items = q("SELECT c.id, c.code, c.pages, c.price, c.batch, c.status, c.created_at, c.used_at, u.name AS used_by_name, u.phone AS used_by_phone FROM codes c LEFT JOIN users u ON u.id = c.used_by $where ORDER BY c.id DESC LIMIT $per OFFSET " . (($page - 1) * $per), $p)->fetchAll();
    foreach ($items as &$it) {
        $it['code'] = format_code((string)$it['code']);
    }
    unset($it);
    $batches = q("SELECT batch, COUNT(*) AS n, SUM(status = 'used') AS used, MAX(created_at) AS created_at, MAX(pages) AS pages, MAX(price) AS price FROM codes WHERE batch != '' GROUP BY batch ORDER BY created_at DESC LIMIT 50")->fetchAll();
    return array('items' => $items, 'total' => $total, 'page' => $page, 'per' => $per, 'batches' => $batches);
}

function adm_codes_generate(): array
{
    $me = require_admin();
    $count = in_int('count', 0);
    $pages = in_int('pages', 0);
    $price = round(in_float('price', 0), 2);
    $batch = in_str('batch', 60);
    if ($count < 1 || $count > 1000) {
        throw new ApiError('عدد الأكواد لازم يكون بين 1 و 1000');
    }
    if ($pages < 1 || $pages > 100000) {
        throw new ApiError('عدد الصفحات بكل كود غير صحيح');
    }
    if ($price < 0) {
        throw new ApiError('السعر غير صحيح');
    }
    if ($batch === '') {
        $batch = 'دفعة ' . gmdate('Y-m-d H:i');
    }
    $alphabet = '23456789ABCDEFGHJKMNPQRSTUVWXYZ';
    $alen = strlen($alphabet);
    $codes = array();
    $pdo = db();
    $pdo->beginTransaction();
    try {
        $st = $pdo->prepare("INSERT OR IGNORE INTO codes (code, pages, price, batch, status, created_by, created_at) VALUES (?, ?, ?, ?, 'new', ?, ?)");
        $guard = 0;
        while (count($codes) < $count && $guard < $count * 5) {
            $guard++;
            $c = '';
            for ($i = 0; $i < 12; $i++) {
                $c .= $alphabet[random_int(0, $alen - 1)];
            }
            $st->execute(array($c, $pages, $price, $batch, (int)$me['id'], now_utc()));
            if ($st->rowCount() === 1) {
                $codes[] = format_code($c);
            }
        }
        $pdo->commit();
    } catch (Throwable $e) {
        $pdo->rollBack();
        throw $e;
    }
    audit('توليد أكواد', $count . ' كود × ' . $pages . ' صفحة — ' . $batch);
    return array('codes' => $codes, 'batch' => $batch, 'pages' => $pages, 'price' => $price);
}

function adm_code_update(): array
{
    $id = in_int('id');
    $op = in_str('op', 20);
    $row = q_one('SELECT * FROM codes WHERE id = ?', array($id));
    if (!$row) {
        throw new ApiError('الكود غير موجود', 404);
    }
    if ($op === 'disable' && $row['status'] === 'new') {
        q("UPDATE codes SET status = 'disabled' WHERE id = ?", array($id));
        audit('إيقاف كود', format_code((string)$row['code']));
    } elseif ($op === 'enable' && $row['status'] === 'disabled') {
        q("UPDATE codes SET status = 'new' WHERE id = ?", array($id));
        audit('تفعيل كود', format_code((string)$row['code']));
    } elseif ($op === 'delete' && $row['status'] !== 'used') {
        q('DELETE FROM codes WHERE id = ?', array($id));
        audit('حذف كود', format_code((string)$row['code']));
    } elseif ($op === 'disable_batch') {
        $n = q("UPDATE codes SET status = 'disabled' WHERE batch = ? AND status = 'new'", array((string)$row['batch']))->rowCount();
        audit('إيقاف دفعة', $row['batch'] . ' (' . $n . ')');
    } else {
        throw new ApiError('العملية غير ممكنة على هذا الكود');
    }
    return array();
}

function settings_keys(): array
{
    return array('app_name', 'tagline', 'footer_text', 'contact_phone', 'currency', 'prices', 'signup_pages', 'allow_register', 'free_retries', 'scan_rate_limit', 'max_image_mb', 'announcement', 'maintenance');
}

function adm_settings(): array
{
    $out = array();
    foreach (settings_keys() as $k) {
        $out[$k] = setting($k);
    }
    return array('settings' => $out, 'icon_v' => setting('icon_version', '0'), 'has_custom_icon' => is_file(sub_data_dir('icons') . '/icon-512.png'));
}

function adm_settings_save(): array
{
    $in = input();
    $ints = array('signup_pages' => array(0, 1000), 'free_retries' => array(0, 10), 'scan_rate_limit' => array(5, 1000), 'max_image_mb' => array(1, 20));
    $bools = array('allow_register', 'maintenance');
    foreach (settings_keys() as $k) {
        if (!array_key_exists($k, $in)) {
            continue;
        }
        if (isset($ints[$k])) {
            $v = (string)max($ints[$k][0], min($ints[$k][1], in_int($k)));
        } elseif (in_array($k, $bools, true)) {
            $v = in_bool($k) ? '1' : '0';
        } elseif ($k === 'prices' || $k === 'announcement') {
            $v = str_cut(trim((string)$in[$k]), 2000);
        } else {
            $v = in_str($k, 200);
        }
        if ($k === 'app_name' && $v === '') {
            $v = 'MADIX Convert';
        }
        set_setting($k, $v);
    }
    audit('حفظ الإعدادات');
    return adm_settings();
}

// ---------------- النماذج ----------------

function adm_models(): array
{
    $items = q('SELECT m.*, (SELECT COUNT(*) FROM scans s WHERE s.model = m.model_id AND s.status = \'ok\') AS uses, (SELECT COALESCE(SUM(cost_usd), 0) FROM scans s WHERE s.model = m.model_id) AS cost FROM ai_models m ORDER BY m.is_default DESC, m.sort ASC, m.id ASC')->fetchAll();
    return array('items' => $items);
}

function adm_model_save(): array
{
    $id = in_int('id');
    $modelId = in_str('model_id', 100);
    if (!preg_match('/^[A-Za-z0-9._:@\/-]{3,100}$/', $modelId)) {
        throw new ApiError('اسم النموذج (Model ID) غير صحيح — مثال: claude-opus-5-5');
    }
    $label = in_str('label', 60);
    if ($label === '') {
        $label = $modelId;
    }
    $effort = in_str('effort', 10);
    if (!in_array($effort, array('', 'low', 'medium', 'high', 'xhigh', 'max'), true)) {
        $effort = 'medium';
    }
    $fields = array(
        $modelId,
        $label,
        in_str('description', 200),
        max(0, in_float('price_in')),
        max(0, in_float('price_out')),
        $effort,
        max(1000, min(64000, in_int('max_tokens', 16000))),
        in_bool('use_fallbacks') ? 1 : 0,
        max(1, min(100, in_int('page_cost', 1))),
        in_bool('is_active') ? 1 : 0,
        in_int('sort', 0),
    );
    $dupe = q_one('SELECT id FROM ai_models WHERE model_id = ? AND id != ?', array($modelId, $id));
    if ($dupe) {
        throw new ApiError('هذا النموذج مضاف مسبقاً');
    }
    db()->beginTransaction();
    try {
        if ($id > 0) {
            $fields[] = $id;
            q('UPDATE ai_models SET model_id = ?, label = ?, description = ?, price_in = ?, price_out = ?, effort = ?, max_tokens = ?, use_fallbacks = ?, page_cost = ?, is_active = ?, sort = ? WHERE id = ?', $fields);
        } else {
            $fields[] = now_utc();
            q('INSERT INTO ai_models (model_id, label, description, price_in, price_out, effort, max_tokens, use_fallbacks, page_cost, is_active, sort, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)', $fields);
            $id = (int)db()->lastInsertId();
        }
        if (in_bool('is_default')) {
            q('UPDATE ai_models SET is_default = CASE WHEN id = ? THEN 1 ELSE 0 END', array($id));
            q('UPDATE ai_models SET is_active = 1 WHERE id = ?', array($id));
        }
        // لازم يكون في نموذج افتراضي مفعّل دائماً
        $hasDefault = (int)q_val('SELECT COUNT(*) FROM ai_models WHERE is_default = 1 AND is_active = 1');
        if ($hasDefault === 0) {
            q('UPDATE ai_models SET is_default = 0');
            q('UPDATE ai_models SET is_default = 1 WHERE id = (SELECT id FROM ai_models WHERE is_active = 1 ORDER BY sort, id LIMIT 1)');
        }
        db()->commit();
    } catch (Throwable $e) {
        if (db()->inTransaction()) {
            db()->rollBack();
        }
        throw $e;
    }
    audit('حفظ نموذج', $modelId);
    return adm_models();
}

function adm_model_delete(): array
{
    $id = in_int('id');
    $m = q_one('SELECT * FROM ai_models WHERE id = ?', array($id));
    if (!$m) {
        throw new ApiError('النموذج غير موجود', 404);
    }
    if ((int)q_val('SELECT COUNT(*) FROM ai_models') <= 1) {
        throw new ApiError('لازم يضل نموذج واحد على الأقل');
    }
    q('DELETE FROM ai_models WHERE id = ?', array($id));
    if ((int)q_val('SELECT COUNT(*) FROM ai_models WHERE is_default = 1 AND is_active = 1') === 0) {
        q('UPDATE ai_models SET is_default = 1 WHERE id = (SELECT id FROM ai_models WHERE is_active = 1 ORDER BY sort, id LIMIT 1)');
    }
    audit('حذف نموذج', (string)$m['model_id']);
    return adm_models();
}

// ---------------- مفاتيح API ----------------

function adm_keys(): array
{
    $items = q('SELECT id, label, key_hint, is_active, priority, ok_count, fail_count, last_used_at, last_error, last_error_at, created_at FROM api_keys ORDER BY priority ASC, id ASC')->fetchAll();
    $env = getenv('ANTHROPIC_API_KEY');
    return array('items' => $items, 'env_key' => $env !== false && trim($env) !== '');
}

function adm_key_save(): array
{
    $id = in_int('id');
    $label = in_str('label', 60);
    $key = trim((string)(input()['key'] ?? ''));
    $active = in_bool('is_active') ? 1 : 0;
    $priority = in_int('priority', 0);
    if ($id > 0) {
        $row = q_one('SELECT id FROM api_keys WHERE id = ?', array($id));
        if (!$row) {
            throw new ApiError('المفتاح غير موجود', 404);
        }
        if ($key !== '') {
            validate_api_key($key);
            q('UPDATE api_keys SET key_enc = ?, key_hint = ?, last_error = \'\', fail_count = 0 WHERE id = ?', array(encrypt_secret($key), key_hint($key), $id));
        }
        q('UPDATE api_keys SET label = ?, is_active = ?, priority = ? WHERE id = ?', array($label, $active, $priority, $id));
        audit('تعديل مفتاح API', $label);
    } else {
        validate_api_key($key);
        q('INSERT INTO api_keys (label, key_enc, key_hint, is_active, priority, created_at) VALUES (?, ?, ?, ?, ?, ?)', array($label === '' ? 'مفتاح' : $label, encrypt_secret($key), key_hint($key), $active, $priority, now_utc()));
        audit('إضافة مفتاح API', $label);
    }
    return adm_keys();
}

function validate_api_key(string $key): void
{
    if (strlen($key) < 20 || strlen($key) > 300 || preg_match('/\s/', $key)) {
        throw new ApiError('المفتاح غير صحيح — انسخه كامل من console.anthropic.com');
    }
}

function key_hint(string $key): string
{
    return substr($key, 0, 7) . '…' . substr($key, -4);
}

function adm_key_delete(): array
{
    $id = in_int('id');
    q('DELETE FROM api_keys WHERE id = ?', array($id));
    audit('حذف مفتاح API', (string)$id);
    return adm_keys();
}

function adm_ai_test(): array
{
    @set_time_limit(120);
    $keyId = in_int('key_id', -1);
    $modelId = in_str('model_id', 100);
    if ($modelId === '') {
        $m = ai_model_for('');
        $modelId = $m ? (string)$m['model_id'] : 'claude-opus-5-5';
    }
    $raw = trim((string)(input()['key'] ?? ''));
    if ($raw === '') {
        if ($keyId > 0) {
            $row = q_one('SELECT key_enc FROM api_keys WHERE id = ?', array($keyId));
            $raw = $row ? decrypt_secret((string)$row['key_enc']) : '';
        } else {
            $keys = ai_keys_active();
            $raw = $keys ? $keys[0]['key'] : '';
        }
    }
    if ($raw === '') {
        throw new ApiError('أضف مفتاح API أولاً');
    }
    $r = ai_test($raw, $modelId);
    if ($keyId > 0) {
        ai_key_mark($keyId, $r['ok'], $r['ok'] ? '' : $r['message']);
        if ($r['ok']) {
            q("UPDATE api_keys SET last_error = '' WHERE id = ?", array($keyId));
        }
    }
    return array('result' => $r);
}

// ---------------- الأيقونة ----------------

function adm_icon(): array
{
    require_once __DIR__ . '/icons.php';
    if (in_str('op', 10) === 'reset') {
        icon_reset();
        audit('إرجاع الأيقونة الافتراضية');
    } else {
        if (empty($_FILES['icon']) || (int)$_FILES['icon']['error'] !== UPLOAD_ERR_OK) {
            throw new ApiError('اختر صورة للأيقونة');
        }
        icon_save_upload((string)$_FILES['icon']['tmp_name']);
        audit('تغيير أيقونة التطبيق');
    }
    set_setting('icon_version', (string)time());
    return adm_settings();
}

function adm_audit(): array
{
    $items = q('SELECT a.action, a.details, a.ip, a.created_at, u.name, u.phone FROM audit_log a LEFT JOIN users u ON u.id = a.user_id ORDER BY a.id DESC LIMIT 300')->fetchAll();
    return array('items' => $items);
}

function adm_scans(): array
{
    $items = q('SELECT s.id, s.model, s.status, s.rows_count, s.charged, s.input_tokens, s.output_tokens, s.cost_usd, s.ms, s.error, s.created_at, u.name, u.phone FROM scans s LEFT JOIN users u ON u.id = s.user_id ORDER BY s.id DESC LIMIT 300')->fetchAll();
    return array('items' => $items);
}

/** نسخة احتياطية من قاعدة البيانات */
function adm_backup(): ?array
{
    $path = data_dir() . '/' . app_config()['db'];
    try {
        db()->exec('PRAGMA wal_checkpoint(FULL)');
    } catch (Throwable $e) {
        // تجاهل
    }
    audit('تحميل نسخة احتياطية');
    header('Content-Type: application/octet-stream');
    header('Content-Disposition: attachment; filename="madix-convert-backup-' . gmdate('Y-m-d') . '.sqlite"');
    header('Content-Length: ' . (string)filesize($path));
    header('Cache-Control: no-store');
    readfile($path);
    return null;
}
