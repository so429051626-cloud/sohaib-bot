<?php
/**
 * محرك القراءة بالذكاء الاصطناعي (Claude API)
 * يقرأ صورة الكشف ويرجع جدول منظم (أعمدة + صفوف) بصيغة JSON مضمونة.
 * Py | Sohaib
 */
declare(strict_types=1);

if (!defined('MADIX')) {
    http_response_code(403);
    exit;
}

const COLUMN_KINDS = array('serial', 'name', 'national_id', 'phone', 'number', 'date', 'text');

const AI_PROVIDERS = array('anthropic' => 'Claude', 'google' => 'Gemini');

function ai_models_active(): array
{
    return q('SELECT * FROM ai_models WHERE is_active = 1 ORDER BY is_default DESC, sort ASC, id ASC')->fetchAll();
}

/** النماذج المفعّلة اللي إلها مفتاح API جاهز */
function ai_models_usable(): array
{
    $out = array();
    $has = array();
    foreach (ai_models_active() as $m) {
        $p = model_provider($m);
        if (!isset($has[$p])) {
            $has[$p] = ai_mock_enabled() || count(ai_keys_active($p)) > 0;
        }
        if ($has[$p]) {
            $out[] = $m;
        }
    }
    return $out;
}

function model_provider(array $m): string
{
    $p = isset($m['provider']) ? (string)$m['provider'] : 'anthropic';
    return isset(AI_PROVIDERS[$p]) ? $p : 'anthropic';
}

function ai_model_for(string $modelId): ?array
{
    $usable = ai_models_usable();
    if ($modelId !== '') {
        foreach ($usable as $m) {
            if ($m['model_id'] === $modelId) {
                return $m;
            }
        }
    }
    return $usable ? $usable[0] : null;
}

/** مزوّد المفتاح من شكله: Gemini يبدأ بـ AIza و Claude يبدأ بـ sk-ant- */
function detect_key_provider(string $key, string $fallback = 'anthropic'): string
{
    if (strpos($key, 'AIza') === 0) {
        return 'google';
    }
    if (strpos($key, 'sk-ant-') === 0) {
        return 'anthropic';
    }
    return isset(AI_PROVIDERS[$fallback]) ? $fallback : 'anthropic';
}

function ai_keys_active(string $provider = 'anthropic'): array
{
    $keys = array();
    $envNames = $provider === 'google' ? array('GEMINI_API_KEY', 'GOOGLE_API_KEY') : array('ANTHROPIC_API_KEY');
    foreach ($envNames as $en) {
        $env = getenv($en);
        if ($env !== false && trim($env) !== '') {
            $keys[] = array('id' => 0, 'key' => trim($env), 'label' => 'ENV');
            break;
        }
    }
    foreach (q('SELECT * FROM api_keys WHERE is_active = 1 AND provider = ? ORDER BY priority ASC, id ASC', array($provider))->fetchAll() as $r) {
        $k = decrypt_secret((string)$r['key_enc']);
        if ($k !== '') {
            $keys[] = array('id' => (int)$r['id'], 'key' => $k, 'label' => (string)$r['label']);
        }
    }
    return $keys;
}

function ai_mock_enabled(): bool
{
    $v = getenv('MADIX_MOCK_AI');
    return $v === '1';
}

function ai_ready(): bool
{
    if (ai_mock_enabled()) {
        return true;
    }
    return ai_model_for('') !== null;
}

function ai_schema(): array
{
    return array(
        'type' => 'object',
        'properties' => array(
            'is_table' => array('type' => 'boolean'),
            'title' => array('type' => 'string'),
            'columns' => array(
                'type' => 'array',
                'items' => array(
                    'type' => 'object',
                    'properties' => array(
                        'label' => array('type' => 'string'),
                        'kind' => array('type' => 'string', 'enum' => COLUMN_KINDS),
                    ),
                    'required' => array('label', 'kind'),
                    'additionalProperties' => false,
                ),
            ),
            'rows' => array(
                'type' => 'array',
                'items' => array(
                    'type' => 'object',
                    'properties' => array(
                        'cells' => array('type' => 'array', 'items' => array('type' => 'string')),
                        'unclear' => array('type' => 'array', 'items' => array('type' => 'integer')),
                        'y_top' => array('type' => 'integer'),
                        'y_bottom' => array('type' => 'integer'),
                    ),
                    'required' => array('cells', 'unclear', 'y_top', 'y_bottom'),
                    'additionalProperties' => false,
                ),
            ),
            'notes' => array('type' => 'string'),
        ),
        'required' => array('is_table', 'title', 'columns', 'rows', 'notes'),
        'additionalProperties' => false,
    );
}

