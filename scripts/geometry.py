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
MIN_NAV_WIDTH_SHARE = 0.19  # five items: a fifth each (rounding to whole pixels must not fail it)
MIN_NAV_WIDTH_DP = 48
MIN_BUTTON_HEIGHT_DP = 40
MIN_SETTINGS_ROW_DP = 56
SCREEN_W = 1080


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
        self.selected = el.get("selected") == "true"
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


def wait_until(pred, timeout=15.0, interval=0.5):
    """Dumps the UI every `interval` seconds, up to `timeout`, until pred(nodes) returns something truthy. Returns (nodes, result);
    result is None when the time ran out. A failed dump is retried by the next round (dump() itself retries too). Replaces a fixed
    wait followed by one dump, which flaked once on a slow emulator."""
    deadline = time.time() + timeout
    while True:
        nodes = dump()
        res = pred(nodes) if nodes else None
        if res:
            return nodes, res
        if time.time() >= deadline:
            return nodes, None
        time.sleep(interval)


def chain(n):
    out = []
    while n is not None:
        out.append(n)
        n = n.parent
    return out


def clickable_of(n):
    """The control around a label: the nearest clickable ancestor; for the SELECTED tab (which Android may report
    as not clickable, because tapping it again does nothing) the nearest selected ancestor."""
    for cur in chain(n):
        if cur.clickable:
            return cur
    for cur in chain(n):
        if cur.selected:
            return cur
    # Last resort: the largest ancestor that is still a single cell (narrower than half the screen).
    cells = [c for c in chain(n) if c.w * 2 <= SCREEN_W and c.w > 0]
    return max(cells, key=lambda c: c.w * c.h) if cells else n


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


def check_sheet(title, W, H, dp, inset):
    """With a wizard sheet open on its first step: every clickable control lies fully inside the screen and above the
    system navigation bar, and the primary Next button is fully visible and at least 40 dp tall."""
    problems = []
    nodes, _ = wait_for(S["wizard_next"], timeout=15)
    time.sleep(1)
    nodes = dump()
    limit = H - inset
    controls = [n for n in nodes if n.clickable and n.w > 0 and n.h > 0 and not (n.w >= 0.9 * W and n.h >= 0.5 * H)]
    lines = []
    for n in sorted(controls, key=lambda n: (n.b[1], n.b[0])):
        inside = n.b[0] >= 0 and n.b[2] <= W and n.b[1] >= 0 and n.b[3] <= limit
        lines.append(f"{n.label()!r}: [{n.b[0]},{n.b[1]}][{n.b[2]},{n.b[3]}] {n.w / dp:.0f} x {n.h / dp:.0f} dp {'ok' if inside else 'OUTSIDE'}")
        if not inside:
            problems.append(f"{title}: control {n.label()!r} {n.b} is not fully inside the screen above the navigation bar (limit y={limit})")
    print(f"{title.upper()} CONTROLS:\n  " + "\n  ".join(lines))
    nxt = clickable_of(max((n for n in nodes if n.text == S["wizard_next"]), key=lambda n: n.b[3]))
    nxt_ok = nxt.b[0] >= 0 and nxt.b[2] <= W and nxt.b[1] >= 0 and nxt.b[3] <= limit and nxt.h / dp >= MIN_BUTTON_HEIGHT_DP
    msg = f"Next: [{nxt.b[0]},{nxt.b[1]}][{nxt.b[2]},{nxt.b[3]}] {nxt.w / dp:.0f} x {nxt.h / dp:.0f} dp, bottom edge {limit - nxt.b[3]} px above the navigation bar, {len(controls)} controls checked"
    print(f"{title}: {msg}")
    notice(f"Geometry: {title}", msg)
    if not nxt_ok:
        problems.append(f"{title}: the Next button is not fully visible: {nxt.b} (limit y={limit}, height {nxt.h / dp:.0f} dp)")
    if len(controls) < 4:
        problems.append(f"{title}: only {len(controls)} controls found in the dump: the check did not see the sheet")
    return problems


