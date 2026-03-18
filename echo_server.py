#!/usr/bin/env python3
"""
echo_server.py — TCP Echo Server for USB-TCP Gateway testing.

Usage:
    python3 echo_server.py --host 0.0.0.0 --port 9000

Accepts one TCP connection at a time. Every packet received
is echoed back to the sender and printed as a hex dump.
Press Ctrl+C to stop.
"""

import argparse
import socket
import sys
import time
from datetime import datetime


def hex_dump(data: bytes, width: int = 16) -> str:
    """Format bytes as a readable hex dump (offset | hex | ASCII)."""
    lines = []
    for offset in range(0, len(data), width):
        chunk = data[offset:offset + width]
        hex_part = " ".join(f"{b:02X}" for b in chunk)
        ascii_part = "".join(chr(b) if 32 <= b < 127 else "." for b in chunk)
        lines.append(f"  {offset:04X}  {hex_part:<{width * 3}}  |{ascii_part}|")
    return "\n".join(lines)


def timestamp() -> str:
    return datetime.now().strftime("%H:%M:%S.%f")[:-3]


def serve(host: str, port: int):
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((host, port))
    server.listen(1)

    print(f"[{timestamp()}] Echo server listening on {host}:{port}")
    print(f"[{timestamp()}] Waiting for connection from USB-TCP Gateway app...")
    print()

    try:
        while True:
            conn, addr = server.accept()
            conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            print(f"[{timestamp()}] ✅ Connection from {addr[0]}:{addr[1]}")

            total_rx = 0
            total_tx = 0
            pkt_count = 0

            try:
                while True:
                    data = conn.recv(4096)
                    if not data:
                        print(f"\n[{timestamp()}] ❌ Connection closed by client")
                        break

                    pkt_count += 1
                    total_rx += len(data)

                    print(f"\n[{timestamp()}] ◀ RX packet #{pkt_count} ({len(data)} bytes):")
                    print(hex_dump(data))

                    # Echo back
                    conn.sendall(data)
                    total_tx += len(data)

                    print(f"[{timestamp()}] ▶ TX echo ({len(data)} bytes)")

            except (ConnectionResetError, BrokenPipeError) as e:
                print(f"\n[{timestamp()}] ❌ Connection lost: {e}")
            except KeyboardInterrupt:
                raise
            finally:
                conn.close()
                print(f"[{timestamp()}] Stats: {pkt_count} packets, "
                      f"RX={total_rx} bytes, TX={total_tx} bytes")
                print(f"[{timestamp()}] Waiting for next connection...\n")

    except KeyboardInterrupt:
        print(f"\n[{timestamp()}] Server stopped.")
    finally:
        server.close()


def main():
    parser = argparse.ArgumentParser(
        description="TCP Echo Server for USB-TCP Gateway testing"
    )
    parser.add_argument("--host", default="0.0.0.0",
                        help="Listen address (default: 0.0.0.0)")
    parser.add_argument("--port", type=int, default=9000,
                        help="Listen port (default: 9000)")
    args = parser.parse_args()

    serve(args.host, args.port)


if __name__ == "__main__":
    main()