function ai_system_prompt(): string
{
    return <<<'TXT'
You transcribe photographed lists ("كشوفات") into structured tables. The lists are usually handwritten in Arabic by people in Palestine (often aid, registration or attendance lists) with columns such as serial number, full name, national ID number (رقم الهوية), phone number, family size, area, notes.

Your output feeds a tool that detects duplicate people by ID number and validates ID check digits, so faithful transcription matters more than anything else.

Rules:
- Transcribe exactly what is written. Do not correct spelling, do not translate, do not complete or guess missing parts of names, and never invent rows.
- Write every number with Western digits 0-9 (convert ٠١٢٣٤٥٦٧٨٩ and ۰۱۲۳۴۵۶۷۸۹). ID numbers and phone numbers: digits only, no spaces, dashes or other characters.
- Columns follow the page's right-to-left reading order: the first column is the right-most column on the page. Use the header text written on the page as the label. If there is no header, give a short Arabic label (e.g. "الاسم", "رقم الهوية", "رقم الجوال").
- kind: "serial" for a row-number column, "name" for person names, "national_id" for identity numbers (usually 9 digits), "phone" for phone numbers, "number" for other numeric columns (family size, amounts), "date" for dates, "text" for anything else.
- Every row's "cells" has exactly one string per column, in column order. Use "" for an empty cell.
- "unclear" lists the zero-based column indexes of cells in that row that are hard to read or that you are not confident about (smudged, overwritten, ambiguous digits). Be honest: a person will review these cells.
- "y_top" and "y_bottom" are the vertical pixel bounds of the row in the image (0 = top edge of the image). They are used to show the person a strip of the original photo next to each row.
- Skip the header row, totals, signatures, stamps and decorative text. If a row is completely crossed out, skip it and mention it in "notes".
- If the user message lists the columns already found on earlier pages of the same list, and this page continues that table, use exactly those columns in the same order.
- "title": the list's title if one is written on the page, otherwise "".
- "notes": a short Arabic note for the person reviewing (crossed-out rows, cut-off parts of the photo, very unclear areas). Use "" if there is nothing to note.
- If the image does not contain a list or table at all, return is_table=false with empty columns and rows, and explain briefly in "notes".
TXT;
}

/**
 * يقرأ الصورة ويرجع البيانات المنظمة.
 * @return array{data: array, model: string, input_tokens: int, output_tokens: int, cost: float, ms: int}
 */
