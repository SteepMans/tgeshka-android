# TGeshka для Android

Неофициальный клиент Telegram для своих: форк [Telegram для Android](https://github.com/DrKLO/Telegram) (GPLv2),
который подключается к Telegram только через шлюз. Ставится рядом с официальным приложением
(`ru.tgeshka.app`, свой ключ подписи).

## Транспорт

Каждое соединение tgnet к DC (основная сессия, медиа, загрузки, временные ключи, push-соединение) открывается
отдельным WebSocket по TLS на адрес шлюза. Внутри идут те же байты, что tgnet слал бы в TCP к DC: 64 байта
инициализации obfuscated2 (abridged, без секрета, с номером DC) и дальше зашифрованный поток. Протокол —
`docs/tg-protocol-v1.md` в SteepMans/api-gateway.

| Файл | Что делает |
|---|---|
| `TMessagesProj/jni/tgnet/Gateway.h/.cpp` | настройки шлюза, TLS (BoringSSL, системные корни Android, SNI и проверка имени), своя минимальная рамка WebSocket (маска, binary, ping/pong, close), счётчики для экрана состояния |
| `TMessagesProj/jni/tgnet/ConnectionSocketGateway.cpp` | `ConnectionSocket` поверх шлюза: TCP → TLS → upgrade с `Authorization: Bearer` и `X-TGeshka-Client: android/<версия>` → поток |
| `TMessagesProj/src/main/java/org/telegram/tgeshka/TGeshkaGateway.java` | адрес и ключ, передача в tgnet, ошибки шлюза, `/v1/info`, ссылки `tgeshka://gateway?url=…&token=…` |

Правки upstream минимальные: `ConnectionSocket::openConnection()` всегда идёт в шлюз, `Connection::sendData()`
пишет номер DC в инициализацию без секрета, `ConnectionsManager::setProxySettings()` игнорирует прокси, а в Java
отключены резервные запросы конфигурации через DNS-over-HTTPS (Google, Mozilla) и резолвинг через Google:
адрес шлюза разрешается только системным DNS (IPv4). Без ключа соединения сразу завершаются ошибкой, прямых
подключений к Telegram нет.

Коды закрытия шлюза показываются в «Настройки → TGeshka → Последняя ошибка»: 4401 (ключ отозван), 4429 (лимит
соединений), 4502 (шлюз не видит Telegram), 4504 (DC недоступен); ещё ошибки TLS, DNS и неверного ключа (HTTP 404).
После отказа в ключе новые соединения 30 с не открываются.

Мимо шлюза идут: звонки (VoIP, как в upstream), веб-страницы и мини-приложения, карты, предпросмотр внешних ссылок.

## Уведомления без FCM

Пуши Telegram привязаны к официальному приложению (ключи FCM Telegram), форку они не приходят. Вместо них
TGeshka держит в фоне push-соединение tgnet через шлюз (то же, что «Фоновое соединение» в настройках Telegram):
сервер Telegram шлёт по нему сигнал о новых событиях, приложение забирает обновления и показывает уведомления.
Сервис `NotificationsService` работает как foreground service (`specialUse`) с тихим уведомлением
«TGeshka на связи». Включено по умолчанию, выключается в «Настройки → TGeshka».

Ограничения:

- Батарея: открытое соединение и пинги (MTProto и WebSocket каждые 25 с) расходуют заметно больше, чем FCM.
- Оптимизация батареи Android (Doze) рвёт фоновую сеть. Кнопка «Не ограничивать в фоне» открывает системный
  запрос на исключение; без него уведомления приходят с задержкой.
- Оболочки производителей (MIUI/HyperOS, EMUI, One UI, ColorOS и др.) убивают фоновые приложения сами:
  нужно разрешить автозапуск, работу в фоне и не очищать TGeshka из «недавних». См. dontkillmyapp.com.
- После перезагрузки сервис стартует по `BOOT_COMPLETED`; на части прошивок это запрещено до первого запуска.
- Во время выката шлюза соединения рвутся и переподключаются, уведомления могут прийти с задержкой.
- Без открытого приложения медиа не качается: автозагрузка фото, видео и файлов из новых сообщений и
  предзагрузка историй работают только при открытом окне (`DownloadController.isAppInBackground`). Иначе каждое
  пробуждение сети в фоне (раз в 4 минуты) докачивало свежие медиа каналов и чатов — до 250 МБ в час.

## Обновления

Через шлюз (`docs/update-v1.md` в SteepMans/api-gateway): `GET /v1/update/manifest?platform=android&channel=stable`,
APK по `url` из ответа тем же ключом. Клиент проверяет размер, sha256 и подпись ed25519 над сырыми 32 байтами
sha256 (публичный ключ в `TGeshkaUpdater.java`) и ставит APK через PackageInstaller после подтверждения
пользователя. Проверка при запуске раз в 12 часов и вручную в «Настройки → TGeshka → Проверить обновления».

## Сборка

GitHub Actions (`.github/workflows/build.yml`): APK по ABI (`arm64-v8a` для релиза, `x86_64` для эмулятора),
смоук-тест на эмуляторе, релиз по тегу `vX.Y.Z` с `update-manifest.json`. Секреты репозитория:

| Секрет | Что это |
|---|---|
| `TGESHKA_API_ID`, `TGESHKA_API_HASH` | свои api_id/api_hash |
| `TGESHKA_GATEWAY_URL` | адрес шлюза по умолчанию, `wss://…/v1/stream` |
| `TGESHKA_KEYSTORE_BASE64`, `TGESHKA_KEYSTORE_PASSWORD` | ключ подписи APK (PKCS12, alias `tgeshka`) |
| `TGESHKA_UPDATE_SIGNING_KEY` | приватный ключ ed25519 для `update-manifest.json` (base64 32 байт) |
| `TGESHKA_TEST_TOKEN` | ключ шлюза для смоук-теста |

Локально те же значения можно положить в `local.properties` (`TGESHKA_API_ID=…`). Версия TGeshka —
`TGESHKA_VERSION` в `gradle.properties` или тег; `versionCode` = сборка Telegram × 100000 + версия TGeshka.

`.github/workflows/upstream.yml` раз в неделю ищет новый релиз DrKLO/Telegram (тег `release-*` или коммит
`update to X.Y.Z (build)`) и открывает PR со слиянием в main (merge, без force-push) и сборкой.

Иконки: `python Tools/tgeshka/gen_icons.py` (тот же логотип, что у TGeshka Desktop).
