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
S_FORMAT_BALANCE = S["cal_desc_balance"].replace("%1$s", "{}")
TEXT_SETTINGS = S["nav_settings"]
TEXT_HOME = S["nav_home"]
TEXT_LOAD_SAMPLE = S["debug_load_sample"]
TEXT_EXPENSE = S["button_expense"]
NAV_CALENDAR = S["nav_calendar"]
NAV_UPCOMING = S["nav_upcoming"]
TEXT_REPEATING = S["repeating_title"]
DESC_NEXT_MONTH = S["calendar_next_month"]
DESC_PREV_MONTH = S["calendar_previous_month"]

# Configurations that also walk through Calendar, day sheet, item edit, Upcoming and Repeating payments.
CALENDAR_CONFIGS = {"dark-normal", "light-normal", "dark-font2", "dark-320dp"}
# Configurations that also screenshot the bottom of Settings.
SETTINGS_BOTTOM_CONFIGS = {"dark-normal", "dark-font2"}
# The configuration that also switches "Accept my income automatically" on and checks what that does.
AUTO_ACCEPT_CONFIG = "dark-normal"
SAMPLE_MONTH = (2026, 11)  # the reference scenario's interesting month


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


def dismiss_system_dialog(nodes):
    """An emulator's own "<app> isn't responding" box (seen for the launcher on slow runners) hides the app from the
    UI dump: press Wait so the app is visible again."""
    if any("isn't responding" in (t or "") for t, _, _ in nodes):
        for t, _, b in nodes:
            if (t or "").strip() == "Wait":
                sh(f"input tap {(b[0] + b[2]) // 2} {(b[1] + b[3]) // 2}")
                time.sleep(1)
                return


def find(text, scroll=True, last=False, timeout=FIND_TIMEOUT, contains=False):
    """Waits up to `timeout` (10 s) for a node with exactly this text (or, with `contains`, a text or content
    description that includes it); scrolls down while it is missing."""
    deadline = time.time() + timeout
    last_nodes = None
    while True:
        nodes = dump_nodes()
        if nodes:
            last_nodes = nodes
            dismiss_system_dialog(nodes)
            if contains:
                hits = [b for t, d, b in nodes if text in t or text in d]
            else:
                hits = [b for t, d, b in nodes if t.strip() == text or d.strip() == text]
            if hits:
                return sorted(hits, key=lambda b: (b[1], b[0]))[-1 if last else 0]
            if scroll:
                w, h = screen_size(nodes)
                sh(f"input swipe {w // 2} {int(h * 0.8)} {w // 2} {int(h * 0.25)} 300")
        if time.time() > deadline:
            visible = [(t or d) for t, d, _ in (last_nodes or []) if (t or d)]
            raise NotFound(f"text {text!r} not found within {timeout:.0f}s; visible texts: {visible}")
        time.sleep(0.5)


def find_slow(text, max_swipes=40):
    """Looks for `text` while scrolling down with slow, short drags (no fling), so a long list cannot jump past it
    between two looks. Raises NotFound after `max_swipes` drags."""
    nodes = []
    for _ in range(max_swipes):
        nodes = dump_nodes() or []
        dismiss_system_dialog(nodes)
        hits = [b for t, d, b in nodes if t.strip() == text or d.strip() == text]
        if hits:
            return sorted(hits, key=lambda b: (b[1], b[0]))[0]
        if nodes:
            w, h = screen_size(nodes)
            sh(f"input swipe {w // 2} {int(h * 0.7)} {w // 2} {int(h * 0.4)} 900")
            time.sleep(0.5)
    visible = [(t or d) for t, d, _ in nodes if (t or d)]
    raise NotFound(f"text {text!r} not found after {max_swipes} slow drags; visible texts: {visible}")


def diagnostics():
    pid = sh(f"pidof {PKG}", check=False).strip()
    print(f"  pid: {pid or 'NONE'}")
    log = adb("logcat", "-d", check=False).splitlines()
    mine = [l for l in log if PKG in l or "FATAL" in l or " E AndroidRuntime" in l or "ANR" in l]
    print("  logcat (package-related, last 60):")
    for line in mine[-60:]:
        print("   ", line)


def tap_text(text, **kw):
    x1, y1, x2, y2 = find(text, **kw)
    sh(f"input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")
    time.sleep(0.8)


