#!/usr/bin/env python3
"""Geometry gate (runs on the emulator right after the launch check, takes seconds).

Dumps the UI with uiautomator and asserts measured sizes, not intentions:
  1. every bottom-navigation item is at least 48 dp tall and at least 20 % of the screen wide, and lies on screen;
  2. with the Add Expense wizard open on its Amount step, every clickable control lies fully inside the screen and
     above the system navigation bar, and the primary Next button is fully visible.
Measured sizes are printed and sent to the build annotations (::notice::). Exit code 1 on any violation.
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

STRINGS_XML = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "values", "strings.xml")
MIN_NAV_HEIGHT_DP = 48
MIN_NAV_WIDTH_SHARE = 0.20
MIN_BUTTON_HEIGHT_DP = 40


def adb(*args):
    return subprocess.run(["adb", *args], capture_output=True, text=True).stdout


def sh(cmd):
    return adb("shell", cmd)


def strings():
    root = ET.parse(STRINGS_XML).getroot()
    return {e.get("name"): (e.text or "") for e in root.findall("string")}


S = strings()


class Node:
    def __init__(self, el, parent):
        self.el = el
        self.parent = parent
        m = re.match(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", el.get("bounds", ""))
        self.b = tuple(int(v) for v in m.groups()) if m else (0, 0, 0, 0)
        self.text = el.get("text", "").strip()
        self.desc = el.get("content-desc", "").strip()
        self.clickable = el.get("clickable") == "true"
        self.cls = el.get("class", "")

    @property
    def w(self):
        return self.b[2] - self.b[0]

    @property
    def h(self):
        return self.b[3] - self.b[1]

    def label(self):
        return self.text or self.desc or self.cls.split(".")[-1]


def dump():
    for _ in range(4):
        out = sh("uiautomator dump /sdcard/geo.xml")
        xml = adb("exec-out", "cat", "/sdcard/geo.xml")
        try:
            root = ET.fromstring(xml)
        except ET.ParseError:
            time.sleep(1)
            continue
        nodes = []

        def walk(el, parent):
            n = Node(el, parent) if el.tag == "node" else None
            for c in el:
                walk(c, n if n else parent)
            if n:
                nodes.append(n)

        walk(root, None)
        # An emulator system box ("Pixel Launcher isn't responding") hides the app: press Wait and look again.
        if any("responding" in n.text.lower() for n in nodes):
            for n in nodes:
                if n.text == "Wait":
                    sh(f"input tap {(n.b[0] + n.b[2]) // 2} {(n.b[1] + n.b[3]) // 2}")
                    time.sleep(1.5)
            continue
        return nodes
    return []


def wait_for(text, timeout=20):
    deadline = time.time() + timeout
    nodes = []
    while time.time() < deadline:
        nodes = dump()
        hits = [n for n in nodes if n.text == text or n.desc == text]
        if hits:
            return nodes, hits
        time.sleep(1)
    texts = [n.label() for n in nodes if n.text or n.desc][:60]
    raise SystemExit(f"GEOMETRY FAILED: {text!r} never appeared. Visible: {texts}")


def clickable_of(n):
    cur = n
    while cur is not None:
        if cur.clickable:
            return cur
        cur = cur.parent
    return n


def display():
    out = sh("wm size")
    m = re.findall(r"(\d+)x(\d+)", out)
    w, h = (int(v) for v in m[-1])
    d = re.findall(r"(\d+)", sh("wm density"))
    return w, h, int(d[-1])


def nav_inset(h):
    out = sh("dumpsys window")
    m = re.findall(r"type=navigationBars frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]", out)
    insets = [int(b) - int(a) for _, a, _, b in m if int(b) - int(a) > 0]
    return max(insets) if insets else 0


def notice(title, message):
    print(f"::notice title={title}::{message}", flush=True)


def main():
    W, H, dpi = display()
    dp = dpi / 160.0
    inset = nav_inset(H)
    print(f"screen {W}x{H} px, {dpi} dpi (1 dp = {dp:.2f} px), navigation bar inset {inset} px ({inset / dp:.0f} dp)")
    problems = []

    # 1. bottom navigation
    labels = [S["nav_home"], S["nav_calendar"], S["nav_upcoming"], S["nav_settings"]]
    nodes, _ = wait_for(S["nav_settings"])
    measured = []
    for label in labels:
        hits = [n for n in nodes if n.text == label or n.desc == label]
        if not hits:
            problems.append(f"nav item {label!r} not found")
            continue
        # the bottom-most match is the bar (a screen title may carry the same word)
        item = clickable_of(max(hits, key=lambda n: n.b[3]))
        hd, wd = item.h / dp, item.w / dp
        measured.append(f"{label}: {wd:.0f} x {hd:.0f} dp ({item.w} x {item.h} px, {item.w / W:.0%} of width, clickable={item.clickable})")
        if not item.clickable:
            problems.append(f"nav item {label!r}: no clickable node around it")
        if hd < MIN_NAV_HEIGHT_DP:
            problems.append(f"nav item {label!r} is {hd:.0f} dp tall, needs >= {MIN_NAV_HEIGHT_DP}")
        if item.w < MIN_NAV_WIDTH_SHARE * W:
            problems.append(f"nav item {label!r} is {item.w / W:.0%} of the screen width, needs >= {MIN_NAV_WIDTH_SHARE:.0%}")
        if item.b[0] < 0 or item.b[2] > W or item.b[1] < 0 or item.b[3] > H:
            problems.append(f"nav item {label!r} is off screen: {item.b}")
    print("NAV ITEMS:\n  " + "\n  ".join(measured))
    notice("Geometry: bottom navigation", " | ".join(measured))

    # 2. Add Expense wizard, Amount step
    nodes, hits = wait_for(S["button_expense"])
    target = clickable_of(hits[0])
    sh(f"input tap {(target.b[0] + target.b[2]) // 2} {(target.b[1] + target.b[3]) // 2}")
    nodes, next_hits = wait_for(S["wizard_next"], timeout=15)
    time.sleep(1)
    nodes = dump()
    limit = H - inset
    controls = []
    for n in nodes:
        if not n.clickable or n.w <= 0 or n.h <= 0:
            continue
        if n.w >= 0.9 * W and n.h >= 0.5 * H:  # the dimmed backdrop behind the sheet, not a button
            continue
        controls.append(n)
    lines = []
    for n in sorted(controls, key=lambda n: (n.b[1], n.b[0])):
        inside = n.b[0] >= 0 and n.b[2] <= W and n.b[1] >= 0 and n.b[3] <= limit
        lines.append(f"{n.label()!r}: [{n.b[0]},{n.b[1]}][{n.b[2]},{n.b[3]}] {n.w / dp:.0f} x {n.h / dp:.0f} dp {'ok' if inside else 'OUTSIDE'}")
        if not inside:
            problems.append(f"wizard control {n.label()!r} {n.b} is not fully inside the screen above the navigation bar (limit y={limit})")
    print("WIZARD CONTROLS (Amount step):\n  " + "\n  ".join(lines))
    nxt = clickable_of(max((n for n in nodes if n.text == S["wizard_next"]), key=lambda n: n.b[3]))
    nxt_ok = nxt.b[0] >= 0 and nxt.b[2] <= W and nxt.b[1] >= 0 and nxt.b[3] <= limit and nxt.h / dp >= MIN_BUTTON_HEIGHT_DP
    msg = f"Next: [{nxt.b[0]},{nxt.b[1]}][{nxt.b[2]},{nxt.b[3]}] {nxt.w / dp:.0f} x {nxt.h / dp:.0f} dp, bottom edge {limit - nxt.b[3]} px above the navigation bar, {len(controls)} controls checked"
    print("NEXT BUTTON:", msg)
    notice("Geometry: wizard Amount step", msg)
    if not nxt_ok:
        problems.append(f"the Next button is not fully visible: {nxt.b} (limit y={limit}, height {nxt.h / dp:.0f} dp)")
    if len(controls) < 4:
        problems.append(f"only {len(controls)} controls found in the wizard dump: the check did not see the sheet")

    sh("input keyevent 4")  # an untouched wizard closes at once
    time.sleep(1.5)
    if problems:
        for p in problems:
            print(f"::error title=Geometry gate::{p}")
        print("GEOMETRY GATE FAILED")
        return 1
    print("GEOMETRY GATE OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
