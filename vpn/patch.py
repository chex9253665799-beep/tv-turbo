"""
Патч исходников v2rayNG → «ТВ Турбо VPN».
Суть: VPN через ТВОЙ сервер работает ТОЛЬКО для YouTube (режим «только выбранные приложения»),
всё остальное — напрямую. Ссылку на сервер передаёт «ТВ Турбо» (вводится с телефона и хранится
только на телевизоре — в репозитории и в APK её нет).
Плюс своё имя пакета, чтобы не конфликтовать с обычным v2rayNG.
Запуск: python3 patch.py <папка с исходниками v2rayNG>
"""
import pathlib
import re
import shutil
import sys

root = pathlib.Path(sys.argv[1]) / "V2rayNG"
here = pathlib.Path(__file__).parent
APP_ID = "ru.chex.tvturbo.vpn"
NAME = "ТВ Турбо VPN"
PERM = "ru.chex.tvturbo.permission.CONTROL"


def edit(rel, fn):
    p = root / rel
    s = p.read_text(encoding="utf-8")
    n = fn(s)
    if n == s:
        sys.exit(f"patch: ничего не изменилось в {rel}")
    p.write_text(n, encoding="utf-8")
    print("patched", rel)


# 1. имя пакета
edit("app/build.gradle.kts", lambda s: s.replace('applicationId = "com.v2ray.ang"', f'applicationId = "{APP_ID}"'))

# 2. управление из ТВ Турбо
shutil.copy(here / "TvTurboActivity.kt", root / "app/src/main/java/com/v2ray/ang/ui/TvTurboActivity.kt")
print("added TvTurboActivity.kt")

MANIFEST_PERM = f'''
    <permission android:name="{PERM}" android:protectionLevel="signature" />
'''
MANIFEST_ACT = f'''
        <activity
            android:name=".ui.TvTurboActivity"
            android:exported="true"
            android:excludeFromRecents="true"
            android:noHistory="true"
            android:theme="@android:style/Theme.Translucent.NoTitleBar" />
        <activity
            android:name=".ui.TvTurboConfigActivity"
            android:exported="true"
            android:excludeFromRecents="true"
            android:permission="{PERM}"
            android:theme="@android:style/Theme.Translucent.NoTitleBar" />
    </application>'''
edit("app/src/main/AndroidManifest.xml", lambda s: re.sub(
    r"(<application)", MANIFEST_PERM.strip() + r"\n\n    \1", s, count=1).replace("</application>", MANIFEST_ACT.strip("\n"), 1))

# 3. название приложения
for p in root.glob("app/src/main/res/values*/strings.xml"):
    s = p.read_text(encoding="utf-8")
    n = re.sub(r'<string name="app_name"([^>]*)>[^<]*</string>', rf'<string name="app_name"\1>{NAME}</string>', s)
    if n != s:
        p.write_text(n, encoding="utf-8")

print("OK")