function ai_extract(string $imgBytes, string $mime, int $w, int $h, array $hintColumns, array $model): array
{
    $started = microtime(true);
    if (ai_mock_enabled()) {
        $data = ai_mock_data($w, $h, $hintColumns);
        return array('data' => $data, 'model' => 'mock', 'input_tokens' => 0, 'output_tokens' => 0, 'cost' => 0.0, 'ms' => (int)((microtime(true) - $started) * 1000));
    }

    $hint = '';
    if ($hintColumns) {
        $parts = array();
        foreach ($hintColumns as $i => $c) {
            $parts[] = ($i + 1) . '. ' . $c['label'] . ' (' . $c['kind'] . ')';
        }
        $hint = "Columns already found on earlier pages of this same list (use them if this page continues the table):\n" . implode("\n", $parts) . "\n\n";
    }
    $userText = "Image size: {$w}x{$h} pixels.\n\n" . $hint . 'Transcribe this page.';
    if (model_provider($model) === 'google') {
        return gemini_extract($imgBytes, $mime, $w, $h, $userText, $model, $started);
    }

    $body = array(
        'model' => (string)$model['model_id'],
        'max_tokens' => max(2000, min(64000, (int)$model['max_tokens'])),
        'system' => ai_system_prompt(),
        'output_config' => array(
            'format' => array('type' => 'json_schema', 'schema' => ai_schema()),
        ),
        'messages' => array(
            array(
                'role' => 'user',
                'content' => array(
                    array('type' => 'image', 'source' => array('type' => 'base64', 'media_type' => $mime, 'data' => base64_encode($imgBytes))),
                    array('type' => 'text', 'text' => $userText),
                ),
            ),
        ),
    );
    $effort = (string)$model['effort'];
    if (in_array($effort, array('low', 'medium', 'high', 'xhigh', 'max'), true)) {
        $body['output_config']['effort'] = $effort;
    }
    $useFallbacks = (int)$model['use_fallbacks'] === 1;

    $resp = ai_call($body, $useFallbacks);
    $json = $resp['json'];

    $stop = isset($json['stop_reason']) ? (string)$json['stop_reason'] : '';
    if ($stop === 'refusal') {
        throw new ApiError('النموذج رفض قراءة هذه الصورة. جرّب صورة ثانية أو نموذج آخر', 422);
    }

    // نجمع نص الإجابة (بعد آخر تحويل للنموذج البديل إن وجد)
    $texts = array();
    foreach ((array)($json['content'] ?? array()) as $block) {
        $type = isset($block['type']) ? (string)$block['type'] : '';
        if ($type === 'fallback') {
            $texts = array();
        } elseif ($type === 'text' && isset($block['text'])) {
            $texts[] = (string)$block['text'];
        }
    }
    $text = trim(implode('', $texts));
    $data = json_decode($text, true);
    if (!is_array($data)) {
        if ($stop === 'max_tokens') {
            throw new ApiError('الكشف طويل جداً على صفحة وحدة. صوّر الصفحة على جزئين أو زد حد التوكنز من لوحة الإدارة', 422);
        }
        throw new ApiError('ما قدرنا نفهم رد الذكاء الاصطناعي. جرّب مرة ثانية', 502);
    }

    $usage = isset($json['usage']) && is_array($json['usage']) ? $json['usage'] : array();
    $in = (int)($usage['input_tokens'] ?? 0);
    $cacheW = (int)($usage['cache_creation_input_tokens'] ?? 0);
    $cacheR = (int)($usage['cache_read_input_tokens'] ?? 0);
    $out = (int)($usage['output_tokens'] ?? 0);
    $servedModel = isset($json['model']) ? (string)$json['model'] : (string)$model['model_id'];
    $priceRow = q_one('SELECT price_in, price_out FROM ai_models WHERE model_id = ?', array($servedModel));
    $pIn = $priceRow ? (float)$priceRow['price_in'] : (float)$model['price_in'];
    $pOut = $priceRow ? (float)$priceRow['price_out'] : (float)$model['price_out'];
    $cost = (($in + $cacheW * 1.25 + $cacheR * 0.1) * $pIn + $out * $pOut) / 1000000;

    return array(
        'data' => ai_clean($data, $w, $h),
        'model' => $servedModel,
        'input_tokens' => $in + $cacheW + $cacheR,
        'output_tokens' => $out,
        'cost' => round($cost, 6),
        'ms' => (int)((microtime(true) - $started) * 1000),
    );
}

