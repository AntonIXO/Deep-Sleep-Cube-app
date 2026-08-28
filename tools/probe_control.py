#!/usr/bin/env python3
"""Brute-force Deep cube start/stop while watching the 12-byte status."""
from __future__ import annotations

import argparse
import asyncio
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "re" / "venv" / "lib"))

from bleak import BleakClient, BleakScanner

ADDR = "34:5F:45:36:E3:8E"
CMD = "98658f3a-e4e3-4ac4-8e14-b1b7ff024353"
STATUS = "98658f39-e4e3-4ac4-8e14-b1b7ff024353"
DATA = "9b5bfb13-20d7-4730-aa0c-993ea99fd4cd"


def hx(b: bytes) -> str:
    return b.hex(" ")


def parse_status(st: bytes) -> str:
    st = (st + bytes(12))[:12]
    running = st[0] != 0
    prog = st[1]
    freq = st[2]
    pwr = st[3]
    total = int.from_bytes(st[4:8], "little")
    elapsed = int.from_bytes(st[8:12], "little")
    return (
        f"{hx(st)} run={running} prog={prog} freq={freq:#x} pwr={pwr} "
        f"total={total}s elapsed={elapsed}s"
    )


async def find(addr: str):
    found = None

    def cb(d, adv):
        nonlocal found
        name = d.name or adv.local_name or ""
        if d.address.upper() == addr.upper() or "deep" in name.lower():
            print(f"ADV {d.address} {name!r} rssi={adv.rssi}")
            found = d

    async with BleakScanner(cb):
        for i in range(40):
            if found:
                return found
            await asyncio.sleep(0.5)
            if i % 8 == 7:
                print(f"scan {i}s")
    return None


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--addr", default=ADDR)
    ap.add_argument("--mode", choices=["watch", "stop", "start", "map"], default="map")
    ap.add_argument("--hours", type=int, default=9)
    args = ap.parse_args()

    dev = await find(args.addr)
    if not dev:
        print("cube not advertising — touch it")
        return 1

    notes = []

    def on_n(sender, data):
        u = str(sender).split(" ")[-1] if sender else ""
        short = u[0:8] if u else "?"
        line = f"N {short} {hx(data)}"
        if STATUS in u.lower() or "98658f39" in u.lower():
            line += "  " + parse_status(data)
        print(line)
        notes.append(data)

    async with BleakClient(dev, timeout=20) as client:
        for u in (STATUS, CMD, DATA):
            await client.start_notify(u, on_n)

        async def status():
            st = await client.read_gatt_char(STATUS)
            print("STATUS", parse_status(st))
            return st

        async def wcmd(b: bytes, wait=0.45):
            b = (b + bytes(8))[:8]
            print(f"CMD {hx(b)}")
            await client.write_gatt_char(CMD, b, response=True)
            await asyncio.sleep(wait)
            return await status()

        async def wdat(b: bytes, wait=0.45):
            print(f"DAT {hx(b)}")
            await client.write_gatt_char(DATA, b, response=True)
            await asyncio.sleep(wait)
            return await status()

        st = await status()
        running = st[0] != 0
        print("initial running", running)

        if args.mode == "watch":
            await asyncio.sleep(8)
            return 0

        dur = args.hours * 3600
        dur_le = dur.to_bytes(4, "little")

        if args.mode in ("stop", "map") and running:
            print("=== STOP search ===")
            stop_cmd = [
                bytes([0x04, 0x00]),
                bytes([0x05, 0x00]),
                bytes([0x06, 0x00]),
                bytes([0x07, 0x00]),
                bytes([0x08, 0x00]),
                bytes([0x02, 0x00]),
                bytes([0x03, 0x00]),
                bytes([0x01, 0x00]),
                bytes([0x10, 0x00]),
                bytes([0x10, 0x02]),
                bytes([0x20, 0x00]),
                bytes([0xAA, 0x00]),
                bytes([0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00]),
                bytes([0xFF]),
                bytes([0x0A]),
                bytes([0x0B]),
                bytes([0x0C]),
                bytes([0x0D]),
                bytes([0x0E]),
                bytes([0x0F]),
            ]
            stop_dat = [
                bytes([0x02]),
                bytes([0x03]),
                bytes([0x04]),
                bytes([0x05]),
                bytes([0x01, 0x00]),
                bytes([0x02, 0x00]),
                bytes([0x03, 0x00]),
                bytes([0x03, 0x01]),
                bytes([0x02, 0x01]),
                bytes([0x04, 0x00]),
                bytes([0x10, 0x00]),
                bytes([0x00, 0x04]),
                bytes([0x01, 0x04]),
            ]
            found = None
            for b in stop_cmd:
                st = await wcmd(b)
                if st[0] == 0:
                    print("*** STOP via CMD", hx(b))
                    found = ("cmd", b)
                    break
            if not found:
                for b in stop_dat:
                    st = await wdat(b)
                    if st[0] == 0:
                        print("*** STOP via DAT", hx(b))
                        found = ("dat", b)
                        break
            if not found:
                print("stop not found, sweeping cmd first-byte 0x11-0x30")
                for i in range(0x11, 0x31):
                    st = await wcmd(bytes([i, 0]))
                    if st[0] == 0:
                        print("*** STOP via CMD", hx(bytes([i, 0])))
                        found = ("cmd", bytes([i, 0]))
                        break
            if not found:
                print("STOP FAILED")
            running = (await status())[0] != 0

        if args.mode in ("start", "map") and not running:
            print("=== START search ===")
            prog = 1
            start_cmd = [
                bytes([0x04, prog]) + dur_le,
                bytes([0x05, prog]) + dur_le,
                bytes([0x06, prog]) + dur_le,
                bytes([0x04, 0x01]),
                bytes([0x05, 0x01]),
                bytes([0x01, prog]) + dur_le,
                bytes([0x10, prog]) + dur_le,
                bytes([prog]) + dur_le,
                bytes([0xAA, prog]) + dur_le,
                bytes([0x20, prog]) + dur_le,
                bytes([0x04, prog]) + (args.hours).to_bytes(2, "little"),
                bytes([0x04, prog, args.hours]),
                bytes([0x07, prog]) + dur_le,
                bytes([0x08, prog]) + dur_le,
            ]
            start_dat = [
                bytes([0x02, prog]) + dur_le,
                bytes([0x01, prog]) + dur_le,
                bytes([0x04, prog]) + dur_le,
                bytes([0x05, prog]) + dur_le,
                bytes([0x10, prog]) + dur_le,
                bytes([0x02, prog, args.hours]),
                bytes([0x01, 0x01, prog]) + dur_le,
                bytes([0x00, 0x10, prog]) + dur_le,
                bytes([0x02]) + bytes([prog]) + dur_le + bytes([3]),
            ]
            found = None
            for b in start_cmd:
                st = await wcmd(b)
                if st[0] != 0:
                    print("*** START via CMD", hx(b))
                    found = ("cmd", b)
                    break
            if not found:
                for b in start_dat:
                    st = await wdat(b)
                    if st[0] != 0:
                        print("*** START via DAT", hx(b))
                        found = ("dat", b)
                        break
            if not found:
                print("start not found, sweeping cmd op 0-32 with prog=1 and 9h")
                for i in range(0, 33):
                    if i in (0, 1, 2, 3):
                        continue
                    st = await wcmd(bytes([i, prog]) + dur_le)
                    if st[0] != 0:
                        print("*** START via CMD", hx(bytes([i, prog]) + dur_le))
                        found = ("cmd", bytes([i, prog]) + dur_le)
                        break
            if not found:
                print("START FAILED")

        await status()
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