def tap(n):
    sh(f"input tap {(n.b[0] + n.b[2]) // 2} {(n.b[1] + n.b[3]) // 2}")


def inside_screen(n, W, bottom):
    return n.b[0] >= 0 and n.b[2] <= W and n.b[1] >= 0 and n.b[3] <= bottom


def check_upcoming(W, H, dp, nav_top):
    """Loads the sample data (Settings > Developer tools), opens Upcoming and measures the tab row (three equal tabs and the
    repeat toggle at its end: at least 48 dp tall, inside the screen); then switches the toggle on and checks that the series rows
    are clickable and inside the screen, with the Income tab too."""
    problems = []
    nodes, hits = wait_for(S["nav_settings"])
    tap(clickable_of(max(hits, key=lambda n: n.b[3])))
    for _ in range(12):  # the developer tools card is at the bottom of Settings
        nodes = dump()
        load = [n for n in nodes if n.text == S["debug_load_sample"] or n.desc == S["debug_load_sample"]]
        if load:
            break
        sh(f"input swipe {W // 2} {int(H * 0.75)} {W // 2} {int(H * 0.30)} 300")
        time.sleep(0.5)
    else:
        return ["Settings: 'Load sample data' not found"]
    tap(clickable_of(load[0]))
    nodes, hits = wait_for(S["nav_upcoming"])
    tap(clickable_of(max(hits, key=lambda n: n.b[3])))

    labels = {"All": S["filter_all"], "Expenses": S["filter_expenses"], "Income": S["filter_income"]}
    def tab_row(nodes):
        found = {}
        for name, label in labels.items():
            hit = [n for n in nodes if (n.text == label or n.desc == label) and n.b[3] < nav_top]
            if hit:
                found[name] = min(hit, key=lambda n: n.b[1])
        toggle = [n for n in nodes if n.desc == S["filter_repeating"]]
        return (found, toggle[0]) if len(found) == 3 and toggle else None
    nodes, row = wait_until(tab_row, timeout=20)
    if row is None:
        return ["the Upcoming tab row (All, Expenses, Income and the repeat toggle) was not found"]
    found, toggle_node = row
    tabs = {name: clickable_of(n) for name, n in found.items()}
    toggle = clickable_of(toggle_node)
    lines = []
    for label, ctl in [*tabs.items(), ("Repeat toggle", toggle)]:
        inside = inside_screen(ctl, W, H)
        lines.append(f"{label}: {ctl.w / dp:.0f} x {ctl.h / dp:.0f} dp {'ok' if inside else 'OUTSIDE'}")
        if ctl.h / dp < MIN_NAV_HEIGHT_DP:
            problems.append(f"Upcoming {label!r} touch area is {ctl.h / dp:.0f} dp tall, needs >= {MIN_NAV_HEIGHT_DP}")
        if ctl.w / dp < MIN_NAV_HEIGHT_DP:
            problems.append(f"Upcoming {label!r} touch area is {ctl.w / dp:.0f} dp wide, needs >= {MIN_NAV_HEIGHT_DP}")
        if not inside:
            problems.append(f"Upcoming {label!r} is not inside the screen: {ctl.b}")
        if not (ctl.clickable or ctl.selected):
            problems.append(f"Upcoming {label!r} is not clickable")
    widths = [t.w for t in tabs.values()]
    if max(widths) - min(widths) > 2 * dp:
        problems.append(f"the three Upcoming tabs are not equal width: {[round(w / dp) for w in widths]} dp")
    ys = sorted(c.b[1] for c in [*tabs.values(), toggle])
    if ys[-1] - ys[0] > 8 * dp:
        problems.append("the tabs and the repeat toggle are not on one row")
    if toggle.b[0] < max(t.b[2] for t in tabs.values()) - 2:
        problems.append("the repeat toggle is not at the right end of the tab row")
    print("UPCOMING TABS:", "; ".join(lines))
    notice("Geometry: Upcoming tabs", "; ".join(lines))

    def series_rows(nodes):
        rows = [n for n in nodes if n.text.startswith("Sunday expense") or n.desc.startswith("Sunday expense") or "Daily income" in (n.text + n.desc)]
        rows = [clickable_of(r) for r in rows if r.b[1] > toggle.b[3]]
        return rows or None
    tap(toggle)
    nodes, rows = wait_until(series_rows, timeout=15)
    if not rows:
        problems.append("with the repeat toggle on no series row was found")
    else:
        lines = []
        for r in rows:
            inside = inside_screen(r, W, nav_top)
            lines.append(f"[{r.b[0]},{r.b[1]}][{r.b[2]},{r.b[3]}] {r.w / dp:.0f} x {r.h / dp:.0f} dp clickable={r.clickable} inside={inside}")
            if not r.clickable:
                problems.append(f"a series row is not clickable: {r.b}")
            if not inside:
                problems.append(f"a series row is not inside the screen above the bar: {r.b}")
        print("SERIES ROWS:", "; ".join(lines))
        notice("Geometry: series rows", "; ".join(lines))
    # the toggle is a toggle: with it on, the Income tab still lists series (the daily income) and Expenses lists the expense series
    tap(tabs["Income"])
    nodes, inc = wait_until(lambda nodes: [n for n in nodes if "Daily income" in (n.text + n.desc) and n.b[1] > toggle.b[3]] or None, timeout=15)
    if not inc:
        problems.append("with the repeat toggle on and the Income tab selected the income series was not listed")
    elif not clickable_of(inc[0]).clickable:
        problems.append("the income series row is not clickable with the Income tab selected")
    tap(toggle)  # back to the occurrences: the series rows ("Ends ..." / "No end") are gone
    def occurrences(nodes):
        return not any((n.text.startswith("Ends ") or n.text == "No end") and n.b[1] > toggle.b[3] for n in nodes)
    nodes, back = wait_until(occurrences, timeout=10)
    if not back:
        problems.append("switching the repeat toggle off did not return the list to occurrences")
    return problems


