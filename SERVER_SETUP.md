# SERVER_SETUP.md — Развёртывание сервера

## Архитектура

```
[Android AntiWhitelist]
    WireGuard UDP → 127.0.0.1:9000 (DTLS-зашифрован)
                          │
                    GoodTurnTunnel
                          │ DTLS 1.2 внутри TURN ChannelData
                          ▼
              [TURN-серверы ВК / Яндекс]
                          │ UDP relay
                          ▼
              [VPS  — ваш сервер]
              good-turn сервер :56000  ←── расшифровывает DTLS, форвардит в WireGuard
              WireGuard          :51820
              wg-easy (UI)       :51821
```

---

## Вариант A — Docker Compose (рекомендуется)

### Требования
- Ubuntu 22.04 / 24.04, 1 vCPU, 512 MB RAM
- Docker + Docker Compose v2

### Шаг 1 — Установка Docker
```bash
apt update && apt upgrade -y
apt install -y docker.io docker-compose-plugin
systemctl enable --now docker
```

### Шаг 2 — UFW
```bash
ufw default deny incoming
ufw default allow outgoing
ufw allow 22/tcp           # SSH (замените на ваш порт)
ufw allow 51820/udp        # WireGuard
ufw allow 51821/tcp        # wg-easy веб-интерфейс (закройте после настройки)
ufw allow 56000/udp        # good-turn сервер
ufw enable
```

### Шаг 3 — Хэш пароля для wg-easy
```bash
docker run --rm ghcr.io/wg-easy/wg-easy wgpw 'ВашПароль'
# Скопируйте вывод вида: $2a$12$...
# В docker-compose знаки $ нужно удваивать: $$2a$$12$$...
```

### Шаг 4 — docker-compose.yml
Создайте `/opt/vpn/docker-compose.yml`:

```yaml
version: "3.8"

services:
  wg-easy:
    image: ghcr.io/wg-easy/wg-easy
    container_name: wg-easy
    restart: unless-stopped
    cap_add:
      - NET_ADMIN
      - SYS_MODULE
    sysctls:
      - net.ipv4.ip_forward=1
      - net.ipv4.conf.all.src_valid_mark=1
    volumes:
      - wg-easy-data:/etc/wireguard
    ports:
      - "51820:51820/udp"
      - "51821:51821/tcp"
    environment:
      - LANG=ru
      - WG_HOST=ВАШ_IP_VPS            # ← IP вашего сервера
      - PASSWORD_HASH=$$2a$$12$$...   # ← хэш из шага 3 ($ удваивать!)
      - WG_PORT=51820
      - WG_DEFAULT_ADDRESS=10.8.0.x
      - WG_DEFAULT_DNS=1.1.1.1
      - WG_MTU=1280                    # ОБЯЗАТЕЛЬНО 1280
      - WG_PERSISTENT_KEEPALIVE=25
      - UI_TRAFFIC_STATS=true

  good-turn:
    image: ghcr.io/cacggghp/vk-turn-proxy:latest
    container_name: good-turn
    restart: unless-stopped
    network_mode: host              # нужен для forwarding в WireGuard
    environment:
      - CONNECT_ADDR=127.0.0.1:51820

volumes:
  wg-easy-data:
```

> **Если образ good-turn не опубликован** — соберите локально:
> ```bash
> git clone https://github.com/cacggghp/vk-turn-proxy
> cd vk-turn-proxy && docker build -t good-turn .
> # В compose замените image на: good-turn
> ```

### Шаг 5 — Запуск
```bash
mkdir -p /opt/vpn && cd /opt/vpn
# Создайте docker-compose.yml как выше
docker compose up -d
docker compose logs -f
```

### Шаг 6 — Создание клиента в wg-easy

1. Откройте `http://ВАШ_IP:51821`
2. Нажмите **+ New** → имя клиента `android`
3. Скачайте `.conf`

> **Endpoint в `.conf` указывает на реальный IP сервера** — оставьте как есть.  
> Приложение AntiWhitelist автоматически заменит его на `127.0.0.1:9000` при подключении.

---

## Вариант B — Ручная установка