/** طلب HTTP للـ API مع تجربة المفاتيح بالترتيب وإعادة المحاولة عند الضغط */
function ai_call(array $body, bool $useFallbacks): array
{
    if (!function_exists('curl_init')) {
        throw new ApiError('امتداد cURL غير مفعّل على السيرفر', 500);
    }
    $keys = ai_keys_active('anthropic');
    if (!$keys) {
        throw new ApiError('الخدمة غير مفعّلة بعد — المدير لازم يضيف مفتاح API من لوحة الإدارة', 503);
    }

    $lastMessage = 'تعذّر الاتصال بخدمة الذكاء الاصطناعي';
    $started = time();
    $met = (int)ini_get('max_execution_time');
    $budget = $met > 0 ? max(25, $met - 6) : 280;
    foreach ($keys as $key) {
        $withFallbacks = $useFallbacks;
        for ($attempt = 1; $attempt <= 3; $attempt++) {
            $payload = $body;
            $headers = array(
                'Content-Type: application/json',
                'x-api-key: ' . $key['key'],
                'anthropic-version: ' . ANTHROPIC_VERSION,
            );
            if ($withFallbacks) {
                $payload['fallbacks'] = 'default';
                $headers[] = 'anthropic-beta: ' . FALLBACK_BETA;
            }
            $left = $budget - (time() - $started);
            if ($left < 15) {
                break 2;
            }
            $r = http_post_json(api_url(), $payload, $headers, min(240, $left));

            if ($r['error'] !== '') {
                $lastMessage = 'انقطع الاتصال بخدمة الذكاء الاصطناعي، حاول مرة ثانية';
                ai_key_mark($key['id'], false, 'network: ' . $r['error']);
                if ($attempt < 2) {
                    sleep(2);
                    continue;
                }
                break;
            }
            $status = $r['status'];
            $json = json_decode($r['body'], true);
            $json = is_array($json) ? $json : array();
            $errMsg = isset($json['error']['message']) ? (string)$json['error']['message'] : '';

            if ($status === 200) {
                ai_key_mark($key['id'], true, '');
                return array('json' => $json);
            }
            if ($status === 400 && $withFallbacks && stripos($errMsg, 'fallback') !== false) {
                // الحساب أو النموذج لا يدعم ميزة النموذج البديل — نعيد بدونها
                $withFallbacks = false;
                $attempt--;
                continue;
            }
            if ($status === 401 || $status === 403) {
                ai_key_mark($key['id'], false, $status . ': ' . $errMsg);
                $lastMessage = 'مفتاح API غير صالح أو موقوف — راجع لوحة الإدارة';
                break; // جرّب المفتاح التالي
            }
            if ($status === 404) {
                ai_key_mark($key['id'], false, $status . ': ' . $errMsg);
                throw new ApiError('النموذج المختار غير متاح على حسابك (' . (string)$body['model'] . '). غيّره من لوحة الإدارة', 502);
            }
            if ($status === 402 || (($status === 400 || $status === 403) && (stripos($errMsg, 'credit') !== false || stripos($errMsg, 'billing') !== false))) {
                ai_key_mark($key['id'], false, $status . ': ' . $errMsg);
                $lastMessage = 'رصيد حساب الذكاء الاصطناعي خلص — تواصل مع المدير';
                continue 2; // جرّب المفتاح التالي
            }
            if ($status === 400 || $status === 413) {
                ai_key_mark($key['id'], false, $status . ': ' . $errMsg);
                if ($status === 413 || stripos($errMsg, 'image') !== false) {
                    throw new ApiError('الصورة غير مقبولة من الخدمة. جرّب صورة أوضح أو أصغر', 422);
                }
                if (stripos($errMsg, 'model') !== false) {
                    throw new ApiError('إعدادات النموذج (' . (string)$body['model'] . ') غير مقبولة. راجع لوحة الإدارة', 502);
                }
                throw new ApiError('الخدمة رفضت الطلب — التفاصيل محفوظة في لوحة الإدارة (مفاتيح API)', 502);
            }
            if ($status === 429 || $status >= 500) {
                ai_key_mark($key['id'], false, $status . ': ' . $errMsg);
                $lastMessage = 'الخدمة مضغوطة حالياً، حاول بعد شوي';
                if ($attempt < 3) {
                    $wait = isset($r['headers']['retry-after']) ? (int)$r['headers']['retry-after'] : 0;
                    sleep(max(2, min(8, $wait > 0 ? $wait : $attempt * 3)));
                    continue;
                }
                break; // جرّب المفتاح التالي
            }
            ai_key_mark($key['id'], false, $status . ': ' . $errMsg);
            $lastMessage = 'خطأ من خدمة الذكاء الاصطناعي (' . $status . ')';
            break;
        }
    }
    throw new ApiError($lastMessage, 502);
}

function api_url(): string
{
    $u = getenv('MADIX_API_URL');
    return ($u !== false && $u !== '') ? $u : ANTHROPIC_URL;
}

function ai_key_mark(int $id, bool $ok, string $error): void
{
    if ($id <= 0) {
        return;
    }
    try {
        if ($ok) {
            q('UPDATE api_keys SET ok_count = ok_count + 1, last_used_at = ? WHERE id = ?', array(now_utc(), $id));
        } else {
            q('UPDATE api_keys SET fail_count = fail_count + 1, last_used_at = ?, last_error = ?, last_error_at = ? WHERE id = ?', array(now_utc(), str_cut($error, 300), now_utc(), $id));
        }
    } catch (Throwable $e) {
        log_error($e);
    }
}

/** @return array{status:int, body:string, error:string, headers:array} */
function http_post_json(string $url, array $payload, array $headers, int $timeout): array
{
    $respHeaders = array();
    $ch = curl_init($url);
    curl_setopt_array($ch, array(
        CURLOPT_POST => true,
        CURLOPT_POSTFIELDS => json_encode($payload, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES),
        CURLOPT_HTTPHEADER => $headers,
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_CONNECTTIMEOUT => 20,
        CURLOPT_TIMEOUT => $timeout,
        CURLOPT_SSL_VERIFYPEER => true,
        CURLOPT_HEADERFUNCTION => function ($ch, $line) use (&$respHeaders) {
            $p = strpos($line, ':');
            if ($p !== false) {
                $respHeaders[strtolower(trim(substr($line, 0, $p)))] = trim(substr($line, $p + 1));
            }
            return strlen($line);
        },
    ));
    $body = curl_exec($ch);
    $err = $body === false ? curl_error($ch) : '';
    $status = (int)curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);
    return array('status' => $status, 'body' => $body === false ? '' : (string)$body, 'error' => $err, 'headers' => $respHeaders);
}

