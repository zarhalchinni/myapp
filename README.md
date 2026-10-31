# SCAT Driver (клиент водителя)

Нативный Android-клиент к серверу SCAT (протокол `$...*CS\r\n` поверх TCP).
Логин, приём и ведение заказов, самозаказ, heartbeat, реконнект на резервный сервер.

## Сборка через GitHub Actions
1. Создай репозиторий и запушь этот проект в ветку `main`.
2. Вкладка **Actions** → workflow **Build APK** запустится сам (или кнопка *Run workflow*).
3. По завершении скачай артефакт **scat-driver-debug** → внутри `app-debug.apk`.

APK — debug-подписи (для продакшена настрой release-подпись и keystore).

## Локальная сборка
Открыть в Android Studio → Run. Либо `gradle assembleDebug` (Gradle 8.10.2, JDK 17, Android SDK 35).

## Что реализовано
- `proto/Frame.kt` — кадрирование + XOR-контрольная сумма, обе формы кадра.
- `net/ScatConnection.kt` — сокет, LGN, heartbeat `p`, реконнект (main→reserve), парсер команд, действия с заказом.
- `net/ScatService.kt` — foreground-сервис (держит линию).
- UI на Compose: экран логина + экран работы (профиль, свободные заказы, активный заказ).

## Ещё не подключено (каркас готов)
- `GuardVpnService` (локальный VPN-блок конкурента) — отдельный модуль.
- Свой таксометр (расчёт по `rate.modifiers`), чат по заказу, безнал.
- Release-подпись APK.