### WireGuard
```bash
apt install -y wireguard

# Генерируем ключи сервера
wg genkey | tee /etc/wireguard/server_private.key | wg pubkey > /etc/wireguard/server_public.key
chmod 600 /etc/wireguard/server_private.key

# /etc/wireguard/wg0.conf
cat > /etc/wireguard/wg0.conf << EOF
[Interface]
PrivateKey = $(cat /etc/wireguard/server_private.key)
Address = 10.8.0.1/24
ListenPort = 51820
MTU = 1280
PostUp   = iptables -A FORWARD -i %i -j ACCEPT; iptables -A FORWARD -o %i -j ACCEPT; iptables -t nat -A POSTROUTING -o eth0 -j MASQUERADE
PostDown = iptables -D FORWARD -i %i -j ACCEPT; iptables -D FORWARD -o %i -j ACCEPT; iptables -t nat -D POSTROUTING -o eth0 -j MASQUERADE
EOF

# IP forwarding
echo "net.ipv4.ip_forward=1" >> /etc/sysctl.conf
sysctl -p

# Запуск
systemctl enable --now wg-quick@wg0
```

### Добавление клиента
```bash
# Генерируем ключи клиента
CLIENT_PRIV=$(wg genkey)
CLIENT_PUB=$(echo "$CLIENT_PRIV" | wg pubkey)
CLIENT_PSK=$(wg genpsk)

# Добавляем peer в wg0.conf
cat >> /etc/wireguard/wg0.conf << EOF

[Peer]
PublicKey = $CLIENT_PUB
PresharedKey = $CLIENT_PSK
AllowedIPs = 10.8.0.2/32
EOF

wg syncconf wg0 <(wg-quick strip wg0)

# Сохраните CLIENT_PRIV, CLIENT_PUB, CLIENT_PSK — нужны для приложения
echo "Private: $CLIENT_PRIV"
echo "Public:  $CLIENT_PUB"
echo "PSK:     $CLIENT_PSK"
```

### good-turn сервер
```bash
# Вариант 1: готовый бинарник
wget https://github.com/cacggghp/vk-turn-proxy/releases/latest/download/server-linux-amd64
chmod +x server-linux-amd64
mv server-linux-amd64 /usr/local/bin/good-turn-server

# Вариант 2: из исходников
apt install -y golang-go git
git clone https://github.com/cacggghp/vk-turn-proxy
cd vk-turn-proxy
go build -o /usr/local/bin/good-turn-server ./server/

# Запуск через systemd
cat > /etc/systemd/system/good-turn.service << EOF
[Unit]
Description=good-turn server
After=network.target wg-quick@wg0.service

[Service]
ExecStart=/usr/local/bin/good-turn-server -connect 127.0.0.1:51820 -listen 0.0.0.0:56000
Restart=always
RestartSec=5
User=nobody

[Install]
WantedBy=multi-user.target
EOF

systemctl enable --now good-turn
journalctl -fu good-turn
```

---

## Проверка

```bash
# Порты
ss -ulnp | grep 51820    # WireGuard
ss -ulnp | grep 56000    # good-turn

# IP forwarding
sysctl net.ipv4.ip_forward   # = 1

# WireGuard статус
wg show                       # или через wg-easy UI

# good-turn (Docker)
docker compose logs good-turn
```

---

## Настройки клиента в приложении

| Поле | Значение |
|---|---|
| Ссылка на звонок | `https://vk.com/call/join/XXXX` |
| IP сервера (Good-Turn) | IP вашего VPS |
| Порт сервера | `56000` |
| Приватный ключ | из `.conf` или сгенерируйте в приложении |
| Публичный ключ сервера | из wg-easy или `cat /etc/wireguard/server_public.key` |
| Endpoint (оригинальный) | `ВАШ_IP:51820` |

---

## Обновление (Docker)

```bash
cd /opt/vpn
docker compose pull
docker compose up -d
```

---

## Известные проблемы

| Симптом | Решение |
|---|---|
| DTLS handshake зависает | Проверьте, что порт 56000/udp открыт в UFW и у хостера |
| WireGuard не поднимается | Убедитесь что MTU=1280 в wg-easy и в `.conf` |
| Ошибка 401 Allocate навсегда | Устаревшая ссылка на звонок; обновите ссылку |
| Маршрутизация не работает | `sysctl net.ipv4.ip_forward` должен быть 1; проверьте PostUp в wg0.conf |
| `wg-easy` не открывается | `ufw allow 51821/tcp` |
