<?php
/**
 * إنشاء الجداول وتحديثها تلقائياً
 * Py | Sohaib
 */
declare(strict_types=1);

if (!defined('MADIX')) {
    http_response_code(403);
    exit;
}

const SCHEMA_VERSION = 2;

function migrate(PDO $pdo): void
{
    $pdo->exec('CREATE TABLE IF NOT EXISTS settings (
        k TEXT PRIMARY KEY,
        v TEXT NOT NULL DEFAULT \'\',
        updated_at TEXT
    )');

    $ver = 0;
    try {
        $v = $pdo->query("SELECT v FROM settings WHERE k = 'schema_version'")->fetchColumn();
        $ver = $v === false ? 0 : (int)$v;
    } catch (Throwable $e) {
        $ver = 0;
    }
    if ($ver >= SCHEMA_VERSION) {
        return;
    }

    $pdo->beginTransaction();
    try {
        $pdo->exec('CREATE TABLE IF NOT EXISTS users (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            phone TEXT NOT NULL UNIQUE,
            password_hash TEXT NOT NULL,
            role TEXT NOT NULL DEFAULT \'user\' CHECK (role IN (\'admin\', \'user\')),
            pages INTEGER NOT NULL DEFAULT 0,
            pages_used INTEGER NOT NULL DEFAULT 0,
            is_blocked INTEGER NOT NULL DEFAULT 0,
            session_v INTEGER NOT NULL DEFAULT 0,
            note TEXT NOT NULL DEFAULT \'\',
            created_at TEXT NOT NULL,
            last_login_at TEXT
        )');

        $pdo->exec('CREATE TABLE IF NOT EXISTS codes (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            code TEXT NOT NULL UNIQUE,
            pages INTEGER NOT NULL,
            price REAL NOT NULL DEFAULT 0,
            batch TEXT NOT NULL DEFAULT \'\',
            status TEXT NOT NULL DEFAULT \'new\' CHECK (status IN (\'new\', \'used\', \'disabled\')),
            created_by INTEGER,
            created_at TEXT NOT NULL,
            used_by INTEGER,
            used_at TEXT
        )');
        $pdo->exec('CREATE INDEX IF NOT EXISTS idx_codes_batch ON codes (batch)');
        $pdo->exec('CREATE INDEX IF NOT EXISTS idx_codes_status ON codes (status)');

        $pdo->exec('CREATE TABLE IF NOT EXISTS ledger (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id INTEGER NOT NULL,
            delta INTEGER NOT NULL,
            balance_after INTEGER NOT NULL,
            kind TEXT NOT NULL,
            ref TEXT NOT NULL DEFAULT \'\',
            note TEXT NOT NULL DEFAULT \'\',
            created_at TEXT NOT NULL
        )');
        $pdo->exec('CREATE INDEX IF NOT EXISTS idx_ledger_user ON ledger (user_id, id)');

        $pdo->exec('CREATE TABLE IF NOT EXISTS scans (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id INTEGER NOT NULL,
            img_hash TEXT NOT NULL,
            model TEXT NOT NULL DEFAULT \'\',
            status TEXT NOT NULL,
            rows_count INTEGER NOT NULL DEFAULT 0,
            charged INTEGER NOT NULL DEFAULT 0,
            input_tokens INTEGER NOT NULL DEFAULT 0,
            output_tokens INTEGER NOT NULL DEFAULT 0,
            cost_usd REAL NOT NULL DEFAULT 0,
            ms INTEGER NOT NULL DEFAULT 0,
            error TEXT NOT NULL DEFAULT \'\',
            created_at TEXT NOT NULL
        )');
        $pdo->exec('CREATE INDEX IF NOT EXISTS idx_scans_user_hash ON scans (user_id, img_hash)');
        $pdo->exec('CREATE INDEX IF NOT EXISTS idx_scans_created ON scans (created_at)');

        $pdo->exec('CREATE TABLE IF NOT EXISTS ai_models (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            model_id TEXT NOT NULL UNIQUE,
            label TEXT NOT NULL,
            description TEXT NOT NULL DEFAULT \'\',
            price_in REAL NOT NULL DEFAULT 0,
            price_out REAL NOT NULL DEFAULT 0,
            effort TEXT NOT NULL DEFAULT \'medium\',
            max_tokens INTEGER NOT NULL DEFAULT 16000,
            use_fallbacks INTEGER NOT NULL DEFAULT 1,
            page_cost INTEGER NOT NULL DEFAULT 1,
            is_active INTEGER NOT NULL DEFAULT 1,
            is_default INTEGER NOT NULL DEFAULT 0,
            sort INTEGER NOT NULL DEFAULT 0,
            created_at TEXT NOT NULL
        )');

        $pdo->exec('CREATE TABLE IF NOT EXISTS api_keys (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            label TEXT NOT NULL DEFAULT \'\',
            key_enc TEXT NOT NULL,
            key_hint TEXT NOT NULL DEFAULT \'\',
            is_active INTEGER NOT NULL DEFAULT 1,
            priority INTEGER NOT NULL DEFAULT 0,
            ok_count INTEGER NOT NULL DEFAULT 0,
            fail_count INTEGER NOT NULL DEFAULT 0,
            last_used_at TEXT,
            last_error TEXT NOT NULL DEFAULT \'\',
            last_error_at TEXT,
            created_at TEXT NOT NULL
        )');

        $pdo->exec('CREATE TABLE IF NOT EXISTS audit_log (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id INTEGER,
            action TEXT NOT NULL,
            details TEXT NOT NULL DEFAULT \'\',
            ip TEXT NOT NULL DEFAULT \'\',
            created_at TEXT NOT NULL
        )');

        $pdo->exec('CREATE TABLE IF NOT EXISTS rate_hits (
            k TEXT NOT NULL,
            t INTEGER NOT NULL
        )');
        $pdo->exec('CREATE INDEX IF NOT EXISTS idx_rate_hits ON rate_hits (k, t)');

        $pdo->exec('CREATE TABLE IF NOT EXISTS tokens (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id INTEGER NOT NULL,
            token_hash TEXT NOT NULL UNIQUE,
            sv INTEGER NOT NULL DEFAULT 0,
            device TEXT NOT NULL DEFAULT \'\',
            created_at TEXT NOT NULL,
            last_used_at TEXT
        )');
        $pdo->exec('CREATE INDEX IF NOT EXISTS idx_tokens_user ON tokens (user_id)');

        if ($ver === 0) {
            seed_defaults($pdo);
        }
        $st2 = $pdo->prepare('INSERT OR IGNORE INTO settings (k, v, updated_at) VALUES (?, ?, ?)');
        foreach (array('web_app' => '0', 'apk_version' => '') as $k2 => $v2) {
            $st2->execute(array($k2, $v2, gmdate('Y-m-d H:i:s')));
        }

        $st = $pdo->prepare('INSERT OR REPLACE INTO settings (k, v, updated_at) VALUES (?, ?, ?)');
        $st->execute(array('schema_version', (string)SCHEMA_VERSION, gmdate('Y-m-d H:i:s')));
        $pdo->commit();
    } catch (Throwable $e) {
        if ($pdo->inTransaction()) {
            $pdo->rollBack();
        }
        throw $e;
    }
}

