# LocalCall — VoIP приложение для локальной сети

Голосовые звонки между устройствами в одной Wi-Fi сети без интернета.

---

## Компоненты проекта

| Компонент | Описание |
|-----------|----------|
| 📱 `app/` | Android-приложение (Kotlin) |
| 🖥️ `PC_PyQt5_LocalCall/` | Desktop-клиент (Python + PyQt5) |
| 🌐 `Server/` | Серверная часть (Python) |

---

## Функционал

| Функция | Описание |
|---------|----------|
| 🔍 Автопоиск | UDP broadcast автоматически находит устройства в той же Wi-Fi сети |
| 📞 Подключение | Одна кнопка — звонок соединяется автоматически |
| ⏱ Таймер | Отсчёт длительности звонка в реальном времени |
| 🔇 Mute | Выключить/включить микрофон во время звонка |
| ❌ Завершить | Завершить звонок с обеих сторон |
| 🔊 Выход звука | Динамик / наушник / Bluetooth-гарнитура |
| 🎤 Микрофон | Системный / VoiceComm / Camcorder |
| 🦷 Bluetooth | Список сопряжённых BT-гарнитур (Android) |
| 🔔 Уведомления | Форграунд-сервис с кнопками принять/отклонить (Android) |

---

## Архитектура

```
Android (app/)
├── MainActivity          — список устройств, кнопка подключения
├── CallActivity          — активный звонок (таймер, mute, завершить)
├── SettingsActivity      — настройки микрофона и вывода звука
└── CallService
    ├── AudioEngine       — AudioRecord + AudioTrack, UDP-потоки
    ├── PeerDiscovery     — UDP broadcast (порт 45678)
    └── SignalingServer   — TCP для сигнализации (порт 45680)

Desktop (PC_PyQt5_LocalCall/)
├── main.py               — точка входа
└── localcall_pc/         — основной код клиента

Server (Server/)
├── server.py             — серверная логика
└── config.json           — конфигурация
```

---

## Сетевые порты

| Порт | Протокол | Назначение |
|------|----------|------------|
| 45678 | UDP (broadcast) | Обнаружение устройств |
| 45679 | UDP (unicast) | Потоковое аудио |
| 45680 | TCP | Сигнализация (CALL / ACCEPT / BYE) |

---

## Сборка и запуск

### Android

**Требования:**
- Android Studio Flamingo или новее
- Android SDK 34
- Kotlin 1.9+
- Минимальный Android: API 26 (Android 8.0)

**Шаги:**
```bash
# Открыть проект в Android Studio
File → Open → выбрать папку LocalCall

# Запустить на устройстве или эмуляторе
Run → Run 'app'
```

### Desktop (PyQt5)

**Требования:**
- Python 3.8+
- PyQt5
- Другие зависимости из `requirements.txt`

**Шаги:**
```bash
cd PC_PyQt5_LocalCall
pip install -r requirements.txt
python main.py
```

### Server

**Требования:**
- Python 3.8+

**Шаги:**
```bash
cd Server
python server.py
```

---

## Разрешения (Android)

```xml
RECORD_AUDIO              — запись с микрофона
INTERNET                  — сетевые сокеты
ACCESS_WIFI_STATE         — информация о Wi-Fi
CHANGE_WIFI_MULTICAST_STATE — UDP broadcast
BLUETOOTH_CONNECT         — подключение BT-устройств (API 31+)
MODIFY_AUDIO_SETTINGS     — переключение динамик/наушник
FOREGROUND_SERVICE        — фоновый сервис звонка
```

---

## Схема работы звонка

```
Устройство A                    Устройство B
     |                               |
     |──── UDP broadcast ───────────>|  (каждые 2 сек)
     |<─── UDP broadcast ────────────|  (каждые 2 сек)
     |                               |
     |  [пользователь нажимает "Подключиться"]
     |                               |
     |──── TCP CALL 45679 ──────────>|  (SignalingServer)
     |<─── TCP ACCEPT 45679 ─────────|
     |                               |
     |<══ UDP audio stream ══════════|  (AudioEngine, порт 45679)
     |══ UDP audio stream ══════════>|
     |                               |
     |──── TCP BYE ─────────────────>|  (завершение)
```

---

## Настройки аудио (Android)

Открыть через ⚙️ в правом верхнем углу главного экрана.

- **Микрофон**: Default / VoiceComm (рекомендуется) / Camcorder
- **Выход**: Динамик / Наушник / Bluetooth
- **Bluetooth**: список сопряжённых гарнитур из BluetoothAdapter

Настройки применяются к следующему звонку.
