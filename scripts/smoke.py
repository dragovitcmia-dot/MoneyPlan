#!/usr/bin/env python3
"""adb-driven smoke test: walks through the app in several theme / font / screen configurations,
finds buttons by their visible text (read from strings.xml) and saves a screenshot at each step.

usage: smoke.py [output_dir]      (an emulator or device must be connected)
Exit code 1 if a node was not found within 10 s, the app died, or a crash was logged.
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = "com.cashflow.app"
OUT = sys.argv[1] if len(sys.argv) > 1 else "screens"
STRINGS_XML = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "values", "strings.xml")
FIND_TIMEOUT = 10.0

CONFIGS = [
    # name, night mode, font scale, wm size override, wm density override
    ("dark-normal", True, "1.0", None, None),
    ("light-normal", False, "1.0", None, None),
    ("dark-font2", True, "2.0", None, None),
    ("light-font1.3", False, "1.3", None, None),
    ("dark-320dp", True, "1.0", "640x1136", "320"),
]


def strings():
    root = ET.parse(STRINGS_XML).getroot()
    return {e.get("name"): (e.text or "") for e in root.findall("string")}


S = strings()
TEXT_SETTINGS = S["nav_settings"]
TEXT_HOME = S["nav_home"]
TEXT_LOAD_SAMPLE = S["debug_load_sample"]
TEXT_EXPENSE = S["button_expense"]


def adb(*args, check=True):
    r = subprocess.run(["adb", *args], capture_output=True, text=True)
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {r.stderr.strip() or r.stdout.strip()}")
    return r.stdout


def sh(cmd, check=True):
    return adb("shell", cmd, check=check)


def dump_nodes():
    """Returns [(text, content_desc, (x1, y1, x2, y2))] for the current screen, or None if the dump failed."""
    for _ in range(3):
        out = sh("uiautomator dump /sdcard/ui.xml", check=False)
        if "ERROR" in out and "dumped" not in out:
            time.sleep(0.7)
            continue
        xml = adb("exec-out", "cat", "/sdcard/ui.xml", check=False)
        try:
            root = ET.fromstring(xml)
        except ET.ParseError:
            time.sleep(0.7)
            continue
        nodes = []
        for n in root.iter("node"):
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
            if m:
                nodes.append((n.get("text", ""), n.get("content-desc", ""), tuple(int(v) for v in m.groups())))
        return nodes
    return None


def screen_size(nodes):
    return max(b[2] for _, _, b in nodes), max(b[3] for _, _, b in nodes)


def screenshot(name):
    os.makedirs(OUT, exist_ok=True)
    png = subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True).stdout
    with open(os.path.join(OUT, name + ".png"), "wb") as f:
        f.write(png)
    print(f"  screenshot {name}.png ({len(png)} bytes)")


class NotFound(Exception):
    pass


def find(text, scroll=True, last=False):
    """Waits up to 10 s for a node with exactly this text; scrolls down while it is missing."""
    deadline = time.time() + FIND_TIMEOUT
    last_nodes = None
    while True:
        nodes = dump_nodes()
        if nodes:
            last_nodes = nodes
            hits = [b for t, d, b in nodes if t.strip() == text or d.strip() == text]
            if hits:
                return sorted(hits, key=lambda b: (b[1], b[0]))[-1 if last else 0]
            if scroll:
                w, h = screen_size(nodes)
                sh(f"input swipe {w // 2} {int(h * 0.75)} {w // 2} {int(h * 0.35)} 300")
        if time.time() > deadline:
            visible = [(t or d) for t, d, _ in (last_nodes or []) if (t or d)]
            raise NotFound(f"text {text!r} not found within {FIND_TIMEOUT:.0f}s; visible texts: {visible}")
        time.sleep(0.5)


def tap_text(text, **kw):
    x1, y1, x2, y2 = find(text, **kw)
    sh(f"input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")
    time.sleep(0.8)


def configure(night, font, size, density):
    sh(f"cmd uimode night {'yes' if night else 'no'}")
    sh(f"settings put system font_scale {font}")
    if size:
        sh(f"wm size {size}")
        sh(f"wm density {density}")
    else:
        sh("wm size reset")
        sh("wm density reset")
    time.sleep(2)


def restore():
    sh("cmd uimode night no", check=False)
    sh("settings put system font_scale 1.0", check=False)
    sh("wm size reset", check=False)
    sh("wm density reset", check=False)


def app_alive():
    return bool(sh(f"pidof {PKG}", check=False).strip())


def crash_log():
    log = adb("logcat", "-d", check=False)
    hits = []
    lines = log.splitlines()
    for i, line in enumerate(lines):
        if "FATAL EXCEPTION" in line or " E AndroidRuntime" in line:
            block = "\n".join(lines[i:i + 6])
            if PKG in block:
                hits.append(block)
    return hits


def run_config(name, night, font, size, density):
    print(f"== {name}: night={night} font={font} size={size or 'default'}")
    configure(night, font, size, density)
    sh(f"am force-stop {PKG}")
    sh(f"pm clear {PKG}")
    adb("logcat", "-c", check=False)
    sh(f"monkey -p {PKG} -c android.intent.category.LAUNCHER 1")
    time.sleep(3)
    try:
        tap_text(TEXT_SETTINGS, scroll=False)
        tap_text(TEXT_LOAD_SAMPLE)  # scrolls down to the debug section
        time.sleep(2.5)
        tap_text(TEXT_HOME, scroll=False)
        time.sleep(4)  # the count-up finishes
        screenshot(f"{name}-home")
        tap_text(TEXT_EXPENSE)
        find("1", scroll=True, last=True)
        time.sleep(1)
        screenshot(f"{name}-composer")
        for digit in ("1", "0"):
            tap_text(digit, last=True)
        time.sleep(0.5)
        screenshot(f"{name}-composer-typed")
        sh("input keyevent 4")  # back: closes the composer
        time.sleep(1.5)
        tap_text(TEXT_SETTINGS, scroll=False)
        time.sleep(1)
        screenshot(f"{name}-settings")
    except NotFound as e:
        screenshot(f"{name}-FAILED")
        print("  NOT FOUND:", e)
        nodes = dump_nodes() or []
        print("  dump:", [(t, d, b) for t, d, b in nodes if t or d][:80])
        return [f"{name}: {e}"]
    problems = []
    if not app_alive():
        problems.append(f"{name}: app process is not alive")
    problems += [f"{name}: crash in log:\n{c}" for c in crash_log()]
    return problems


def main():
    problems = []
    try:
        for cfg in CONFIGS:
            problems += run_config(*cfg)
    finally:
        restore()
    if problems:
        print("\nSMOKE TEST FAILED:")
        for p in problems:
            print(" -", p)
        sys.exit(1)
    print("\nSMOKE TEST OK")


if __name__ == "__main__":
    main()