def tap_text_midscreen(text, attempts=8):
    """Scrolls until `text` is in the middle band of the screen (clear of the bottom bar), then taps it."""
    for _ in range(attempts):
        nodes = dump_nodes() or []
        hits = [b for t, d, b in nodes if t.strip() == text or d.strip() == text]
        if hits:
            _, h = screen_size(nodes)
            x1, y1, x2, y2 = sorted(hits, key=lambda b: (b[1], b[0]))[0]
            cy = (y1 + y2) // 2
            if h * 0.15 < cy < h * 0.65:
                sh(f"input tap {(x1 + x2) // 2} {cy}")
                time.sleep(0.8)
                return
            if cy >= h * 0.65:
                swipe_up(0.7, 0.4)
            else:
                swipe_down()
        else:
            swipe_up(0.7, 0.4)
    raise NotFound(f"text {text!r} never reached the middle of the screen")


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


def launch():
    """Cold-starts the app with cleared data. Android can kill a process that is started while the previous
    task is still being removed ("remove task" race right after `pm clear`), so pause after clearing and
    retry the launch if the UI does not appear."""
    for attempt in range(1, 4):
        sh(f"am force-stop {PKG}")
        if attempt == 1:
            sh(f"pm clear {PKG}")
            adb("logcat", "-c", check=False)
        time.sleep(3)
        sh(f"monkey -p {PKG} -c android.intent.category.LAUNCHER 1")
        try:
            find(TEXT_SETTINGS, scroll=False, timeout=20)
            return
        except NotFound as e:
            print(f"  launch attempt {attempt} did not show the UI: {e}")
            diagnostics()
    raise NotFound("the app never showed its UI after 3 launch attempts")


# ---- calendar / upcoming / settings walk-through -------------------------------------------------------

MONTHS = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"]


def swipe_up(fraction_from=0.75, fraction_to=0.3):
    nodes = dump_nodes() or []
    if nodes:
        w, h = screen_size(nodes)
        sh(f"input swipe {w // 2} {int(h * fraction_from)} {w // 2} {int(h * fraction_to)} 300")
        time.sleep(0.8)


def swipe_down():
    nodes = dump_nodes() or []
    if nodes:
        w, h = screen_size(nodes)
        sh(f"input swipe {w // 2} {int(h * 0.3)} {w // 2} {int(h * 0.75)} 300")
        time.sleep(0.8)


def scroll_to_top(times=10):
    for _ in range(times):
        swipe_down()


def visible_month_title():
    """(year, month) of the calendar title that is on screen, or None."""
    for t, _, _ in dump_nodes() or []:
        m = re.fullmatch(r"([A-Z][a-z]+) (\d{4})", t.strip())
        if m and m.group(1) in MONTHS:
            return int(m.group(2)), MONTHS.index(m.group(1)) + 1
    return None


def go_to_month(year, month):
    for _ in range(40):
        shown = visible_month_title()
        if shown == (year, month):
            return
        if shown is None:
            time.sleep(0.7)
            continue
        forward = (year, month) > shown
        tap_text(DESC_NEXT_MONTH if forward else DESC_PREV_MONTH, scroll=False)
        time.sleep(0.8)
    raise NotFound(f"could not navigate the calendar to {year}-{month:02d}")


def cell_descriptions():
    """{date: content description} of the calendar cells, scrolling the tab once to reach the lower rows."""
    out = {}
    pattern = re.compile(r"^[A-Za-z]+, ([A-Za-z]+) (\d+), (\d{4})")

    def collect():
        for _, d, _ in dump_nodes() or []:
            m = pattern.match(d)
            if m and m.group(1) in MONTHS:
                out[(int(m.group(3)), MONTHS.index(m.group(1)) + 1, int(m.group(2)))] = d

    collect()
    swipe_up()
    collect()
    swipe_down()
    return out


import datetime as _dt

# The reference scenario, written down independently of the app (see ReferenceScenario.kt): start Oct 10, 2026 with
# 180 TND, one-off expenses, 75 every Sunday Oct 11 .. Dec 27, and 30 of daily income from Oct 11. Nothing is accepted
# when the numbers are checked, so Available stays 180 and the forecast follows the rule in BalanceEngine.
REF_START = _dt.date(2026, 10, 10)
REF_BALANCE = 180
REF_ONE_OFFS = {(10, 12): 128, (10, 13): 67, (10, 14): 30, (10, 15): 20, (10, 16): 20, (10, 24): 75, (10, 31): 25,
                (11, 10): 380, (11, 12): 63, (11, 28): 220, (11, 30): 25, (12, 12): 63, (12, 31): 25}