function seed_defaults(PDO $pdo): void
{
    $now = gmdate('Y-m-d H:i:s');
    $defaults = array(
        'app_name' => 'MADIX Convert',
        'tagline' => 'حوّل الكشوفات المكتوبة بخط اليد لجدول إكسل خلال ثواني',
        'footer_text' => 'MADIX Convert — محوّل الكشوفات الذكي',
        'contact_phone' => '',
        'currency' => '₪',
        'prices' => "10 صفحات — 10 ₪\n50 صفحة — 40 ₪\n200 صفحة — 140 ₪",
        'signup_pages' => '2',
        'allow_register' => '1',
        'free_retries' => '2',
        'scan_rate_limit' => '40',
        'max_image_mb' => '6',
        'announcement' => '',
        'maintenance' => '0',
        'icon_version' => '0',
    );
    $st = $pdo->prepare('INSERT OR IGNORE INTO settings (k, v, updated_at) VALUES (?, ?, ?)');
    foreach ($defaults as $k => $v) {
        $st->execute(array($k, $v, $now));
    }

    $models = array(
        array('claude-opus-5-5', 'دقة عالية', 'أقوى نموذج لقراءة خط اليد الصعب — الخيار المنصوح فيه', 4.0, 20.0, 'medium', 1, 1, 1, 1),
        array('claude-sonnet-5-5', 'متوازن', 'سريع ودقيق للكشوفات الواضحة', 2.0, 10.0, 'medium', 1, 1, 0, 2),
        array('claude-haiku-5-5', 'اقتصادي', 'الأسرع والأرخص — للخط المرتب والمطبوع', 0.10, 0.50, 'medium', 0, 0, 0, 3),
    );
    $ms = $pdo->prepare('INSERT OR IGNORE INTO ai_models (model_id, label, description, price_in, price_out, effort, use_fallbacks, is_active, is_default, sort, max_tokens, page_cost, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 16000, 1, ?)');
    foreach ($models as $m) {
        $m[] = $now;
        $ms->execute($m);
    }
}