/** تنظيف وتوحيد مخرجات النموذج حتى لا يصل للمستخدم أي شيء غير متوقع */
function ai_clean(array $data, int $w, int $h): array
{
    $columns = array();
    foreach ((array)($data['columns'] ?? array()) as $c) {
        if (!is_array($c)) {
            continue;
        }
        $kind = isset($c['kind']) && in_array($c['kind'], COLUMN_KINDS, true) ? (string)$c['kind'] : 'text';
        $label = trim(str_cut((string)($c['label'] ?? ''), 60));
        $columns[] = array('label' => $label === '' ? 'عمود ' . (count($columns) + 1) : $label, 'kind' => $kind);
        if (count($columns) >= 30) {
            break;
        }
    }
    $n = count($columns);
    $rows = array();
    foreach ((array)($data['rows'] ?? array()) as $r) {
        if (!is_array($r) || $n === 0) {
            continue;
        }
        $cells = array();
        $raw = array_values((array)($r['cells'] ?? array()));
        for ($i = 0; $i < $n; $i++) {
            $v = isset($raw[$i]) && is_scalar($raw[$i]) ? trim((string)$raw[$i]) : '';
            $v = preg_replace('/[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]/u', '', $v);
            $v = $v === null ? '' : $v;
            $kind = $columns[$i]['kind'];
            if ($kind === 'national_id' || $kind === 'phone') {
                $v = (string)preg_replace('/\D+/', '', to_latin_digits($v));
            } elseif ($kind === 'number' || $kind === 'serial') {
                $v = to_latin_digits($v);
            }
            $cells[] = str_cut($v, 300);
        }
        if (implode('', $cells) === '') {
            continue;
        }
        $unclear = array();
        foreach ((array)($r['unclear'] ?? array()) as $u) {
            if (is_int($u) && $u >= 0 && $u < $n) {
                $unclear[$u] = true;
            }
        }
        $yt = isset($r['y_top']) ? (int)$r['y_top'] : 0;
        $yb = isset($r['y_bottom']) ? (int)$r['y_bottom'] : 0;
        $yt = max(0, min($h, $yt));
        $yb = max(0, min($h, $yb));
        if ($yb <= $yt) {
            $yt = 0;
            $yb = 0;
        }
        $rows[] = array('cells' => $cells, 'unclear' => array_keys($unclear), 'y_top' => $yt, 'y_bottom' => $yb);
        if (count($rows) >= 500) {
            break;
        }
    }
    return array(
        'is_table' => !empty($data['is_table']) && $n > 0,
        'title' => trim(str_cut((string)($data['title'] ?? ''), 120)),
        'columns' => $columns,
        'rows' => $rows,
        'notes' => trim(str_cut((string)($data['notes'] ?? ''), 500)),
    );
}

/** بيانات تجريبية (فقط عند تفعيل MADIX_MOCK_AI=1 على السيرفر للتجربة بدون مفتاح) */
function ai_mock_data(int $w, int $h, array $hintColumns): array
{
    $names = array('محمد أحمد عبد الله', 'أحمد محمود سالم', 'فاطمة علي حسن', 'محمد احمد عبدالله', 'خالد يوسف النجار', 'مريم سعيد عودة', 'يوسف إبراهيم شعت', 'خالد يوسف النجار', 'عمر حسين الزعانين', 'أحمد محمود سالم');
    $ids = array('400000006', '802456715', '901234567', '400000006', '123456782', '987654324', '410234565', '803456789', '911122232', '802456715');
    $phones = array('0599123456', '0597654321', '0592223334', '0599123456', '0566778899', '0598887776', '0595554443', '0594443332', '0593332221', '0591112223');
    $rows = array();
    $count = count($names);
    $top = (int)($h * 0.12);
    $step = (int)(($h * 0.8) / $count);
    for ($i = 0; $i < $count; $i++) {
        $rows[] = array(
            'cells' => array((string)($i + 1), $names[$i], $ids[$i], $phones[$i], (string)(($i * 3) % 8 + 1)),
            'unclear' => $i === 6 ? array(2) : array(),
            'y_top' => $top + $i * $step,
            'y_bottom' => $top + ($i + 1) * $step,
        );
    }
    return array(
        'is_table' => true,
        'title' => 'كشف تجريبي',
        'columns' => array(
            array('label' => 'م', 'kind' => 'serial'),
            array('label' => 'الاسم', 'kind' => 'name'),
            array('label' => 'رقم الهوية', 'kind' => 'national_id'),
            array('label' => 'رقم الجوال', 'kind' => 'phone'),
            array('label' => 'عدد الأفراد', 'kind' => 'number'),
        ),
        'rows' => $rows,
        'notes' => 'هذه بيانات تجريبية (وضع التجربة مفعّل على السيرفر).',
    );
}