def ref_expenses_on(d):
    total = REF_ONE_OFFS.get((d.month, d.day), 0) if d.year == 2026 else 0
    if _dt.date(2026, 10, 11) <= d <= _dt.date(2026, 12, 27) and d.weekday() == 6:
        total += 75
    return total


def ref_income_on(d):
    return 30 if d >= _dt.date(2026, 10, 11) else 0  # (the 2027 freelance series is far away)


def ref_closing(d, today):
    """Balance at the end of day d when nothing has been accepted: history is flat; from today on, Available minus
    the overdue pending expenses, plus every pending item on its date. Overdue pending income is not counted."""
    if d < REF_START:
        return None
    if d < today:
        return REF_BALANCE
    overdue = sum(ref_expenses_on(REF_START + _dt.timedelta(days=i)) for i in range(max((today - REF_START).days, 0)))
    balance = REF_BALANCE - overdue
    day = max(today, REF_START)
    while day <= d:
        balance += ref_income_on(day) - ref_expenses_on(day)
        day += _dt.timedelta(days=1)
    return balance


def device_now():
    """The phone's local date and time (what the app uses)."""
    text = sh("date '+%Y-%m-%d %H:%M'").strip()
    return _dt.datetime.strptime(text, "%Y-%m-%d %H:%M")


def balance_text(value):
    sign = "\u2212" if value < 0 else ""
    return f"{sign}{abs(value):,}".replace(",", "\u00a0")


def check_sample_numbers(name, today):
    """Compares what the November 2026 cells say (their accessibility text) with the reference scenario."""
    problems = []
    cells = cell_descriptions()
    y, m = SAMPLE_MONTH
    for day in range(1, 31):
        d = cells.get((y, m, day))
        if d is None:
            problems.append(f"{name}: calendar cell for {y}-{m:02d}-{day:02d} was not found")
            continue
        date = _dt.date(y, m, day)
        closing = ref_closing(date, today)
        short = "Money runs short" in d
        if short != (closing < 0):
            problems.append(f"{name}: {date} short={short}, expected {closing < 0} (balance {closing}) ({d!r})")
        want_balance = S_FORMAT_BALANCE.format(balance_text(closing))
        if want_balance not in d:
            problems.append(f"{name}: {date} should say {want_balance!r} but says {d!r}")
        exp = ref_expenses_on(date)
        if exp and f"Expenses {exp} dinars" not in d:
            problems.append(f"{name}: {date} should say 'Expenses {exp} dinars' but says {d!r}")
        if not exp and "Expenses" in d:
            problems.append(f"{name}: {date} shows an expense that is not in the scenario ({d!r})")
        if "Income" in d:
            problems.append(f"{name}: {date} shows income although only daily income exists ({d!r})")
    print(f"  calendar numbers checked against the reference model (today={today}): {len(cells)} cells, {len(problems)} problems")
    return problems


def calendar_flow(name, today):
    problems = []
    tap_text(NAV_CALENDAR, scroll=False)
    time.sleep(1.5)
    screenshot(f"{name}-calendar")
    go_to_month(*SAMPLE_MONTH)
    time.sleep(1)
    screenshot(f"{name}-calendar-nov")
    problems += check_sample_numbers(name, today)

    # Day sheet of Nov 10: the closing balance sentence must match the reference model.
    tap_text("November 10, 2026", scroll=False, contains=True)
    time.sleep(1.2)
    screenshot(f"{name}-day-sheet")
    nov10 = ref_closing(_dt.date(2026, 11, 10), today)
    sentence = S["day_end_short" if nov10 < 0 else "day_end_have"].replace("%1$s", balance_text(abs(nov10)).replace("\u2212", ""))
    find(sentence, scroll=False, timeout=8)
    # One item: change it.
    tap_text("Expense 11/10")
    time.sleep(1)
    find(S["item_edit_title"], scroll=False, timeout=8)
    screenshot(f"{name}-item-edit")
    swipe_up()
    screenshot(f"{name}-item-edit-bottom")
    sh("input keyevent 4")  # closes the edit sheet
    time.sleep(1.2)
    # Add from the day: the composer opens with that day prefilled.
    tap_text(TEXT_EXPENSE)
    find("1", scroll=True, last=True)
    time.sleep(1)
    screenshot(f"{name}-day-composer")
    sh("input keyevent 4")  # closes the composer
    time.sleep(1.2)
    sh("input keyevent 4")  # closes the day sheet
    time.sleep(1.2)

    # An item that comes from a repeating payment (Sunday Nov 8).
    tap_text("November 8, 2026", scroll=False, contains=True)
    time.sleep(1.2)
    tap_text("Sunday expense")
    time.sleep(1)
    find(S["item_edit_title"], scroll=False, timeout=8)
    screenshot(f"{name}-item-edit-series")
    sh("input keyevent 4")
    time.sleep(1.2)
    sh("input keyevent 4")
    time.sleep(1.2)
    return problems


