#!/usr/bin/env python3
"""Walks the Cashflow Lab build on the emulator. Gate (exit code 3): the LAB tag is shown, the Lab home offers "Check this update", and the
check panel's two buttons ("Looks right", "Problem") are clickable, inside the screen and at least 48 dp tall. Best effort, in two walks:
"Check this update" (this release's few screens, every one photographed, then the Results screen), and then EVERY scenario from the
collapsed "Older checks" list ("Run all checks"), marked "Looks right" to the end, photographing only the first few.

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


def wait_for_any(texts, timeout=40):
    """The first of [texts] to show up on screen: (text, nodes)."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        nodes = smoke.dump_nodes() or []
        smoke.dismiss_system_dialog(nodes)
        for text in texts:
            if node_boxes(nodes, text):
                return text, nodes
        time.sleep(0.7)
    raise NotFound(f"none of {texts!r} appeared")


RESULTS_MARK = "Clear results"  # a button only the Results screen has: the run is over


def mark_through(name, prefix, shots, limit=120):
    """Marks 'Looks right' on every screen until the run ends (the Results screen appears), photographing the first [shots] screens.
    Returns how many screens it marked."""
    marked = 0
    while marked < limit:
        try:
            # The panel shows "Preparing the data and the screen" (no buttons) while the Lab seeds, navigates and opens the sheet by itself.
            found, nodes = wait_for_any([LOOKS_RIGHT, RESULTS_MARK], timeout=40)
            if found == RESULTS_MARK:
                return marked
            if marked < shots:
                time.sleep(1.5)  # the sheet or picker has finished opening
                smoke.screenshot(f"{name}-{prefix}-{marked + 1:02d}")
            else:
                time.sleep(0.4)
        except NotFound:
            # The Move date picker is a dialog of its own: its check panel is a small window at the top of the screen that the
            # accessibility dump does not list. Photograph it, close the picker with Back and answer on the item sheet below.
            smoke.screenshot(f"{name}-{prefix}-{marked + 1:02d}-picker")
            smoke.sh("input keyevent 4")
            time.sleep(1.0)
            wait_for_text(LOOKS_RIGHT, timeout=20)
        nodes = wait_for_text(LOOKS_RIGHT, timeout=20)
        tap_box(max(node_boxes(nodes, LOOKS_RIGHT), key=lambda b: b[3]))
        marked += 1
        time.sleep(0.8)  # the panel switches to "Preparing" right after the tap
    return marked


def walk(name, shots):
    """Walk 1: 'Check this update' (every screen photographed, then the Results screen); walk 2: 'Older checks' > 'Run all checks'
    through every scenario. Returns the gating problems of the panel buttons (measured on the first panel, before anything else can go wrong)."""
    nodes = wait_for_text("Check this update", timeout=20)
    smoke.screenshot(f"{name}-lab-home")
    tap_box(max(node_boxes(nodes, "Check this update"), key=lambda b: b[3]))
    wait_for_text(LOOKS_RIGHT, timeout=40)
    gate = check_panel_buttons()
    if gate:
        return gate
    n = mark_through(name, "lab-update", shots=20)
    print(f"  'Check this update': {n} screens marked")
    time.sleep(1.5)
    smoke.screenshot(f"{name}-lab-results")  # the Results screen shows only this update's checks
    nodes = wait_for_text("Lab home", timeout=10)
    tap_box(max(node_boxes(nodes, "Lab home"), key=lambda b: b[3]))
    nodes = wait_for_text("Older checks", timeout=15)
    older = [b for t, d, b in nodes if t.strip().startswith("Older checks")]
    for _ in range(4):  # the row is the last on the home: scroll to it (large fonts push it off screen)
        if older:
            break
        smoke.sh("input swipe 360 1250 360 450 300")
        time.sleep(0.8)
        nodes = smoke.dump_nodes() or []
        older = [b for t, d, b in nodes if t.strip().startswith("Older checks")]
    if not older:
        raise NotFound("'Older checks' is not on the Lab home")
    tap_box(max(older, key=lambda b: b[3]))
    time.sleep(0.8)
    smoke.screenshot(f"{name}-lab-older-open")
    for _ in range(4):  # the button is right under the row; scroll if it fell off screen
        nodes = smoke.dump_nodes() or []
        if node_boxes(nodes, "Run all checks"):
            break
        smoke.sh("input swipe 360 1250 360 450 300")
        time.sleep(0.8)
    nodes = wait_for_text("Run all checks", timeout=10)
    tap_box(max(node_boxes(nodes, "Run all checks"), key=lambda b: b[3]))
    wait_for_text(LOOKS_RIGHT, timeout=40)
    n = mark_through(name, "lab-all", shots=shots)
    print(f"  'Run all checks': {n} screens marked")
    return []


def finish(name):
    """Stops a run that is still going (a walk that ended early); the Results screen was photographed after the first walk."""
    nodes = smoke.dump_nodes() or []
    stop = [b for t, d, b in nodes if d.strip() == "Stop the checks"]
    if stop:
        tap_box(stop[0])
    time.sleep(1.0)


def main():
    name = os.environ.get("LAB_NAME", "dark-720")
    night = os.environ.get("LAB_NIGHT", "1") == "1"
    font = os.environ.get("LAB_FONT", "1.0")
    count = int(os.environ.get("LAB_CHECKS", "6"))  # photographs of the full "Run all checks" walk (which marks every screen)
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
