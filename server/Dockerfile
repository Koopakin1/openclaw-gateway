# ==============================================================================
# Scanmatik-Web: Docker-контейнер для удалённой диагностики
# ==============================================================================
# Контейнер запускает Windows-программу "Сканматик 2" через Wine,
# транслирует её интерфейс в браузер через noVNC и принимает
# диагностические данные по TCP (от Android USB-шлюза), маппя их
# на виртуальный COM-порт внутри Wine.
# ==============================================================================

FROM ubuntu:22.04

# --- Отключаем интерактивные диалоги apt ---
ENV DEBIAN_FRONTEND=noninteractive

# --- Включаем поддержку 32-битной архитектуры (Wine i386) ---
RUN dpkg --add-architecture i386

# --- Устанавливаем все зависимости одним слоем ---
RUN apt-get update && apt-get install -y --no-install-recommends \
    # Wine (32-бит + 64-бит) — запуск Windows-приложений
    wine \
    wine32 \
    wine64 \
    # Xvfb — виртуальный X11-дисплей (без физического монитора)
    xvfb \
    # Fluxbox — легковесный оконный менеджер для X11
    fluxbox \
    # x11vnc — VNC-сервер, захватывает экран Xvfb
    x11vnc \
    # noVNC + websockify — прокси WebSocket→VNC для доступа из браузера
    novnc \
    websockify \
    # socat — создаёт виртуальный COM-порт из TCP-потока
    socat \
    # Утилиты
    xterm \
    procps \
    net-tools \
    && rm -rf /var/lib/apt/lists/*

# --- Переменные окружения ---
ENV DISPLAY=:0
ENV WINEPREFIX=/root/.wine
ENV WINEARCH=win32

# --- Создаём директорию для приложения Сканматик ---
RUN mkdir -p /app

# --- Инициализируем Wine (создаёт ~/.wine, принимает EULA) ---
# wineboot создаёт базовую структуру Wine-префикса
RUN xvfb-run wine wineboot --init 2>/dev/null || true

# --- Создаём директорию dosdevices если не существует ---
RUN mkdir -p /root/.wine/dosdevices

# --- Копируем скрипт запуска ---
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

# --- Порты ---
# 8080 — noVNC (веб-интерфейс в браузере)
# 9000 — TCP (приём данных от Android USB-шлюза)
EXPOSE 8080 9000

# --- Точка входа ---
ENTRYPOINT ["/entrypoint.sh"]