def upcoming_flow(name, today):
    problems = []
    tap_text(NAV_UPCOMING, scroll=False)
    time.sleep(1.5)
    screenshot(f"{name}-upcoming")
    # A waiting item: its edit sheet has the red cross and the green check.
    tap_text("Sunday expense", scroll=False)
    time.sleep(1)
    find(S["item_edit_title"], scroll=False, timeout=8)
    find(S["action_accept"], scroll=True, timeout=15)
    find(S["action_reject"], scroll=False, timeout=8)
    screenshot(f"{name}-item-edit-waiting")
    sh("input keyevent 4")
    time.sleep(1.2)
    nov10 = ref_closing(_dt.date(2026, 11, 10), today)
    if nov10 < 0:
        try:
            scroll_to_top(15)  # the list keeps its position after the sheet: look downwards from the top
            find_slow(S["short_by_tag"].replace("%1$s", balance_text(-nov10)))
            screenshot(f"{name}-upcoming-short")
        except NotFound as e:
            problems.append(f"{name}: Upcoming has no 'Short by {-nov10} TND' row for Nov 10 ({e})")
    else:
        for _ in range(4):
            swipe_up()
        screenshot(f"{name}-upcoming-short")
    return problems


def settings_flow(name):
    """Settings screenshots: Repeating payments (with its editors), the bottom of the screen, and for one
    configuration what "Accept my income automatically" really does."""
    problems = []
    find(TEXT_REPEATING, scroll=True)
    time.sleep(0.5)
    screenshot(f"{name}-repeating")
    # An income series with an end date is listed with its signed amount and "until <date>".
    try:
        find("Freelance income", scroll=True, timeout=20)
        find("until", scroll=False, timeout=8, contains=True)
        screenshot(f"{name}-repeating-income")
        tap_text("Freelance income", scroll=False)
        time.sleep(1)
        find(S["end_date_label"], scroll=True, timeout=15, contains=True)
        screenshot(f"{name}-repeating-income-edit")
        sh("input keyevent 4")
        time.sleep(1)
    except NotFound as e:
        problems.append(f"{name}: the ending income series is not listed with an end date ({e})")
    scroll_to_top()
    find(TEXT_REPEATING, scroll=True)
    try:
        tap_text("Sunday expense", scroll=True, timeout=8)
    except NotFound:
        # Scrolled past the list (a flaky swipe on a short screen): start again from the top and look downwards.
        scroll_to_top()
        tap_text("Sunday expense", scroll=True, timeout=25)
    time.sleep(1)
    screenshot(f"{name}-repeating-edit")
    sh("input keyevent 4")
    time.sleep(1)
    if name in SETTINGS_BOTTOM_CONFIGS:
        for _ in range(8):
            swipe_up()
        screenshot(f"{name}-settings-bottom")
    if name == AUTO_ACCEPT_CONFIG:
        problems += auto_accept_flow(name)
    return problems


def spoken_available():
    for _, d, _ in dump_nodes() or []:
        m = re.match(r"Available: (.+) dinars", d)
        if m:
            return m.group(1).replace("\u00a0", "").replace("\u2212", "-")
    return None


def waiting_count():
    for t, _, _ in dump_nodes() or []:
        m = re.match(r"(\d+) waiting for you", t.strip())
        if m:
            return int(m.group(1))
    return 0


