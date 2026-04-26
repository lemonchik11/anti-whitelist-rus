# AntiWhitelist

**AntiWhitelist** — Android-приложение, которое туннелирует WireGuard-трафик через TURN-серверы ВКонтакте или Яндекс Телемоста (DTLS 1.2), обходя блокировки без использования сторонних VPN-провайдеров.

```
[Android App]
   └─ WireGuard (endpoint: 127.0.0.1:9000, MTU 1280)
   └─ good-turn client (Kotlin, слушает :9000)
         │
         │  DTLS 1.2 внутри TURN ChannelData (TCP/UDP)
         ▼
[TURN-серверы ВК / Яндекса]
         │
         │
         ▼
[VPS — good-turn сервер :56000]
   └─ WireGuard (слушает :51820)
         │
         ▼
    [Интернет]
```

---

## Возможности

- **Несколько профилей** — создавайте, импортируйте и переключайте конфиги; у каждого свой сервер, ссылка, настройки
- **Импорт `.conf`** — загрузите стандартный WireGuard .conf и приложение автоматически заполнит поля (MTU принудительно 1280)
- **Два провайдера TURN** — ВК Звонки (16 параллельных соединений) и Яндекс Телемост (1)
- **DTLS 1.2** — шифрование через BouncyCastle (ECDHE-ECDSA-AES128-GCM-SHA256, самоподписанный P-256 сертификат)
- **WireGuard GoBackend** — полноценный VPN-интерфейс (`wireguard-android`)
- **CIDR anti-loop** — IP TURN-сервера автоматически исключается из AllowedIPs
- **Автозапуск** — опционально запускает выбранный профиль при загрузке устройства
- **Лог** — живой лог подключения прямо на главном экране

---

## Быстрый старт

### 1. Сборка
```bash
git clone https://github.com/yakovsava/antiwhitelist
cd antiwhitelist
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

### 2. Настройка сервера
→ См. [SERVER_SETUP.md](SERVER_SETUP.md)

### 3. Настройка приложения

1. Получите ссылку на активный **ВК звонок** (формат `https://vk.com/call/join/XXXX`) или **Яндекс Телемост** (`https://telemost.yandex.ru/j/XXXX`).
2. На сервере откройте `http://YOUR_VPS_IP:51821`, создайте клиента, скачайте `.conf`.
3. В приложении: **⋮ → Импорт .conf** — загрузите файл. Все WireGuard-поля заполнятся автоматически.
4. Введите ссылку на звонок.
5. Убедитесь, что **IP сервера** (поле «Good-Turn сервер IP») совпадает с IP вашего VPS.
6. Нажмите ▶ (play) у профиля.

### 4. Импорт .conf вручную
Пример `.conf`:
```ini
[Interface]
PrivateKey = GDEPMoPxVOO/mBcntV7VCGCwz+Hp2+XOEz59/H+hTmQ=
Address = 10.8.0.2/24
DNS = 1.1.1.1

[Peer]
PublicKey = r99/A4CLi2Ob9Ogfc+TmdebgeCHd81HszHk3IJgX7Rg=
PresharedKey = Rw0oQcvm4iQeYnzcc+jBNUJnLAPyq5JlE/XDfIx34Jg=
AllowedIPs = 0.0.0.0/0, ::/0
PersistentKeepalive = 25
Endpoint = 188.17.159.51:51820
```
> MTU всегда принудительно устанавливается в 1280 при импорте.

---

## Структура проекта

```
app/src/main/java/dev/yakovsava/antiwhitelist/
├── AntiWhitelistApp.kt              — Application, каналы уведомлений
├── data/
│   ├── VpnProfile.kt                — модель профиля + JSON сериализация
│   ├── WireGuardConfParser.kt       — парсер стандартного .conf файла
│   └── ProfileRepository.kt        — хранение профилей в DataStore
├── turn/
│   ├── StunMsg.kt                   — STUN RFC 8489: encode/decode, HMAC-SHA1, CRC32
│   ├── TurnConn.kt                  — TURN клиент TCP/UDP: Allocate, ChannelBind, ChannelData
│   ├── DtlsEngine.kt                — DTLS 1.2 через BouncyCastle (pion/dtls совместимый)
│   ├── Credentials.kt               — получение TURN credentials от ВК и Яндекса
│   └── GoodTurnTunnel.kt            — оркестрация N параллельных соединений, UDP мост :9000
├── service/
│   ├── GoodTurnVpnService.kt        — VpnService: запуск туннеля → WireGuard GoBackend
│   ├── CidrExclusion.kt             — разбивка CIDR для исключения TURN IP из маршрутов
│   └── BootReceiver.kt              — автозапуск при загрузке
└── ui/
    ├── ProfilesViewModel.kt
    ├── ProfilesAdapter.kt           — RecyclerView адаптер для списка профилей
    ├── MainActivity.kt              — список профилей + лог
    └── ProfileEditActivity.kt       — редактор профиля с импортом .conf и генерацией ключей
```

---

## Требования

- Android 8.0+ (API 26)
- Активный **ВК звонок** (не закончившийся, со ссылкой join) или **Яндекс Телемост** сессия
- VPS с запущенным good-turn сервером и WireGuard (см. SERVER_SETUP.md)

---

## Зависимости

| Библиотека | Назначение |
|---|---|
| `wireguard-android:tunnel` | WireGuard GoBackend (userspace) |
| `bctls-jdk18on` | DTLS 1.2 (BouncyCastle) |
| `bcpkix-jdk18on` | X.509 сертификат для DTLS |
| `okhttp3` | HTTP + WebSocket для получения TURN credentials |
| `datastore-preferences` | Хранение профилей |
| `java-uuid-generator` | Time-based UUID для Yandex API |

---

## Лицензия

MIT License — см. [LICENSE](LICENSE)

---

## Disclaimer

Проект создан в исследовательских целях. Использование в соответствии с законодательством вашей страны — ответственность пользователя.
