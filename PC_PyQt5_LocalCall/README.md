# LocalCall PC (PyQt5)

Desktop-версия LocalCall на `PyQt5` с аналогичными настройками и сетевыми протоколами:

- LAN discovery: `LOCALCALL_PEER` (UDP `45678`)
- Ping/Pong discovery: `PING/PONG` (UDP `45677`)
- Direct signaling: `CALL/ACCEPT/BUSY/BYE` (TCP `45680`)
- Audio stream: PCM 24kHz mono 16-bit (UDP `45679`)
- Relay mode через сервер: `REGISTER2/CALL2/SESSION/EVENT/BYE2/REJECT2`
- Relay UDP bootstrap: `LCHELLO|session|token`

## Запуск

```bash
cd PC_PyQt5_LocalCall
python -m pip install -r requirements.txt
python main.py
```

## Настройки

В приложении доступны те же ключевые параметры:

- Имя устройства
- IP/порт relay-сервера
- Микрофон: system / bluetooth
- Выход: earpiece / speaker / bluetooth
- Auto discovery
- Background service
- Auto accept calls
- Mic gain / Speaker gain
- Custom noise suppression
- UPnP (клиентский флаг совместимости)

Настройки хранятся в:

- `PC_PyQt5_LocalCall/data/settings.json`

## Совместимость

Desktop-клиент совместим с сервером из текущего репозитория:

- `Server/server.py`

Можно использовать один сервер для Android и PC клиентов одновременно.

