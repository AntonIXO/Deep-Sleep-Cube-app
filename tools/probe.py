#!/usr/bin/env python3
"""Live BLE probe for a Deep sleep cube. Uses the venv at re/venv."""
from __future__ import annotations

import argparse
import asyncio
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "re" / "venv" / "lib"))

from bleak import BleakClient, BleakScanner

ADDR_DEFAULT = "34:5F:45:36:E3:8E"
CMD = "98658f3a-e4e3-4ac4-8e14-b1b7ff024353"
STATUS = "98658f39-e4e3-4ac4-8e14-b1b7ff024353"
DATA = "9b5bfb13-20d7-4730-aa0c-993ea99fd4cd"


def hx(b: bytes) -> str:
    return b.hex(" ")


async def wait_device(addr: str, timeout: int = 45):
    found = None

    def cb(d, adv):
        nonlocal found
        name = d.name or adv.local_name or ""
        if d.address.upper() == addr.upper() or "deep" in name.lower():
            print(f"ADV {d.address} {name!r} rssi={adv.rssi}")
            found = d

    async with BleakScanner(cb):
        for i in range(timeout):
            if found:
                return found
            await asyncio.sleep(1)
            if i % 8 == 7:
                print(f"scan {i+1}s")
    return None


async def main():
    p = argparse.ArgumentParser()
    p.add_argument("--addr", default=ADDR_DEFAULT)
    p.add_argument("--write-cmd", help="hex bytes to command char")
    p.add_argument("--write-data", help="hex bytes to data char")
    p.add_argument("--get-reg", type=int)
    p.add_argument("--restore-led", action="store_true")
    args = p.parse_args()

    dev = await wait_device(args.addr)
    if not dev:
        print("cube not advertising")
        return 1

    def on_n(sender, data):
        print(f"N {sender} {hx(data)} {list(data)}")

    async with BleakClient(dev, timeout=20) as client:
        for u in (STATUS, CMD, DATA):
            await client.start_notify(u, on_n)
        st = await client.read_gatt_char(STATUS)
        print("STATUS", hx(st), list(st))

        async def wcmd(b: bytes):
            b = (b + bytes(8))[:8]
            print("CMD", hx(b))
            await client.write_gatt_char(CMD, b, response=True)
            await asyncio.sleep(0.4)
            print("STATUS", hx(await client.read_gatt_char(STATUS)))

        async def wdat(b: bytes):
            print("DAT", hx(b))
            await client.write_gatt_char(DATA, b, response=True)
            await asyncio.sleep(0.4)
            print("STATUS", hx(await client.read_gatt_char(STATUS)))

        if args.restore_led:
            await wcmd(bytes([0, 1]))
            await wcmd(bytes([1, 100]))
            await wcmd(bytes([2, 1]))
            await wcmd(bytes([3, 3]))
        if args.get_reg is not None:
            await wdat(bytes([0, args.get_reg & 0xFF]))
        if args.write_cmd:
            await wcmd(bytes.fromhex(args.write_cmd))
        if args.write_data:
            await wdat(bytes.fromhex(args.write_data))
        if not any([args.restore_led, args.get_reg is not None, args.write_cmd, args.write_data]):
            print("connected. pass --write-cmd/--write-data/--get-reg/--restore-led")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
