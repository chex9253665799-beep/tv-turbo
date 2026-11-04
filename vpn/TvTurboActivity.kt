package com.v2ray.ang.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil

/**
 * Управление из «ТВ Турбо» (добавлено патчем ТВ Турбо).
 *
 * TvTurboActivity (открытая): extra "action" = "start" | "stop" | "apply".
 *   "apply" — снова включить режим «только выбранные приложения» (на случай, если его выключили вручную).
 *
 * TvTurboConfigActivity (только для приложений с тем же ключом подписи — т.е. для ТВ Турбо):
 *   extra "link"  — ссылка vless:// vmess:// trojan:// ss:// hy2:// или ссылка подписки https://…
 *   extra "apps"  — пакеты, которые идут через VPN (остальные — напрямую)
 *   extra "start" — сразу подключиться
 *   Результат: RESULT_OK + extra "count" (сколько серверов загружено) либо RESULT_CANCELED + "error".
 * Ссылка хранится только в настройках этого приложения на телевизоре.
 */
object TvTurbo {
    val DEFAULT_APPS = listOf(
        "com.google.android.youtube.tv",
        "com.google.android.youtube.tvkids",
        "com.google.android.youtube.tvmusic",
        "com.google.android.youtube",
        "com.google.android.apps.youtube.kids",
        "com.google.android.apps.youtube.music"
    )

    /** Только выбранные приложения идут через VPN, остальные — напрямую. */
    fun applyPerApp(apps: Collection<String>?) {
        val set = (apps?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() } ?: DEFAULT_APPS).toMutableSet()
        MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY, true)
        MmkvManager.encodeSettings(AppConfig.PREF_BYPASS_APPS, false)
        MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY_SET, set)
        MmkvManager.encodeSettings(AppConfig.PREF_DYNAMIC_SOCKS_PORT, false)
        MmkvManager.encodeSettings(AppConfig.PREF_ENABLE_LOCAL_PROXY, true)   // 127.0.0.1:10808 — ТВ Турбо проверяет через него YouTube
        MmkvManager.encodeStartOnBoot(true)
    }

    fun selectFirstServer(): Boolean {
        val all = MmkvManager.decodeAllServerList()
        val cur = MmkvManager.getSelectServer()
        if (!cur.isNullOrEmpty() && all.contains(cur)) return true
        val first = all.firstOrNull() ?: return false
        MmkvManager.setSelectServer(first)
        return true
    }
}

class TvTurboActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            when (intent?.getStringExtra("action")) {
                "start" -> {
                    TvTurbo.applyPerApp(MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET))
                    if (TvTurbo.selectFirstServer()) LauncherManager.startService(this)
                }
                "stop" -> LauncherManager.stopService(this)
                "apply" -> TvTurbo.applyPerApp(MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET))
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "TvTurbo control", e)
        }
        finish()
    }
}

class TvTurboConfigActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = intent?.getStringExtra("link")?.trim().orEmpty()
        val apps = intent?.getStringArrayExtra("apps")?.toList()
        val start = intent?.getBooleanExtra("start", true) ?: true
        Thread {
            var count = 0
            var error: String? = null
            try {
                TvTurbo.applyPerApp(apps)
                if (link.isNotEmpty()) {
                    // старые серверы и подписки убираем — остаётся только новая ссылка
                    LauncherManager.stopService(this)
                    MmkvManager.decodeSubscriptions().forEach {
                        if (it.subscription.url.isNotEmpty()) MmkvManager.removeSubscription(it.guid)
                    }
                    MmkvManager.removeAllServer()
                    val (c, cs) = AngConfigManager.importBatchConfig(link, "", false)
                    count = MmkvManager.decodeAllServerList().size
                    if (c + cs <= 0 || count == 0) error = "ссылка не распознана"
                }
                if (error == null && TvTurbo.selectFirstServer() && start) {
                    Thread.sleep(1500)
                    runOnUiThread { LauncherManager.startService(this) }
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "TvTurbo config", e)
                error = e.message ?: e.javaClass.simpleName
            }
            runOnUiThread {
                val data = Intent().putExtra("count", count)
                if (error != null) {
                    data.putExtra("error", error)
                    setResult(RESULT_CANCELED, data)
                } else setResult(RESULT_OK, data)
                finish()
            }
        }.start()
    }
}
