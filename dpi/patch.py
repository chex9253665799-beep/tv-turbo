"""
Патч исходников ByeByeDPI → «ТВ Турбо Обход».
Единственная суть изменений: с первого запуска обход работает ТОЛЬКО для YouTube
(белый список приложений), все остальные приложения идут в интернет напрямую и VPN не видят.
Плюс своё имя пакета, чтобы не конфликтовать с официальным ByeByeDPI.
Запуск: python3 patch.py <папка с исходниками ByeByeDPI>
"""
import pathlib
import re
import sys

root = pathlib.Path(sys.argv[1])
APP_ID = "ru.chex.tvturbo.dpi"
NAME = "ТВ Турбо Обход"
# приложения, которые идут через обход
APPS = [
    "com.google.android.youtube.tv",       # YouTube для Android TV
    "com.google.android.youtube.tvkids",   # YouTube Kids для ТВ
    "com.google.android.youtube.tvmusic",  # YouTube Music для ТВ
    "com.google.android.youtube",          # обычный YouTube (на приставках)
    "com.google.android.apps.youtube.kids",
    "com.google.android.apps.youtube.music",
]


def edit(rel, fn):
    p = root / rel
    s = p.read_text(encoding="utf-8")
    n = fn(s)
    if n == s:
        sys.exit(f"patch: ничего не изменилось в {rel}")
    p.write_text(n, encoding="utf-8")
    print("patched", rel)


# 1. имя пакета и одна универсальная сборка
edit("app/build.gradle.kts", lambda s: s
     .replace('applicationId = "io.github.romanvht.byedpi"', f'applicationId = "{APP_ID}"')
     .replace("isEnable = true", "isEnable = false"))

# 2. белый список по умолчанию
for rel in ["app/src/main/java/io/github/romanvht/byedpi/services/NativeEngine.kt",
            "app/src/main/java/io/github/romanvht/byedpi/utility/DiagnosticUtils.kt"]:
    edit(rel, lambda s: s.replace('"applist_type", "disable"', '"applist_type", "whitelist"'))

edit("app/src/main/res/xml/main_settings.xml", lambda s: re.sub(
    r'(android:key="applist_type"[\s\S]*?android:defaultValue=)"disable"', r'\1"whitelist"', s, count=1))

# 3. список приложений по умолчанию — YouTube
apps_kt = "setOf(" + ", ".join(f'"{a}"' for a in APPS) + ")"
edit("app/src/main/java/io/github/romanvht/byedpi/utility/PreferencesUtils.kt", lambda s: s.replace(
    'getStringSet("selected_apps", emptySet())', f'getStringSet("selected_apps", {apps_kt})'))

# 4. название приложения
for p in root.glob("app/src/main/res/values*/strings.xml"):
    s = p.read_text(encoding="utf-8")
    n = re.sub(r'<string name="app_name">[^<]*</string>', f'<string name="app_name">{NAME}</string>', s)
    if n != s:
        p.write_text(n, encoding="utf-8")
        print("patched", p.relative_to(root))

print("OK")
