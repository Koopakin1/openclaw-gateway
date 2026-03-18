# USB-TCP Gateway

Утилитарное Android-приложение для сквозной пересылки «сырых» байтов (Raw Data)
между USB-портом смартфона и TCP-сервером через мобильный интернет.

## Сборка

1. Откройте проект в Android Studio (Arctic Fox+).
2. **Build → Make Project** или из терминала:
   ```bash
   ./gradlew assembleDebug
   ```
3. APK будет в `app/build/outputs/apk/debug/`.

## Тестирование

Запустите эхо-сервер на ПК:

```bash
python3 echo_server.py --host 0.0.0.0 --port 9000
```

В приложении укажите IP компьютера и порт 9000, подключите USB-сканер, нажмите **Старт**.

## Архитектура

```
USB Device ←→ UsbBridge (bulkTransfer)
                  ↕
             TunnelEngine (2 coroutines)
                  ↕
             TcpBridge (Socket)
                  ↕
           Remote TCP Server
```
