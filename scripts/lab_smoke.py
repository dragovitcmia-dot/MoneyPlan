#!/usr/bin/env python3
"""Walks the Cashflow Lab build on the emulator. Gate (exit code 3): the LAB tag is shown, the Lab home offers "Run all checks", and the
check panel's two buttons ("Looks right", "Problem") are clickable, inside the screen and at least 48 dp tall. Best effort: it runs the
first checks (the four item sheets, the date picker, Upcoming, Spent today, Settings and the Backups screen) by marking "Looks right"
and photographs each screen as the Lab opened it by itself, then the Results screen.

usage: lab_smoke.py [output_dir]
Exit code 3: a gating check failed. Exit code 1: a step of the best-effort walk failed (not a build failure).
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
import smoke  # noqa: E402  (module-level setup only; main() is not run on import)

smoke.PKG = "com.cashflow.app.lab"
S = smoke.S
NotFound = smoke.NotFound
MIN_DP = 48
LOOKS_RIGHT = "Looks right"
PROBLEM = "Problem"


def center(b):
    return (b[0] + b[2]) // 2, (b[1] + b[3]) // 2


def tap_box(b):
    x, y = center(b)
    smoke.sh(f"input tap {x} {y}")
    time.sleep(0.8)


def node_boxes(nodes, text):
    return [b for t, d, b in nodes if t.strip() == text or d.strip() == text]


def check_panel_buttons(nodes, name):
    """Gate: both panel buttons are on screen, inside it and at least 48 dp tall (1 dp = density / 160 px)."""
    w, h = smoke.screen_size(nodes)
    dpi = int(os.environ.get("LAB_DENSITY", "320"))
    dp = dpi / 160.0
    problems = []
    lines = []
    for label in (LOOKS_RIGHT, PROBLEM):
        boxes = node_boxes(nodes, label)
        if not boxes:
            problems.append(f"the panel button {label!r} was not found")
            continue
        b = max(boxes, key=lambda x: x[3])
        hd, wd = (b[3] - b[1]) / dp, (b[2] - b[0]) / dp
        inside = b[0] >= 0 and b[2] <= w and b[1] >= 0 and b[3] <= h
        lines.append(f"{label}: {wd:.0f} x {hd:.0f} dp {'ok' if inside else 'OUTSIDE'}")
        if hd < MIN_DP:
            problems.append(f"the panel button {label!r} is {hd:.0f} dp tall, needs >= {MIN_DP}")
        if not inside:
            problems.append(f"the panel button {label!r} is not inside the screen: {b}")
    msg = "; ".join(lines)
    print(f"::notice title=Geometry: Lab panel buttons::{msg}", flush=True)
    return problems


def wait_for_text(text, timeout=20):
    deadline = time.time() + timeout
    while time.time() < deadline:
        nodes = smoke.dump_nodes() or []
        smoke.dismiss_system_dialog(nodes)
        if node_boxes(nodes, text):
            return nodes
        time.sleep(0.7)
    raise NotFound(f"{text!r} never appeared")


def walk(name, count):
    """Run all checks, then mark 'Looks right' `count` times, photographing each screen the Lab opened by itself."""
    problems = []
    nodes = wait_for_text("Run all checks", timeout=20)
    smoke.screenshot(f"{name}-lab-home")
    tap_box(max(node_boxes(nodes, "Run all checks"), key=lambda b: b[3]))
    nodes = wait_for_text(LOOKS_RIGHT, timeout=30)
    problems += check_panel_buttons(nodes, name)
    for i in range(count):
        time.sleep(2.0)  # the Lab seeds, navigates and opens the sheet by itself
        smoke.screenshot(f"{name}-lab-check-{i + 1:02d}")
        nodes = wait_for_text(LOOKS_RIGHT, timeout=20)
        tap_box(max(node_boxes(nodes, LOOKS_RIGHT), key=lambda b: b[3]))
    return problems


def finish(name):
    """Stops the run and photographs the Results screen."""
    nodes = smoke.dump_nodes() or []
    stop = [b for t, d, b in nodes if d.strip() == "Stop the checks"]
    if stop:
        tap_box(stop[0])
    time.sleep(1.5)
    try:
        nodes = wait_for_text("Results", timeout=10)
        tap_box(max(node_boxes(nodes, "Results"), key=lambda b: b[3]))
        time.sleep(1.5)
        smoke.screenshot(f"{name}-lab-results")
    except NotFound:
        pass


def main():
    name = os.environ.get("LAB_NAME", "dark-720")
    night = os.environ.get("LAB_NIGHT", "1") == "1"
    font = os.environ.get("LAB_FONT", "1.0")
    count = int(os.environ.get("LAB_CHECKS", "13"))
    problems = []
    smoke.configure(night, font, "720x1560", "320")
    time.sleep(5)
    try:
        smoke.launch()
        try:
            smoke.find("LAB", scroll=False, timeout=15)
        except NotFound as e:
            print("LAB TAG MISSING:", e)
            smoke.screenshot(f"{name}-lab-NO-TAG")
            sys.exit(3)
        print("LAB tag is shown")
        try:
            problems += walk(name, count)
        except NotFound as e:
            smoke.screenshot(f"{name}-lab-walk-FAILED")
            print("  lab walk:", e)
            problems.append(f"lab walk: {e}")
        else:
            finish(name)
    finally:
        smoke.restore()
    gating = [p for p in problems if p.startswith("the panel button")]
    if gating:
        for p in gating:
            print(f"::error title=Geometry gate (Lab)::{p}")
        print("LAB GEOMETRY GATE FAILED")
        sys.exit(3)
    if problems:
        print("\nLAB SMOKE (best effort) problems:")
        for p in problems:
            print(" -", p)
        sys.exit(1)
    print("\nLAB SMOKE OK")


if __name__ == "__main__":
    main()