def auto_accept_flow(name):
    """Turns "Accept my income automatically" on and checks, against the reference model, what was accepted."""
    problems = []
    now = device_now()
    today = now.date()
    scroll_to_top()
    tap_text(S["auto_income_title"], scroll=False, last=True)  # the switch has the same description as its label
    time.sleep(3)  # the catch-up runs when the setting changes
    screenshot(f"{name}-settings-auto-on")
    tap_text(TEXT_HOME, scroll=False)
    time.sleep(3)
    # Daily income of earlier days is caught up at once; today's only from the "Remind me at" time (12:00).
    income_days = max((today - _dt.date(2026, 10, 11)).days, 0) + (1 if now.hour >= 12 and today >= _dt.date(2026, 10, 11) else 0)
    want = str(REF_BALANCE + 30 * income_days)
    got = spoken_available()
    if got != want:
        problems.append(f"{name}: after turning auto-accept on Available should be {want} but is {got}")
    pending_expenses = sum(1 for i in range((today - REF_START).days + 1) if ref_expenses_on(REF_START + _dt.timedelta(days=i)))
    pending_income_today = 1 if (now.hour < 12 and today >= _dt.date(2026, 10, 11)) else 0
    want_waiting = pending_expenses + pending_income_today
    got_waiting = waiting_count()
    if got_waiting != want_waiting:
        problems.append(f"{name}: waiting count should be {want_waiting} (expenses never auto-accepted) but is {got_waiting}")
    print(f"  auto-accept: Available {got} (expected {want}), waiting {got_waiting} (expected {want_waiting})")
    screenshot(f"{name}-home-auto-income")
    return problems


def run_config(name, night, font, size, density):
    print(f"== {name}: night={night} font={font} size={size or 'default'}")
    configure(night, font, size, density)
    try:
        launch()
        tap_text(TEXT_SETTINGS, scroll=False)
        tap_text(TEXT_LOAD_SAMPLE, timeout=60)  # scrolls down to the developer tools card (a long screen at large fonts)
        time.sleep(2.5)
        tap_text(TEXT_HOME, scroll=False)
        time.sleep(4)  # the count-up finishes
        screenshot(f"{name}-home")
        extra = []
        # Nothing has been accepted: Available is the starting balance, whatever is waiting or overdue.
        if spoken_available() != str(REF_BALANCE):
            extra.append(f"{name}: Available should be {REF_BALANCE} while nothing is accepted, but is {spoken_available()}")
        # Tapping a waiting card opens the accept sheet: a red cross and a green check.
        scroll_to_top(3)
        tap_text_midscreen("Sunday expense")
        time.sleep(1.2)
        screenshot(f"{name}-home-accept-sheet")
        try:
            find(S["action_accept"], scroll=False, timeout=6)
        except NotFound:
            find(S["action_accept"], scroll=True, timeout=15)  # a short screen: the sheet scrolls
        find(S["action_reject"], scroll=False, timeout=8)
        sh("input keyevent 4")
        time.sleep(1.2)
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
        if name in CALENDAR_CONFIGS:
            today = device_now().date()
            extra += calendar_flow(name, today)
            extra += upcoming_flow(name, today)
        tap_text(TEXT_SETTINGS, scroll=False)
        time.sleep(1)
        scroll_to_top()  # the tab keeps its scroll position, so a fresh look starts from the top
        screenshot(f"{name}-settings")
        extra += settings_flow(name)
    except NotFound as e:
        screenshot(f"{name}-FAILED")
        print("  NOT FOUND:", e)
        diagnostics()
        nodes = dump_nodes() or []
        print("  dump:", [(t, d, b) for t, d, b in nodes if t or d][:80])
        return [f"{name}: {e}"]
    problems = list(extra)
    if not app_alive():
        problems.append(f"{name}: app process is not alive")
    problems += [f"{name}: crash in log:\n{c}" for c in crash_log()]
    return problems


# ---- hero animation frame check -----------------------------------------------------------------------

BURST_FRAMES = 24
# Rows of the hero number at 1080x2340 (dark, normal font): below the "Available" label, above the chip.
HERO_BAND = (290, 470)
INK_MIN_LUMA = 150  # white digits / 80%-alpha decimals; the skeleton blocks and background are much darker
MIN_INK_PIXELS = 500
MAX_INK_PIXELS = 60000  # more than this is the bright launcher wallpaper, not the app


def set_animations(scale):
    for key in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
        sh(f"settings put global {key} {scale}")