def check_history(W, H, dp, nav_top):
    """History: accepts one waiting item on Home (so History has a row), then measures the three tabs (>= 48 dp, equal width), the search field
    (>= 56 dp) and the row (>= 56 dp, clickable), and checks that a tap on the row opens the item sheet as history (no actions)."""
    problems = []
    nodes, hits = wait_for(S["nav_home"])
    tap(clickable_of(max(hits, key=lambda n: n.b[3])))
    time.sleep(1.5)
    for _ in range(8):  # the tab keeps its scroll position: look from the top
        sh(f"input swipe {W // 2} {int(H * 0.3)} {W // 2} {int(H * 0.8)} 250")
        time.sleep(0.3)
    nodes, card = wait_until(lambda nodes: [n for n in nodes if n.text.startswith("Sunday expense") and n.b[3] < nav_top] or None, timeout=15)
    if not card:
        return ["History: the waiting 'Sunday expense' card was not found on Home (needed to confirm one item)"]
    tap(clickable_of(card[0]))
    nodes, acc = wait_for(S["action_accept"], timeout=15)
    tap(clickable_of(acc[0]))
    time.sleep(2)
    nodes, hits = wait_for(S["nav_history"])
    tap(clickable_of(max(hits, key=lambda n: n.b[3])))
    labels = {"All": S["filter_all"], "Expenses": S["filter_expenses"], "Income": S["filter_income"]}
    def ready(nodes):
        tabs = {}
        for name, label in labels.items():
            hit = [n for n in nodes if (n.text == label or n.desc == label) and n.b[3] < nav_top]
            if hit:
                tabs[name] = clickable_of(min(hit, key=lambda n: n.b[1]))
        field = [n for n in nodes if n.cls.endswith("EditText")]
        rows = [n for n in nodes if n.text.startswith("Sunday expense") and n.b[3] < nav_top]
        return (tabs, field[0], rows[0]) if len(tabs) == 3 and field and rows else None
    nodes, found = wait_until(ready, timeout=25)
    if found is None:
        return ["History: the tabs, the search field and the confirmed row were not all found"]
    tabs, field, row_node = found
    row = clickable_of(row_node)
    lines = []
    for label, ctl, need_h in [*tabs.items(), ("Search field", field, MIN_SETTINGS_ROW_DP), ("Row", row, MIN_SETTINGS_ROW_DP)]:
        inside = inside_screen(ctl, W, nav_top)
        lines.append(f"{label}: {ctl.w / dp:.0f} x {ctl.h / dp:.0f} dp {'ok' if inside else 'OUTSIDE'}")
        if ctl.h / dp < need_h:
            problems.append(f"History {label!r} is {ctl.h / dp:.0f} dp tall, needs >= {need_h}")
        if not inside:
            problems.append(f"History {label!r} is not inside the screen above the bar: {ctl.b}")
    if not row.clickable:
        problems.append("the History row is not clickable")
    widths = [t.w for t in tabs.values()]
    if max(widths) - min(widths) > 2 * dp:
        problems.append(f"the three History tabs are not equal width: {[round(w / dp) for w in widths]} dp")
    print("HISTORY:", "; ".join(lines))
    notice("Geometry: History", "; ".join(lines))
    tap(row)
    nodes, ok = wait_until(lambda nodes: any(n.text == "This is history and cannot be changed" for n in nodes) or None, timeout=15)
    if not ok:
        problems.append("a History row did not open the item sheet as history ('This is history and cannot be changed')")
    sh("input keyevent 4")
    time.sleep(1)
    return problems


