#!/usr/bin/env python3
import socket
import serial
import threading
import time
import sys

def tcp_to_serial(sock, ser):
    try:
        while True:
            data = sock.recv(4096)
            if not data:
                print("TCP connection lost")
                break
            ser.write(data)
    except Exception as e:
        print(f"Error in tcp_to_serial: {e}")

def serial_to_tcp(sock, ser):
    try:
        while True:
            data = ser.read(max(1, ser.in_waiting))
            if data:
                sock.sendall(data)
            else:
                time.sleep(0.01)
    except Exception as e:
        print(f"Error in serial_to_tcp: {e}")

def main():
    port_name = "/tmp/ttyV0"
    host = "127.0.0.1"
    port = 9000

    print(f"Starting bridge emulator on {port_name} -> {host}:{port}")
    
    # Wait for the port to become available
    for i in range(10):
        try:
            ser = serial.Serial(port_name, baudrate=115200, timeout=0.1)
            break
        except serial.SerialException:
            time.sleep(0.5)
    else:
        print(f"Failed to open serial port {port_name}")
        sys.exit(1)

    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        sock.connect((host, port))
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    except Exception as e:
        print(f"Failed to connect to {host}:{port}: {e}")
        sys.exit(1)

    print("Bridge connected successfully.")

    t1 = threading.Thread(target=tcp_to_serial, args=(sock, ser), daemon=True)
    t2 = threading.Thread(target=serial_to_tcp, args=(sock, ser), daemon=True)

    t1.start()
    t2.start()

    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("Stopping bridge emulator")
    
    sock.close()
    ser.close()

if __name__ == "__main__":
    main()
