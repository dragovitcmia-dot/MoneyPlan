#!/usr/bin/env python3
"""Walks the Cashflow Lab build on the emulator: checks its amber LAB tag (gate), loads two scenarios through the Lab card and
photographs what they are for: the Upcoming tabs and the Plan a purchase result strip months away.

usage: lab_smoke.py [output_dir]
Exit code 3: the LAB tag was not shown (gating). Exit code 1: a step of the best-effort walk failed (not a build failure).
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
import smoke  # noqa: E402  (module-level setup only; main() is not run on import)

smoke.PKG = "com.cashflow.app.lab"
S = smoke.S
NotFound = smoke.NotFound
LAB_TITLE_UPCOMING = "Upcoming tabs"
LAB_TITLE_PURCHASE = "Plan a purchase months away"


def center(b):
    return (b[0] + b[2]) // 2, (b[1] + b[3]) // 2


def tap_box(b):
    x, y = center(b)
    smoke.sh(f"input tap {x} {y}")
    time.sleep(0.8)


def load_scenario(title):
    """Settings > Lab card: scrolls (slow drags) until the scenario's title is on screen and taps the first Load below it."""
    smoke.tap_text(smoke.TEXT_SETTINGS, scroll=False)
    time.sleep(1)
    for _ in range(45):
        nodes = smoke.dump_nodes() or []
        smoke.dismiss_system_dialog(nodes)
        titles = [b for t, d, b in nodes if t.strip().startswith(title)]
        if titles:
            loads = sorted([b for t, d, b in nodes if t.strip() == "Load" and b[1] >= titles[0][1]], key=lambda b: b[1])
            if loads:
                _, h = smoke.screen_size(nodes)
                if loads[0][3] < h * 0.85:
                    tap_box(loads[0])
                    time.sleep(2.5)
                    return
        if nodes:
            w, h = smoke.screen_size(nodes)
            smoke.sh(f"input swipe {w // 2} {int(h * 0.7)} {w // 2} {int(h * 0.4)} 900")
            time.sleep(0.5)
    raise NotFound(f"scenario {title!r} or its Load button never appeared in the Lab card")


def close_checklist(name):
    smoke.find("Close", scroll=False, timeout=15)
    smoke.screenshot(name)
    smoke.tap_text("Close", scroll=False)
    time.sleep(1)


def tabs_walk(name):
    load_scenario(LAB_TITLE_UPCOMING)
    close_checklist(f"{name}-lab-checklist")
    smoke.tap_text(smoke.NAV_UPCOMING, scroll=False)
    time.sleep(1.5)
    smoke.scroll_to_top(15)
    smoke.screenshot(f"{name}-lab-upcoming-all")
    smoke.tap_text(S["filter_expenses"], scroll=False)
    time.sleep(1.2)
    smoke.screenshot(f"{name}-lab-upcoming-expenses")
    smoke.tap_text(S["filter_income"], scroll=False)
    time.sleep(1.2)
    smoke.screenshot(f"{name}-lab-upcoming-income")
    smoke.tap_text(S["filter_repeating"], scroll=False)  # the repeat toggle: the series of the Income tab
    time.sleep(1.5)
    smoke.screenshot(f"{name}-lab-upcoming-income-series")
    smoke.tap_text(S["filter_repeating"], scroll=False)
    smoke.tap_text(S["filter_all"], scroll=False)
    time.sleep(1)


def purchase_walk(name):
    load_scenario(LAB_TITLE_PURCHASE)
    close_checklist(f"{name}-lab-checklist-purchase")
    smoke.tap_text(smoke.TEXT_HOME, scroll=False)
    time.sleep(1.5)
    smoke.open_plan_wizard()
    smoke.find("1", scroll=False, last=True)
    for digit in ("1", "2", "0", "0"):
        smoke.tap_text(digit, last=True, scroll=False)
    smoke.tap_text(S["wizard_next"], scroll=False)
    time.sleep(1.5)
    smoke.sh("input text Phone")
    smoke.finish_name_step(S["plan_it"])
    time.sleep(2)
    smoke.screenshot(f"{name}-lab-plan-far-result")
    problems = []
    try:
        smoke.find("Buy on 01 FEB 2027", scroll=False, timeout=6, contains=True)
    except NotFound as e:
        problems.append(f"the far purchase label 'Buy on 01 FEB 2027' was not shown ({e})")
    try:
        smoke.tap_text("2", scroll=False, timeout=4)  # the 2-part chip
        time.sleep(1.5)
        smoke.screenshot(f"{name}-lab-plan-far-result-2")
    except NotFound:
        pass
    smoke.sh("input keyevent 4")
    time.sleep(1.2)
    try:
        smoke.find(S["wizard_keep_going"], scroll=False, timeout=8)
        smoke.tap_text(S["wizard_discard"], scroll=False)
    except NotFound:
        pass
    time.sleep(1)
    return problems


def main():
    name = "dark-720"
    problems = []
    smoke.configure(True, "1.0", "720x1560", "320")
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
        smoke.screenshot(f"{name}-lab-home")
        for walk in (tabs_walk, purchase_walk):
            try:
                result = walk(name)
                problems += result or []
            except NotFound as e:
                smoke.screenshot(f"{name}-lab-{walk.__name__}-FAILED")
                print(f"  {walk.__name__}:", e)
                problems.append(f"{walk.__name__}: {e}")
                smoke.recover_to_home()
    finally:
        smoke.restore()
    if problems:
        print("\nLAB SMOKE (best effort) problems:")
        for p in problems:
            print(" -", p)
        sys.exit(1)
    print("\nLAB SMOKE OK")


if __name__ == "__main__":
    main()
