#!/usr/bin/env python3
import serial
import time
import sys

def main():
    port_name = "/tmp/ttyV1"
    test_data = b"SCANMATIK_TEST_DATA_12345"
    
    print(f"Opening test port {port_name}...")
    
    # Wait for the port to become available
    for i in range(10):
        try:
            ser = serial.Serial(port_name, baudrate=115200, timeout=2.0)
            break
        except serial.SerialException:
            time.sleep(0.5)
    else:
        print(f"Failed to open serial port {port_name}")
        sys.exit(1)

    print(f"Sending test data: {test_data}")
    start_time = time.time()
    
    ser.write(test_data)
    
    # Read response
    received_data = b""
    while len(received_data) < len(test_data):
        chunk = ser.read(len(test_data) - len(received_data))
        if not chunk:
            break
        received_data += chunk
        
    end_time = time.time()
    
    latency_ms = (end_time - start_time) * 1000
    
    print(f"Received data: {received_data}")
    print(f"Latency: {latency_ms:.2f} ms")
    
    if received_data == test_data:
        print("\n✅ TEST PASSED: Data integrity verified end-to-end!")
        sys.exit(0)
    else:
        print("\n❌ TEST FAILED: Data mismatch or timeout!")
        sys.exit(1)

if __name__ == "__main__":
    main()
