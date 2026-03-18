#!/usr/bin/env python3
import os
import pty
import time
import threading

def forward_data(fd1, fd2):
    while True:
        try:
            data = os.read(fd1, 4096)
            if data:
                os.write(fd2, data)
        except OSError:
            break

def main():
    master1, slave1 = pty.openpty()
    master2, slave2 = pty.openpty()

    s1_name = os.ttyname(slave1)
    s2_name = os.ttyname(slave2)

    # Symlink to requested names
    try: os.unlink('/tmp/ttyV0')
    except OSError: pass
    try: os.unlink('/tmp/ttyV1')
    except OSError: pass

    os.symlink(s1_name, '/tmp/ttyV0')
    os.symlink(s2_name, '/tmp/ttyV1')

    print(f"Created virtual serial ports: /tmp/ttyV0 -> {s1_name}, /tmp/ttyV1 -> {s2_name}")

    t1 = threading.Thread(target=forward_data, args=(master1, master2), daemon=True)
    t2 = threading.Thread(target=forward_data, args=(master2, master1), daemon=True)

    t1.start()
    t2.start()

    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("Stopping pseudo-socat")

if __name__ == "__main__":
    main()