def check_settings(W, H, dp, nav_top):
    """Settings is a list of uniform rows: the Backups row, "Remind me at" and "Check for updates" are clickable rows at least 56 dp
    tall, the auto-accept switch is at least 48 dp, and the Backups row opens a full screen with a back arrow of at least 48 dp."""
    problems = []
    nodes, hits = wait_for(S["nav_settings"])
    tap(clickable_of(max(hits, key=lambda n: n.b[3])))
    time.sleep(1)
    for _ in range(6):  # back to the top of the tab (it keeps its scroll position)
        sh(f"input swipe {W // 2} {int(H * 0.3)} {W // 2} {int(H * 0.8)} 250")
        time.sleep(0.3)
    wanted = [("Backups", S["settings_backups_title"]), ("Remind me at", S["remind_label"]), ("Check for updates", S["check_updates"])]
    lines = []
    rows = {}
    for name, label in wanted:
        found = None
        for _ in range(14):
            nodes = dump()
            hit = [n for n in nodes if (n.text == label or n.desc == label) and n.b[3] < nav_top]
            if hit:
                found = clickable_of(hit[0])
                break
            sh(f"input swipe {W // 2} {int(H * 0.75)} {W // 2} {int(H * 0.35)} 300")
            time.sleep(0.5)
        if found is None:
            problems.append(f"Settings row {name!r} was not found")
            continue
        rows[name] = found
        inside = inside_screen(found, W, nav_top)
        lines.append(f"{name}: {found.w / dp:.0f} x {found.h / dp:.0f} dp clickable={found.clickable} {'ok' if inside else 'OUTSIDE'}")
        if not found.clickable:
            problems.append(f"Settings row {name!r} is not clickable")
        if found.h / dp < MIN_SETTINGS_ROW_DP:
            problems.append(f"Settings row {name!r} is {found.h / dp:.0f} dp tall, needs >= {MIN_SETTINGS_ROW_DP}")
        if not inside:
            problems.append(f"Settings row {name!r} is not inside the screen above the bar: {found.b}")
    # the switch row: the switch itself is the control
    for _ in range(6):
        sh(f"input swipe {W // 2} {int(H * 0.3)} {W // 2} {int(H * 0.8)} 250")
        time.sleep(0.3)
    nodes = dump()
    switches = [n for n in nodes if n.desc == S["auto_income_title"]]
    if not switches:
        problems.append("the 'Accept my income automatically' switch was not found")
    else:
        sw = clickable_of(switches[0])
        lines.append(f"Auto-accept switch: {sw.w / dp:.0f} x {sw.h / dp:.0f} dp")
        if sw.h / dp < MIN_NAV_HEIGHT_DP or sw.w / dp < MIN_NAV_HEIGHT_DP:
            problems.append(f"the auto-accept switch is {sw.w / dp:.0f} x {sw.h / dp:.0f} dp, needs >= {MIN_NAV_HEIGHT_DP}")
    print("SETTINGS ROWS:", "; ".join(lines))
    notice("Geometry: Settings rows", "; ".join(lines))
    # the Backups row opens its own screen
    backups = rows.get("Backups")
    if backups is not None:
        for _ in range(14):
            nodes = dump()
            hit = [n for n in nodes if n.text == S["settings_backups_title"] and n.b[3] < nav_top]
            if hit:
                backups = clickable_of(hit[0])
                break
            sh(f"input swipe {W // 2} {int(H * 0.75)} {W // 2} {int(H * 0.35)} 300")
            time.sleep(0.5)
        tap(backups)
        nodes, now_hits = wait_for(S["backup_now"], timeout=15)
        time.sleep(0.5)
        nodes = dump()
        back = [n for n in nodes if n.desc == S["settings_back"]]
        if not back:
            problems.append("the Backups screen has no back arrow")
        else:
            b = clickable_of(back[0])
            msg = f"Backups back arrow: {b.w / dp:.0f} x {b.h / dp:.0f} dp; Back up now: {clickable_of(now_hits[0]).h / dp:.0f} dp tall"
            print("BACKUPS SCREEN:", msg)
            notice("Geometry: Backups screen", msg)
            if b.h / dp < MIN_NAV_HEIGHT_DP or b.w / dp < MIN_NAV_HEIGHT_DP:
                problems.append(f"the Backups back arrow is {b.w / dp:.0f} x {b.h / dp:.0f} dp, needs >= {MIN_NAV_HEIGHT_DP}")
            tap(b)
            nodes, ok = wait_until(lambda nodes: any(n.text == S["settings_data_title"] for n in nodes) or None, timeout=10)  # the Settings group header, not the Backups screen title
            if not ok:
                problems.append("the back arrow did not return from the Backups screen to Settings")
    return problems