/** اختبار سريع لمفتاح ونموذج (للوحة الإدارة) */
function ai_test(string $apiKey, string $modelId, string $provider = 'anthropic'): array
{
    if ($provider === 'google') {
        return gemini_test($apiKey, $modelId);
    }
    $payload = array(
        'model' => $modelId,
        'max_tokens' => 64,
        'output_config' => array('effort' => 'low'),
        'messages' => array(array('role' => 'user', 'content' => 'Reply with the single word: OK')),
    );
    $started = microtime(true);
    $r = http_post_json(api_url(), $payload, array(
        'Content-Type: application/json',
        'x-api-key: ' . $apiKey,
        'anthropic-version: ' . ANTHROPIC_VERSION,
    ), 60);
    $ms = (int)((microtime(true) - $started) * 1000);
    if ($r['error'] !== '') {
        return array('ok' => false, 'message' => 'تعذّر الاتصال: ' . $r['error'], 'ms' => $ms);
    }
    $json = json_decode($r['body'], true);
    if ($r['status'] === 200) {
        return array('ok' => true, 'message' => 'الاتصال ناجح ✓ النموذج ' . (is_array($json) && isset($json['model']) ? $json['model'] : $modelId) . ' جاهز', 'ms' => $ms);
    }
    $msg = is_array($json) && isset($json['error']['message']) ? (string)$json['error']['message'] : ('HTTP ' . $r['status']);
    $hint = '';
    if ($r['status'] === 401) {
        $hint = 'المفتاح غير صحيح. ';
    } elseif ($r['status'] === 404) {
        $hint = 'اسم النموذج غير موجود أو غير متاح لحسابك. ';
    } elseif ($r['status'] === 429) {
        $hint = 'تجاوزت حد الطلبات. ';
    }
    return array('ok' => false, 'message' => $hint . 'رد الخدمة (' . $r['status'] . '): ' . str_cut($msg, 300), 'ms' => $ms);
}

// ================================================================
// Google Gemini
// ================================================================

function gemini_url(string $modelId): string
{
    $base = getenv('MADIX_GEMINI_URL');
    $base = ($base !== false && $base !== '') ? rtrim($base, '/') : 'https://generativelanguage.googleapis.com/v1beta';
    return $base . '/models/' . rawurlencode($modelId) . ':generateContent';
}

/** نفس شكل الجدول بصيغة Gemini (OpenAPI) */
function gemini_schema(): array
{
    return array(
        'type' => 'OBJECT',
        'properties' => array(
            'is_table' => array('type' => 'BOOLEAN'),
            'title' => array('type' => 'STRING'),
            'columns' => array('type' => 'ARRAY', 'items' => array(
                'type' => 'OBJECT',
                'properties' => array(
                    'label' => array('type' => 'STRING'),
                    'kind' => array('type' => 'STRING', 'enum' => COLUMN_KINDS),
                ),
                'required' => array('label', 'kind'),
            )),
            'rows' => array('type' => 'ARRAY', 'items' => array(
                'type' => 'OBJECT',
                'properties' => array(
                    'cells' => array('type' => 'ARRAY', 'items' => array('type' => 'STRING')),
                    'unclear' => array('type' => 'ARRAY', 'items' => array('type' => 'INTEGER')),
                    'y_top' => array('type' => 'INTEGER'),
                    'y_bottom' => array('type' => 'INTEGER'),
                ),
                'required' => array('cells', 'unclear', 'y_top', 'y_bottom'),
            )),
            'notes' => array('type' => 'STRING'),
        ),
        'required' => array('is_table', 'title', 'columns', 'rows', 'notes'),
        'propertyOrdering' => array('is_table', 'title', 'columns', 'rows', 'notes'),
    );
}

