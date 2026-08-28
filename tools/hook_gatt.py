#!/usr/bin/env python3
import sys
import time
from pathlib import Path

import frida

PKG = "com.dnateam.deep_app"
JS = Path(__file__).with_name("hook_gatt.js").read_text()


def on_msg(message, data):
    if message["type"] == "send":
        print(message["payload"], flush=True)
    elif message["type"] == "error":
        print("ERR", message, flush=True)
    else:
        print(message, flush=True)


def main():
    device = frida.get_usb_device(timeout=8)
    print("device", device, flush=True)
    import subprocess
    subprocess.call(["adb", "shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1"])
    pid = None
    for _ in range(20):
        time.sleep(0.4)
        out = subprocess.check_output(["adb", "shell", "pidof", PKG], text=True).strip()
        if out:
            pid = int(out.split()[0])
            break
    if pid is None:
        raise SystemExit("app did not start")
    print("attaching", pid, flush=True)
    session = device.attach(pid)
    time.sleep(1.0)
    script = session.create_script(JS)
    script.on("message", on_msg)
    script.set_log_handler(lambda level, text: print(text, flush=True))
    script.load()
    print("hooks live", flush=True)
    while True:
        time.sleep(1)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(0)
