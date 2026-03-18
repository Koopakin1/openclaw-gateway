#!/bin/bash
set -e

echo "1. Starting pseudo-socat virtual serial ports..."
python3 /tmp/pseudo_socat.py &
SOCAT_PID=$!
sleep 1

echo "2. Starting echo_server.py in background..."
python3 /home/fedr/.gemini/antigravity/scratch/usb-tcp-gateway/echo_server.py --host 127.0.0.1 --port 9000 > /tmp/echo_server.log 2>&1 &
ECHO_PID=$!
sleep 1

echo "3. Starting bridge emulator..."
python3 /tmp/bridge_emulator.py > /tmp/bridge.log 2>&1 &
BRIDGE_PID=$!
sleep 1

echo "4. Running test script..."
python3 /tmp/run_test.py

echo "Cleaning up..."
kill $BRIDGE_PID
kill $ECHO_PID
kill $SOCAT_PID

echo "Echo server log:"
cat /tmp/echo_server.log