function gemini_extract(string $imgBytes, string $mime, int $w, int $h, string $userText, array $model, float $started): array
{
    $body = array(
        'systemInstruction' => array('parts' => array(array('text' => ai_system_prompt()))),
        'contents' => array(array(
            'role' => 'user',
            'parts' => array(
                array('inlineData' => array('mimeType' => $mime, 'data' => base64_encode($imgBytes))),
                array('text' => $userText),
            ),
        )),
        'generationConfig' => array(
            'responseMimeType' => 'application/json',
            'responseSchema' => gemini_schema(),
            'maxOutputTokens' => max(2000, min(64000, (int)$model['max_tokens'])),
        ),
    );
    $effort = (string)$model['effort'];
    if (in_array($effort, array('low', 'medium', 'high'), true)) {
        $body['generationConfig']['thinkingConfig'] = array('thinkingLevel' => $effort === 'medium' ? 'low' : $effort);
    }
    $json = gemini_call($body, (string)$model['model_id']);

    if (isset($json['promptFeedback']['blockReason'])) {
        throw new ApiError('النموذج رفض قراءة هذه الصورة. جرّب صورة ثانية أو نموذج آخر', 422);
    }
    $cand = isset($json['candidates'][0]) ? $json['candidates'][0] : array();
    $finish = isset($cand['finishReason']) ? (string)$cand['finishReason'] : '';
    $text = '';
    foreach ((array)($cand['content']['parts'] ?? array()) as $part) {
        if (isset($part['text']) && empty($part['thought'])) {
            $text .= (string)$part['text'];
        }
    }
    $text = trim($text);
    if (strpos($text, '```') === 0) {
        $text = trim(preg_replace('/^```(?:json)?\s*|\s*```$/', '', $text));
    }
    $data = json_decode($text, true);
    if (!is_array($data)) {
        if ($finish === 'MAX_TOKENS') {
            throw new ApiError('الكشف طويل جداً على صفحة وحدة. صوّر الصفحة على جزئين أو زد حد التوكنز من لوحة الإدارة', 422);
        }
        if (in_array($finish, array('SAFETY', 'PROHIBITED_CONTENT', 'BLOCKLIST', 'SPII', 'RECITATION'), true)) {
            throw new ApiError('النموذج رفض قراءة هذه الصورة. جرّب صورة ثانية أو نموذج آخر', 422);
        }
        throw new ApiError('ما قدرنا نفهم رد الذكاء الاصطناعي. جرّب مرة ثانية', 502);
    }
    $u = isset($json['usageMetadata']) && is_array($json['usageMetadata']) ? $json['usageMetadata'] : array();
    $in = (int)($u['promptTokenCount'] ?? 0);
    $out = (int)($u['candidatesTokenCount'] ?? 0) + (int)($u['thoughtsTokenCount'] ?? 0);
    $cost = ($in * (float)$model['price_in'] + $out * (float)$model['price_out']) / 1000000;
    return array(
        'data' => ai_clean($data, $w, $h),
        'model' => isset($json['modelVersion']) ? (string)$json['modelVersion'] : (string)$model['model_id'],
        'input_tokens' => $in,
        'output_tokens' => $out,
        'cost' => round($cost, 6),
        'ms' => (int)((microtime(true) - $started) * 1000),
    );
}

