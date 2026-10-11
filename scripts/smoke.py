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


def find(text, scroll=True, last=False, timeout=FIND_TIMEOUT):
    """Waits up to `timeout` (10 s) for a node with exactly this text; scrolls down while it is missing."""
    deadline = time.time() + timeout
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
            raise NotFound(f"text {text!r} not found within {timeout:.0f}s; visible texts: {visible}")
        time.sleep(0.5)


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


def run_config(name, night, font, size, density):
    print(f"== {name}: night={night} font={font} size={size or 'default'}")
    configure(night, font, size, density)
    try:
        launch()
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
        diagnostics()
        nodes = dump_nodes() or []
        print("  dump:", [(t, d, b) for t, d, b in nodes if t or d][:80])
        return [f"{name}: {e}"]
    problems = []
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
    """(ink pixel count, left extent, right extent, gray crop) of the hero band of a screenshot."""
    from PIL import Image
    img = Image.open(path).convert("L")
    crop = img.crop((0, HERO_BAND[0], img.width, HERO_BAND[1]))
    px = crop.load()
    w, h = crop.size
    cols = [False] * w
    count = 0
    for x in range(w):
        for y in range(h):
            if px[x, y] > INK_MIN_LUMA:
                cols[x] = True
                count += 1
    xs = [x for x, c in enumerate(cols) if c]
    return count, (xs[0] if xs else None), (xs[-1] if xs else None), crop


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

        # 1) Launch from closed with real animations: burst of screenshots from the moment of launch.
        set_animations("1.0")
        frames = burst("hero-anim", BURST_FRAMES, relaunch=True)
        time.sleep(3.5)
        screenshot("hero-anim-final")
        final = band_info(os.path.join(OUT, "hero-anim-final.png"))
        infos = []
        print("  frame  t(s)   ink   left right")
        for path, t in frames:
            count, left, right, crop = band_info(path)
            print(f"  {os.path.basename(path)[-6:-4]:>5} {t:5.2f} {count:6d} {left!s:>5} {right!s:>5}")
            infos.append((path, t, count, left, right, crop))
        hero_frames = [(p, t, c, l, r, crop) for p, t, c, l, r, crop in infos if MIN_INK_PIXELS <= c <= MAX_INK_PIXELS]
        if len(hero_frames) < 5:
            problems.append(f"hero: only {len(hero_frames)} frames show the number; expected an animation burst")
        else:
            make_filmstrip([(p, t, c, crop) for p, t, c, l, r, crop in hero_frames], "hero-anim-filmstrip.png")
            rights = [r for *_, r, _ in hero_frames]
            lefts = [l for _, _, _, l, _, _ in hero_frames]
            span_r = max(rights) - min(rights)
            span_l = max(lefts) - min(lefts)
            print(f"  hero frames: {len(hero_frames)}  right extent range {min(rights)}..{max(rights)} ({span_r}px)  "
                  f"left extent range {min(lefts)}..{max(lefts)} ({span_l}px)")
            # The TND label is static, so the right edge must not move at all. The left edge is the ink of
            # the first digit, whose shape differs from digit to digit (a '1' starts further right than a
            # '9'), so it gets a glyph-shape allowance; the slot layout itself is fixed-width.
            if span_r > 2:
                problems.append(f"hero: right extent moved by {span_r}px across frames (limit 2)")
            if span_l > 24:
                problems.append(f"hero: left extent moved by {span_l}px across frames (limit 24 for glyph shapes)")
            changed = sum(
                1 for a, b in zip(hero_frames, hero_frames[1:]) if mean_abs_diff(a[5], b[5]) > 0.5
            )
            print(f"  consecutive hero frames that differ: {changed} of {len(hero_frames) - 1}")
            if changed < 2:
                problems.append("hero: the number did not visibly animate between frames")
            diff_final = mean_abs_diff(hero_frames[-1][5], final[3])
            print(f"  last burst frame vs final frame: mean abs diff {diff_final:.2f}")
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
        hero_back = [(p, i) for p, i in shown if MIN_INK_PIXELS <= i[0] <= MAX_INK_PIXELS]
        print(f"  return from background: {len(hero_back)} of {len(back)} frames show the number")
        if not hero_back:
            problems.append("hero: no frame after returning from the background shows the number")
        else:
            worst = max(mean_abs_diff(i[3], final[3]) for _, i in hero_back)
            print(f"  worst difference to the final frame: {worst:.2f}")
            if worst > 1.0:
                problems.append(f"hero: after returning from the background the number differs from the final value (diff {worst:.2f})")
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