def burst(prefix, frames, relaunch):
    """Captures `frames` screenshots as fast as the device allows, in one on-device loop (no adb round trip
    between frames), starting right after the app is launched. Raw (uncompressed) screencap is used because
    PNG compression takes ~300 ms per frame. Returns [(path, seconds since the launch command)]."""
    from PIL import Image
    sh("rm -f /sdcard/hero_*.raw /sdcard/hero_times.txt")
    adb("logcat", "-c", check=False)
    start = f"am force-stop {PKG}; " if relaunch else ""
    loop = (
        f"{start}echo L $(date +%s%N) > /sdcard/hero_times.txt; "
        f"(am start -n {PKG}/.MainActivity >/dev/null 2>&1 &); "
        f"i=0; while [ $i -lt {frames} ]; do n=$(printf %02d $i); a=$(date +%s%N); "
        f"screencap /sdcard/hero_$n.raw; b=$(date +%s%N); echo \"$n $a $b\" >> /sdcard/hero_times.txt; i=$((i+1)); done"
    )
    sh(loop)
    times = {}
    launch_ns = 0
    for line in adb("shell", "cat /sdcard/hero_times.txt", check=False).splitlines():
        parts = line.split()
        if parts[:1] == ["L"] and len(parts) == 2:
            launch_ns = int(parts[1])
        elif len(parts) == 3:
            times[parts[0]] = (int(parts[1]) + int(parts[2])) // 2
    os.makedirs(OUT, exist_ok=True)
    out = []
    for i in range(frames):
        n = f"{i:02d}"
        raw = os.path.join(OUT, f"{prefix}-{n}.raw")
        adb("pull", f"/sdcard/hero_{n}.raw", raw, check=False)
        if not os.path.exists(raw):
            continue
        data = open(raw, "rb").read()
        os.remove(raw)
        width, height = int.from_bytes(data[0:4], "little"), int.from_bytes(data[4:8], "little")
        pixels = data[len(data) - width * height * 4:]  # header is 12 or 16 bytes depending on the release
        path = os.path.join(OUT, f"{prefix}-{n}.png")
        Image.frombytes("RGBA", (width, height), pixels).convert("RGB").save(path, optimize=False, compress_level=1)
        out.append((path, (times.get(n, launch_ns) - launch_ns) / 1e9))
    print("  logcat CashflowHero:")
    for line in adb("logcat", "-d", "-v", "time", "-s", "CashflowHero:I", check=False).splitlines():
        if "CashflowHero" in line:
            print("   ", line)
    return out