/** طلب Gemini مع تجربة المفاتيح بالترتيب وإعادة المحاولة */
function gemini_call(array $body, string $modelId): array
{
    if (!function_exists('curl_init')) {
        throw new ApiError('امتداد cURL غير مفعّل على السيرفر', 500);
    }
    $keys = ai_keys_active('google');
    if (!$keys) {
        throw new ApiError('الخدمة غير مفعّلة بعد — المدير لازم يضيف مفتاح API من لوحة الإدارة', 503);
    }
    $lastMessage = 'تعذّر الاتصال بخدمة الذكاء الاصطناعي';
    $started = time();
    $met = (int)ini_get('max_execution_time');
    $budget = $met > 0 ? max(25, $met - 6) : 280;
    foreach ($keys as $key) {
        $payload = $body;
        for ($attempt = 1; $attempt <= 3; $attempt++) {
            $left = $budget - (time() - $started);
            if ($left < 15) {
                break 2;
            }
            $r = http_post_json(gemini_url($modelId), $payload, array('Content-Type: application/json', 'x-goog-api-key: ' . $key['key']), min(240, $left));
            if ($r['error'] !== '') {
                $lastMessage = 'انقطع الاتصال بخدمة الذكاء الاصطناعي، حاول مرة ثانية';
                ai_key_mark($key['id'], false, 'network: ' . $r['error']);
                if ($attempt < 2) {
                    sleep(2);
                    continue;
                }
                break;
            }
            $status = $r['status'];
            $json = json_decode($r['body'], true);
            $json = is_array($json) ? $json : array();
            $errMsg = isset($json['error']['message']) ? (string)$json['error']['message'] : '';
            $errStatus = isset($json['error']['status']) ? (string)$json['error']['status'] : '';
            if ($status === 200) {
                ai_key_mark($key['id'], true, '');
                return $json;
            }
            ai_key_mark($key['id'], false, $status . ' ' . $errStatus . ': ' . $errMsg);
            if ($status === 400 && isset($payload['generationConfig']['thinkingConfig']) && stripos($errMsg, 'thinking') !== false) {
                unset($payload['generationConfig']['thinkingConfig']);
                $attempt--;
                continue;
            }
            if (stripos($errMsg, 'API key') !== false || $status === 401 || $status === 403) {
                $lastMessage = 'مفتاح API غير صالح أو موقوف — راجع لوحة الإدارة';
                break;
            }
            if ($status === 404) {
                throw new ApiError('النموذج المختار غير متاح (' . $modelId . '). غيّره من لوحة الإدارة', 502);
            }
            if ($status === 429 || $errStatus === 'RESOURCE_EXHAUSTED') {
                $lastMessage = 'تم تجاوز حد الاستخدام لمفتاح Gemini، حاول بعد شوي';
                if ($attempt < 2) {
                    sleep(4);
                    continue;
                }
                break;
            }
            if ($status >= 500) {
                $lastMessage = 'الخدمة مضغوطة حالياً، حاول بعد شوي';
                if ($attempt < 3) {
                    sleep(min(8, $attempt * 3));
                    continue;
                }
                break;
            }
            if ($status === 400 && (stripos($errMsg, 'image') !== false || stripos($errMsg, 'inline') !== false)) {
                throw new ApiError('الصورة غير مقبولة من الخدمة. جرّب صورة أوضح أو أصغر', 422);
            }
            if ($status === 400 && stripos($errMsg, 'location') !== false) {
                throw new ApiError('خدمة Gemini غير متاحة من موقع السيرفر', 502);
            }
            throw new ApiError('الخدمة رفضت الطلب — التفاصيل محفوظة في لوحة الإدارة (مفاتيح API)', 502);
        }
    }
    throw new ApiError($lastMessage, 502);
}

function gemini_test(string $apiKey, string $modelId): array
{
    $started = microtime(true);
    $r = http_post_json(gemini_url($modelId), array(
        'contents' => array(array('role' => 'user', 'parts' => array(array('text' => 'Reply with the single word: OK')))),
        'generationConfig' => array('maxOutputTokens' => 256),
    ), array('Content-Type: application/json', 'x-goog-api-key: ' . $apiKey), 60);
    $ms = (int)((microtime(true) - $started) * 1000);
    if ($r['error'] !== '') {
        return array('ok' => false, 'message' => 'تعذّر الاتصال: ' . $r['error'], 'ms' => $ms);
    }
    $json = json_decode($r['body'], true);
    if ($r['status'] === 200) {
        $mv = is_array($json) && isset($json['modelVersion']) ? $json['modelVersion'] : $modelId;
        return array('ok' => true, 'message' => 'الاتصال ناجح ✓ النموذج ' . $mv . ' جاهز', 'ms' => $ms);
    }
    $msg = is_array($json) && isset($json['error']['message']) ? (string)$json['error']['message'] : ('HTTP ' . $r['status']);
    $hint = '';
    if (stripos($msg, 'API key') !== false) {
        $hint = 'المفتاح غير صحيح. ';
    } elseif ($r['status'] === 404) {
        $hint = 'اسم النموذج غير موجود. ';
    } elseif ($r['status'] === 429) {
        $hint = 'تجاوزت حد الطلبات. ';
    }
    return array('ok' => false, 'message' => $hint . 'رد الخدمة (' . $r['status'] . '): ' . str_cut($msg, 300), 'ms' => $ms);
}
