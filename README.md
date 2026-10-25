# Dash

VPN-клиент для Android и Windows на ядрах sing-box и Xray. Вставил ссылку подписки — нажал одну кнопку.

<p align="center">
  <img src="docs/off.jpg" width="250" alt="Dash: отключено">
  <img src="docs/on.jpg" width="250" alt="Dash: подключено">
</p>

## Возможности

- **Без списка серверов.** Dash сам выбирает самый быстрый.
- **Проверка, что трафик реально идёт.** Раз в 5 минут и после смены сервера Dash скачивает через него небольшой файл (128 КБ). Если сервер отвечает на пинг, но загрузку не пропускает (так ведут себя «замороженные» блокировкой соединения), Dash на 15 минут убирает его из автовыбора и переходит на сервер, который проверку прошёл.
- **Раздельная маршрутизация.** Российские сайты идут напрямую, остальные и заблокированные в РФ — через VPN.
- **Белые списки.** Две группы серверов: обычные и «LTE». Если оператор включил белые списки, Dash это определит и переключится сам.
- **Подписки.** Ссылки `vless`, `vmess`, `trojan`, `ss`, `hy2` и JSON-конфиги Xray / sing-box. Подписка обновляется автоматически.
- **Android.** Плитка в шторке, виджет на рабочий стол, исключения для приложений.
- **Windows.** Значок в трее, автозапуск вместе с Windows, исключения для программ. Автообновление принимает только версии с подписью ed25519.

## Установка

- **Android** — подписанный APK из [Releases](../../releases).
- **Windows** — архив `Dash-Windows-<версия>.zip` из [Releases](../../releases).

## Сборка

Версии ядер и инструментов зафиксированы в [`versions.env`](versions.env). [`build.yml`](.github/workflows/build.yml) на каждый push прогоняет тесты и собирает APK и архив для Windows (лежат в артефактах запуска).

**Android**

```sh
scripts/fetch-cores.sh android
TOOLS=<папка с aapt2, dx.jar, apksigner.jar> ANDROID_JAR=… CORES_DIR=out ./build.sh
```

`fetch-cores.sh` собирает sbhelper и скачивает Xray в `out/`. `build.sh` собирает APK без Gradle (aapt2, javac, dx, apksigner). Ключ подписи — переменные `DASH_KS`, `DASH_KS_PASS`.

`helper/main.go` кладётся в `cmd/sbhelper` sing-box 1.12 и собирается под android/arm64; бинарник упаковывается как `lib/arm64-v8a/libsbhelper.so`.

**Windows**

```sh
scripts/fetch-cores.sh windows && scripts/package-windows.sh
```

Результат — `out/Dash-Windows-<версия>.zip`.

**Тесты**

```sh
scripts/test-android.sh
cd windows && go test ./...
```

Оба прогоняют [`testdata/sub-cases.json`](testdata/sub-cases.json), чтобы парсеры подписок на Android и Windows не расходились.

**Выпуск**

1. Поднять версию: `build.sh` и `AndroidManifest.xml` (Android) или `Version` в `windows/app.go` (Windows).
2. Дописать, что нового: [`Changelog.java`](src/app/dash/Changelog.java) (по versionCode) или [`windows/changelog.go`](windows/changelog.go). Эти же строки приложение показывает после обновления, и они же становятся описанием релиза.
3. После слияния в main: Actions → `release-android` или `release-windows` → Run workflow. Workflow собирает сборку, публикует релиз с описанием и SHA-256 и обновляет подписанный `version.json`.

Описания уже выпущенных релизов обновляет `release-notes` сам, когда меняются списки изменений.

---

Создано с помощью Claude (Anthropic).