def main():
    global SCREEN_W
    W, H, dpi = display()
    SCREEN_W = W
    dp = dpi / 160.0
    inset = nav_inset(H)
    print(f"screen {W}x{H} px, {dpi} dpi (1 dp = {dp:.2f} px), navigation bar inset {inset} px ({inset / dp:.0f} dp)")
    problems = []

    # 1. bottom navigation
    labels = [S["nav_home"], S["nav_calendar"], S["nav_upcoming"], S["nav_history"], S["nav_settings"]]
    nodes, _ = wait_for(S["nav_settings"])
    measured = []
    for label in labels:
        hits = [n for n in nodes if n.text == label or n.desc == label]
        if not hits:
            problems.append(f"nav item {label!r} not found")
            continue
        # the bottom-most match is the bar (a screen title may carry the same word)
        item = clickable_of(max(hits, key=lambda n: n.b[3]))
        print(f"  {label}: " + " <- ".join(f"{c.cls.split('.')[-1]}[{c.b[0]},{c.b[1]}][{c.b[2]},{c.b[3]}]{'C' if c.clickable else ''}{'S' if c.selected else ''}" for c in chain(max(hits, key=lambda n: n.b[3]))[:4]))
        hd, wd = item.h / dp, item.w / dp
        measured.append(f"{label}: {wd:.0f} x {hd:.0f} dp ({item.w} x {item.h} px, {item.w / W:.0%} of width, clickable={item.clickable})")
        if not (item.clickable or item.selected):
            problems.append(f"nav item {label!r}: no clickable node around it")
        if hd < MIN_NAV_HEIGHT_DP:
            problems.append(f"nav item {label!r} is {hd:.0f} dp tall, needs >= {MIN_NAV_HEIGHT_DP}")
        if wd < MIN_NAV_WIDTH_DP:
            problems.append(f"nav item {label!r} is {wd:.0f} dp wide, needs >= {MIN_NAV_WIDTH_DP}")
        if item.w < MIN_NAV_WIDTH_SHARE * W:
            problems.append(f"nav item {label!r} is {item.w / W:.0%} of the screen width, needs >= {MIN_NAV_WIDTH_SHARE:.0%}")
        if item.b[0] < 0 or item.b[2] > W or item.b[1] < 0 or item.b[3] > H:
            problems.append(f"nav item {label!r} is off screen: {item.b}")
    print("NAV ITEMS:\n  " + "\n  ".join(measured))
    notice("Geometry: bottom navigation", " | ".join(measured))

    # 1b. the notification bell (top right of the main screens): a tap target of at least 48 dp each way
    bells = [n for n in nodes if n.desc.startswith(S["bell_desc"])]
    if not bells:
        problems.append("the notification bell was not found")
    else:
        bell = clickable_of(bells[0])
        bw, bh = bell.w / dp, bell.h / dp
        msg = f"bell: {bw:.0f} x {bh:.0f} dp ({bell.w} x {bell.h} px) at [{bell.b[0]},{bell.b[1]}][{bell.b[2]},{bell.b[3]}], clickable={bell.clickable}, desc={bell.desc!r}"
        print("BELL:", msg)
        notice("Geometry: notification bell", msg)
        if not bell.clickable:
            problems.append("the bell is not clickable")
        if bw < MIN_NAV_HEIGHT_DP or bh < MIN_NAV_HEIGHT_DP:
            problems.append(f"the bell is {bw:.0f} x {bh:.0f} dp, needs >= {MIN_NAV_HEIGHT_DP} each way")
        if bell.b[2] > W or bell.b[0] < W / 2 or bell.b[1] < 0:
            problems.append(f"the bell is not at the top right of the screen: {bell.b}")

    # 1c. the notification center must not block the bar: with it open every tab is present and clickable, and a tap leaves it
    bell_node = clickable_of(bells[0]) if bells else None
    if bell_node is not None:
        sh(f"input tap {(bell_node.b[0] + bell_node.b[2]) // 2} {(bell_node.b[1] + bell_node.b[3]) // 2}")
        nodes, _ = wait_for(S["center_mark_all"], timeout=15)
        time.sleep(0.5)
        nodes = dump()
        open_items = {}
        for label in labels:
            hits = [n for n in nodes if n.text == label or n.desc == label]
            if hits:
                open_items[label] = clickable_of(max(hits, key=lambda n: n.b[3]))
        missing = [l for l in labels if l not in open_items]
        if missing:
            problems.append(f"with the notification center open these tabs are missing: {missing}")
        for label, item in open_items.items():
            if not (item.clickable or item.selected):
                problems.append(f"with the notification center open the {label!r} tab is not clickable")
        print("NAV WITH CENTER OPEN:", ", ".join(f"{l} {i.w / dp:.0f}x{i.h / dp:.0f} dp clickable={i.clickable}" for l, i in open_items.items()))
        cal = open_items.get(S["nav_calendar"])
        if cal is not None:
            sh(f"input tap {(cal.b[0] + cal.b[2]) // 2} {(cal.b[1] + cal.b[3]) // 2}")
            def calendar_shown(nodes):
                closed = not any(n.text == S["center_mark_all"] for n in nodes)
                sel = any((n.text == S["nav_calendar"] or n.desc == S["nav_calendar"]) and any(c.selected for c in chain(n)) for n in nodes)
                return closed and sel
            nodes, _ok = wait_until(calendar_shown, timeout=15)
            still_open = any(n.text == S["center_mark_all"] for n in nodes)
            cal_selected = any((n.text == S["nav_calendar"] or n.desc == S["nav_calendar"]) and any(c.selected for c in chain(n)) for n in nodes)
            print(f"TAP ON CALENDAR WITH CENTER OPEN: center still open={still_open}, calendar tab selected={cal_selected}")
            notice("Geometry: tabs with the center open", "; ".join(f"{l} {i.w / dp:.0f} x {i.h / dp:.0f} dp" for l, i in open_items.items()) + f"; after a tap on Calendar the center is {'STILL OPEN' if still_open else 'closed'}")
            if still_open:
                problems.append("tapping a bottom-navigation tab did not close the notification center")
            if not cal_selected:
                problems.append("tapping the Calendar tab with the center open did not select Calendar")
            home = [n for n in nodes if n.text == S["nav_home"] or n.desc == S["nav_home"]]
            if home:
                h = clickable_of(max(home, key=lambda n: n.b[3]))
                sh(f"input tap {(h.b[0] + h.b[2]) // 2} {(h.b[1] + h.b[3]) // 2}")
                time.sleep(1.5)

    # 2. Home: the Plan a purchase button, then the Add Expense wizard and the Plan a purchase wizard on their first step
    nodes, hits = wait_for(S["button_expense"])
    nav_top = min((i.b[1] for i in open_items.values()), default=H) if bell_node is not None else H
    plan_hits = [n for n in nodes if n.text == S["plan_button"] or n.desc == S["plan_button"]]
    if not plan_hits:
        problems.append("the 'Plan a purchase' button was not found on Home")
    else:
        plan = clickable_of(plan_hits[0])
        pw, ph = plan.w / dp, plan.h / dp
        inside = plan.b[0] >= 0 and plan.b[2] <= W and plan.b[1] >= 0 and plan.b[3] <= nav_top
        msg = f"Plan a purchase: [{plan.b[0]},{plan.b[1]}][{plan.b[2]},{plan.b[3]}] {pw:.0f} x {ph:.0f} dp, clickable={plan.clickable}, inside the screen above the bar={inside}"
        print("PLAN BUTTON:", msg)
        notice("Geometry: Plan a purchase button", msg)
        if not plan.clickable:
            problems.append("the 'Plan a purchase' button is not clickable")
        if ph < MIN_NAV_HEIGHT_DP:
            problems.append(f"the 'Plan a purchase' button is {ph:.0f} dp tall, needs >= {MIN_NAV_HEIGHT_DP}")
        if not inside:
            problems.append(f"the 'Plan a purchase' button is not fully on screen above the navigation bar: {plan.b} (bar top {nav_top})")
        sh(f"input tap {(plan.b[0] + plan.b[2]) // 2} {(plan.b[1] + plan.b[3]) // 2}")
        problems += check_sheet("Plan a purchase wizard, price step", W, H, dp, inset)
        sh("input keyevent 4")  # an untouched wizard closes at once
        time.sleep(1.5)

    nodes, hits = wait_for(S["button_expense"])
    target = clickable_of(hits[0])
    sh(f"input tap {(target.b[0] + target.b[2]) // 2} {(target.b[1] + target.b[3]) // 2}")
    problems += check_sheet("Add expense wizard, Amount step", W, H, dp, inset)
    sh("input keyevent 4")  # an untouched wizard closes at once
    time.sleep(1.5)
    # 3. Upcoming: the three filter chips (>= 48 dp, inside the screen) and, with Repeating selected, the series rows
    problems += check_upcoming(W, H, dp, nav_top)
    # 4. History: tabs, search field, rows
    problems += check_history(W, H, dp, nav_top)
    # 5. Settings: uniform rows (>= 56 dp) and the Backups screen
    problems += check_settings(W, H, dp, nav_top)

    if problems:
        for p in problems:
            print(f"::error title=Geometry gate::{p}")
        print("GEOMETRY GATE FAILED")
        return 1
    print("GEOMETRY GATE OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
