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


def check_panel_buttons():
    """Gate: both panel buttons are on screen, inside it and at least 48 dp tall. The CLICKABLE button is measured (not its label)."""
    import geometry  # measured the same way as the real app's gate (clickable ancestor of the label)
    W, H, dpi = geometry.display()
    geometry.SCREEN_W = W
    dp = dpi / 160.0
    nodes = geometry.dump()
    problems = []
    lines = []
    for label in (LOOKS_RIGHT, PROBLEM):
        hits = [n for n in nodes if n.text == label or n.desc == label]
        if not hits:
            problems.append(f"the panel button {label!r} was not found")
            continue
        btn = geometry.clickable_of(max(hits, key=lambda n: n.b[3]))
        hd, wd = btn.h / dp, btn.w / dp
        inside = btn.b[0] >= 0 and btn.b[2] <= W and btn.b[1] >= 0 and btn.b[3] <= H
        lines.append(f"{label}: {wd:.0f} x {hd:.0f} dp clickable={btn.clickable} {'ok' if inside else 'OUTSIDE'}")
        if not btn.clickable:
            problems.append(f"the panel button {label!r} is not clickable")
        if hd < MIN_DP:
            problems.append(f"the panel button {label!r} is {hd:.0f} dp tall, needs >= {MIN_DP}")
        if wd < MIN_DP:
            problems.append(f"the panel button {label!r} is {wd:.0f} dp wide, needs >= {MIN_DP}")
        if not inside:
            problems.append(f"the panel button {label!r} is not inside the screen: {btn.b}")
    msg = "; ".join(lines)
    print("LAB PANEL BUTTONS:", msg, flush=True)
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
    """Run all checks, then mark 'Looks right' `count` times, photographing each screen the Lab opened by itself.
    Returns the gating problems of the panel buttons (measured on the first panel, before anything else can go wrong)."""
    nodes = wait_for_text("Run all checks", timeout=20)
    smoke.screenshot(f"{name}-lab-home")
    tap_box(max(node_boxes(nodes, "Run all checks"), key=lambda b: b[3]))
    wait_for_text(LOOKS_RIGHT, timeout=40)
    gate = check_panel_buttons()
    if gate:
        return gate
    for i in range(count):
        # The panel shows "Preparing the data and the screen" (no buttons) while the Lab seeds, navigates and opens the sheet by itself.
        try:
            wait_for_text(LOOKS_RIGHT, timeout=40)
            time.sleep(1.5)  # the sheet or picker has finished opening
            smoke.screenshot(f"{name}-lab-check-{i + 1:02d}")
        except NotFound:
            # The Move date picker is a dialog of its own: its check panel is a small window at the top of the screen that the
            # accessibility dump does not list. Photograph it, close the picker with Back and answer on the item sheet below.
            smoke.screenshot(f"{name}-lab-check-{i + 1:02d}-picker")
            smoke.sh("input keyevent 4")
            time.sleep(1.0)
            wait_for_text(LOOKS_RIGHT, timeout=20)
        nodes = wait_for_text(LOOKS_RIGHT, timeout=20)
        tap_box(max(node_boxes(nodes, LOOKS_RIGHT), key=lambda b: b[3]))
        time.sleep(1.0)  # the panel switches to "Preparing" right after the tap
    return []


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
    count = int(os.environ.get("LAB_CHECKS", "20"))
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
            gate = walk(name, count)
            problems += gate
            if gate:
                smoke.screenshot(f"{name}-lab-GATE-FAILED")
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