def band_info(path):
    """(ink pixel count, left extent, right extent, gray crop) of the hero band of a frame. Frames may be
    smaller than the 1080-wide screen (video); extents are returned in 1080-wide device pixels and the ink
    count is normalised the same way."""
    from PIL import Image
    img = Image.open(path).convert("L")
    scale = img.width / 1080.0
    crop = img.crop((0, round(HERO_BAND[0] * scale), img.width, round(HERO_BAND[1] * scale)))
    px = crop.load()
    w, h = crop.size
    # While the window fades in the whole number is dimmer, so "ink" is relative to the brightest pixel.
    threshold = max(INK_MIN_LUMA // 2, 0.55 * crop.getextrema()[1])
    cols = [False] * w
    count = 0
    for x in range(w):
        for y in range(h):
            if px[x, y] > threshold:
                cols[x] = True
                count += 1
    xs = [x for x, c in enumerate(cols) if c]
    left = round(xs[0] / scale) if xs else None
    right = round(xs[-1] / scale) if xs else None
    return round(count / (scale * scale)), left, right, crop


def is_hero_frame(count, left, right):
    """The app shows the number: enough ink, not the bright launcher wallpaper, and centred (not the launcher's
    left-aligned date text)."""
    return MIN_INK_PIXELS <= count <= MAX_INK_PIXELS and left is not None and left >= 150 and right <= 1000


def extract_video_frames(video, prefix, fps=30):
    """Decodes the screen recording into PNG frames (evenly spaced in time, `fps` per second) with ffmpeg
    from the imageio-ffmpeg wheel. Returns [(path, seconds since the first video frame)]."""
    import glob
    import imageio_ffmpeg
    ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
    for old in glob.glob(os.path.join(OUT, f"{prefix}-*.png")):
        os.remove(old)
    pattern = os.path.join(OUT, f"{prefix}-%03d.png")
    r = subprocess.run([ffmpeg, "-v", "error", "-y", "-i", video, "-vf", f"fps={fps}", "-compression_level", "1", pattern],
                       capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(f"ffmpeg failed: {r.stderr.strip()}")
    paths = sorted(glob.glob(os.path.join(OUT, f"{prefix}-*.png")))
    return [(p, i / fps) for i, p in enumerate(paths)]


def record_launch(prefix, seconds=10):
    """Cold-starts the app while the screen is being recorded; returns the decoded frames."""
    sh("rm -f /sdcard/hero.mp4")
    adb("logcat", "-c", check=False)
    sh("am force-stop " + PKG)
    sh(f"(screenrecord --time-limit {seconds} --bit-rate 16000000 /sdcard/hero.mp4 >/sdcard/hero_rec.log 2>&1 &)")
    time.sleep(1.5)
    sh(f"am start -n {PKG}/.MainActivity >/dev/null 2>&1")
    time.sleep(seconds)
    time.sleep(1.5)  # screenrecord finalises the file
    os.makedirs(OUT, exist_ok=True)
    video = os.path.join(OUT, f"{prefix}.mp4")
    adb("pull", "/sdcard/hero.mp4", video, check=False)
    if not os.path.exists(video) or os.path.getsize(video) < 1000:
        print("  screenrecord log:", sh("cat /sdcard/hero_rec.log", check=False).strip())
        return None
    frames = extract_video_frames(video, prefix)
    os.remove(video)  # only the PNG frames are published
    return frames


def mean_abs_diff(a, b):
    from PIL import ImageChops, ImageStat
    return ImageStat.Stat(ImageChops.difference(a, b)).mean[0]


def make_filmstrip(infos, name):
    from PIL import Image
    crops = [c for _, _, _, c in infos]
    if not crops:
        return
    w, h = crops[0].size
    scale = 0.5
    cw, ch = int(w * scale), int(h * scale)
    sheet = Image.new("L", (cw, ch * len(crops)), 0)
    for i, c in enumerate(crops):
        sheet.paste(c.resize((cw, ch)), (0, i * ch))
    sheet.save(os.path.join(OUT, name))


def run_hero_animation():
    print("== hero animation (real motion, animator scale 1)")
    problems = []
    configure(True, "1.0", None, None)
    try:
        # Setup with animations off (plain flow): fresh data + sample data, then stop the app.
        set_animations("0.0")
        launch()
        tap_text(TEXT_SETTINGS, scroll=False)
        tap_text(TEXT_LOAD_SAMPLE)
        time.sleep(2.5)
        sh(f"am force-stop {PKG}")
        time.sleep(1)

        # 1) Launch from closed with real animations. The screen is recorded (screencap is far too slow to
        #    catch a 1.5 s animation on a CI emulator) and decoded to 30 frames per second.
        set_animations("1.0")
        frames = record_launch("hero-anim")
        if not frames:
            problems.append("hero: screenrecord produced no video")
            frames = []
        print("  logcat CashflowHero:")
        launch_log = adb("logcat", "-d", "-v", "time", "-s", "CashflowHero:I", check=False)
        for line in launch_log.splitlines():
            if "CashflowHero" in line:
                print("   ", line)
        # The app's own clock is the reliable duration measure: from the moment the roll starts to the moment
        # it has settled. (A screen recording on a loaded CI emulator drops and duplicates frames.)
        rolling = re.search(r"rolling t=(\d+)", launch_log)
        settled = re.search(r"settled t=(\d+)", launch_log)
        if rolling and settled:
            took = int(settled.group(1)) - int(rolling.group(1))
            print(f"  animation duration on the app clock: {took} ms (spec 1500 ms)")
            if not 1450 <= took <= 1700:
                problems.append(f"hero: the roll took {took} ms on the app clock, expected about 1500")
        else:
            problems.append("hero: the app log has no rolling/settled lines for the launch animation")
        time.sleep(1)
        screenshot("hero-anim-final")
        final = band_info(os.path.join(OUT, "hero-anim-final.png"))
        infos = []
        for path, t in frames:
            count, left, right, crop = band_info(path)
            infos.append((path, t, count, left, right, crop))
        # Frames from the window-open transition (the app icon fading, the whole window still dark) are not
        # layout evidence: the number must be at least half as bright as in the final screenshot.
        final_peak = final[3].getextrema()[1]
        hero_frames = [x for x in infos if is_hero_frame(x[2], x[3], x[4]) and x[5].getextrema()[1] >= 0.5 * final_peak]
        print("  frame   t(s)    ink  left right  diff-to-final")
        for path, t, count, left, right, crop in infos:
            if is_hero_frame(count, left, right) and crop.getextrema()[1] >= 0.5 * final_peak:
                d = mean_abs_diff(crop, final[3].resize(crop.size))
                print(f"  {os.path.basename(path)[-7:-4]:>5} {t:6.2f} {count:6d} {left!s:>5} {right!s:>5}  {d:6.2f}")
        if len(hero_frames) < 10:
            problems.append(f"hero: only {len(hero_frames)} frames show the number; expected an animation burst")
        else:
            make_filmstrip([(p, t, c, crop) for p, t, c, l, r, crop in hero_frames[::3]], "hero-anim-filmstrip.png")
            rights = [r for *_, r, _ in hero_frames]
            lefts = [l for _, _, _, l, _, _ in hero_frames]
            span_r = max(rights) - min(rights)
            span_l = max(lefts) - min(lefts)
            print(f"  hero frames: {len(hero_frames)}  right extent range {min(rights)}..{max(rights)} ({span_r}px)  "
                  f"left extent range {min(lefts)}..{max(lefts)} ({span_l}px)")
            # The TND label is static, so the right edge must not move at all (video frames are lossy and
            # possibly downscaled, hence a little tolerance for the quantisation). The left edge is the ink of
            # the first digit, whose shape differs from digit to digit (a '1' starts further right than a
            # '9'), so it gets a glyph-shape allowance; the slot layout itself is fixed-width.
            if span_r > 2:
                problems.append(f"hero: right extent moved by {span_r}px across frames (limit 2)")
            if span_l > 24:
                problems.append(f"hero: left extent moved by {span_l}px across frames (limit 24 for glyph shapes)")
            diffs = [mean_abs_diff(f[5], final[3].resize(f[5].size)) for f in hero_frames]
            steps = [mean_abs_diff(a[5], b[5]) for a, b in zip(hero_frames, hero_frames[1:])]
            changed_idx = [i + 1 for i, d in enumerate(steps) if d > 0.3]  # lossy video: ignore encoder noise
            print(f"  consecutive hero frames that differ: {len(changed_idx)} of {len(hero_frames) - 1}")
            if len(changed_idx) < 3:
                problems.append("hero: the number did not visibly animate between frames")
            else:
                t_start = hero_frames[changed_idx[0] - 1][1]  # the last still frame before the first change
                t_end = hero_frames[changed_idx[-1]][1]
                shown_for = t_end - t_start
                print(f"  observed: start value still shown from t={hero_frames[0][1]:.2f}s, first change after "
                      f"t={t_start:.2f}s, last change t={t_end:.2f}s -> rolling for {shown_for:.2f}s (spec 1.5s)")
            print(f"  first frame vs final screenshot: mean abs diff {diffs[0]:.2f}; last frame: {diffs[-1]:.2f}")
            if diffs[0] < 6.0:
                problems.append("hero: the first frame already shows (almost) the final value, no count-down from all nines")
            if diffs[-1] > 3.6:  # lossy video: the final value measures ~3 against the lossless screenshot
                problems.append("hero: the last frame does not show the final value")
        nodes = dump_nodes() or []
        descs = [d for _, d, _ in nodes if d.startswith("Available:")]
        print("  hero content description:", descs)
        if not descs:
            problems.append("hero: no 'Available: ... dinars' content description (TalkBack)")

        # 2) Return from the background: no replay, the final value is there immediately.
        sh("input keyevent 3")  # HOME
        time.sleep(2)
        back = burst("hero-return", 8, relaunch=False)
        shown = [(p, band_info(p)) for p, _ in back]
        hero_back = [(p, i) for p, i in shown if is_hero_frame(i[0], i[1], i[2])]
        print(f"  return from background: {len(hero_back)} of {len(back)} frames show the number")
        if not hero_back:
            problems.append("hero: no frame after returning from the background shows the number")
        else:
            ds = [mean_abs_diff(i[3], final[3]) for _, i in hero_back]
            print("  difference of each return frame to the final frame:", " ".join(f"{d:.2f}" for d in ds))
            # The very first frame can still be the window-open transition (the whole window is scaled), so
            # one frame may differ; a replayed count-down would differ in many frames.
            if sum(1 for d in ds if d > 1.0) > 1 or ds[-1] > 1.0:
                problems.append(f"hero: after returning from the background the number differs from the final value ({ds})")
        replayed = [l for l in adb("logcat", "-d", "-v", "time", "-s", "CashflowHero:I", check=False).splitlines() if "show action" in l]
        print("  hero show() calls after return from background:", len(replayed))
        if replayed:
            problems.append("hero: the animation replayed after returning from the background")
    except NotFound as e:
        screenshot("hero-FAILED")
        print("  NOT FOUND:", e)
        problems.append(f"hero: {e}")
    except ImportError:
        problems.append("hero: Pillow is not installed on the runner")
    finally:
        set_animations("0.0")
    if not app_alive():
        problems.append("hero: app process is not alive")
    problems += [f"hero: crash in log:\n{c}" for c in crash_log()]
    return problems


def main():
    problems = []
    try:
        for cfg in CONFIGS:
            problems += run_config(*cfg)
        problems += run_hero_animation()
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
