#!/usr/bin/env python3
"""
Turns raw app captures into framed store screenshots: brand gradient, a localized headline and
subline, and the capture inside a phone (or tablet) frame.

    python3 tools/frame-screenshots.py --platform android
    python3 tools/frame-screenshots.py --platform ios

Android reads build/screenshots/<play-locale>/<n>_<screen>.png and writes
fastlane/metadata/android/<play-locale>/images/phoneScreenshots/<n>.png at the capture's size.
Tablet captures under build/screenshots/tablet/<play-locale>/ (landscape, from a tablet AVD) are
framed with a side-by-side layout into both sevenInchScreenshots/ and tenInchScreenshots/.

iOS reads build/screenshots/<device>/<locale>/<n>_<screen>.png (device: iphone, ipad) and writes
fastlane/screenshots/<locale>/<device>-<n>_<screen>.png at the capture's size, which is what
deliver expects for each display class.

Needs Pillow. On macOS the text is rendered by tools/render-text.swift (compiled once into
build/) with the system font and the system text engine, so every script is shaped and wrapped
correctly with no font setup. Elsewhere, or with --pillow-text, Pillow draws the text with the
fonts in FONTS, which needs a Pillow with libraqm for Devanagari.
"""
import argparse
import glob
import json
import os
import re
import subprocess
import sys
import tempfile

from PIL import Image, ImageDraw, ImageFilter, ImageFont, features

RAQM = features.check("raqm")

# Brand gradient (RelayPony violet to transfer cyan), see app theme Color.kt.
VIOLET = (0x5A, 0x4F, 0xE0)
CYAN = (0x1E, 0x97, 0xA8)
BEZEL = (0x12, 0x12, 0x18)
BEZEL_EDGE = (0x3A, 0x3A, 0x48)

# Screen order is the store order. Keys match the capture file names (<n>_<screen>.png).
SCREENS = ["home", "sendto", "transfer", "received", "pair", "word"]

# headline, subline per language and screen. Keep headlines to two short lines.
TEXT = {
    "en": {
        "home": ("Send files to your other devices. Privately.", "No account. No cloud. Encrypted end to end."),
        "sendto": ("Nearby or across the internet", "Tap the device. RelayPony finds the way."),
        "transfer": ("Direct when it can, relayed when it must", "The relay only ever sees encrypted data."),
        "received": ("Photos, folders, text and links", "Folders arrive as a zip. Links open in a tap."),
        "pair": ("Pair once with a QR code", "Only paired devices can send to you."),
        "word": ("Or with a short word code", "Works across networks, no camera needed."),
    },
    "de": {
        "home": ("Dateien an deine anderen Geräte. Privat.", "Kein Konto. Keine Cloud. Ende-zu-Ende verschlüsselt."),
        "sendto": ("In der Nähe oder übers Internet", "Gerät antippen, RelayPony findet den Weg."),
        "transfer": ("Direkt wenn möglich, sonst per Relay", "Das Relay sieht nur verschlüsselte Daten."),
        "received": ("Fotos, Ordner, Text und Links", "Ordner kommen als ZIP an. Links öffnen mit einem Tipp."),
        "pair": ("Einmal per QR-Code koppeln", "Nur gekoppelte Geräte können dir senden."),
        "word": ("Oder mit einem kurzen Wortcode", "Funktioniert über Netzwerke hinweg, ohne Kamera."),
    },
    "es": {
        "home": ("Envía archivos a tus otros dispositivos. En privado.", "Sin cuenta. Sin nube. Cifrado de extremo a extremo."),
        "sendto": ("Cerca o a través de internet", "Toca el dispositivo. RelayPony encuentra el camino."),
        "transfer": ("Directo cuando puede, por relay cuando debe", "El relay solo ve datos cifrados."),
        "received": ("Fotos, carpetas, texto y enlaces", "Las carpetas llegan como zip. Los enlaces se abren con un toque."),
        "pair": ("Empareja una vez con un código QR", "Solo los dispositivos emparejados pueden enviarte."),
        "word": ("O con un código de palabras corto", "Funciona entre redes, sin cámara."),
    },
    "fr": {
        "home": ("Envoyez des fichiers à vos autres appareils. En privé.", "Sans compte. Sans cloud. Chiffré de bout en bout."),
        "sendto": ("À proximité ou via internet", "Touchez l'appareil, RelayPony trouve le chemin."),
        "transfer": ("En direct si possible, sinon via un relais", "Le relais ne voit que des données chiffrées."),
        "received": ("Photos, dossiers, texte et liens", "Les dossiers arrivent en zip. Les liens s'ouvrent d'un geste."),
        "pair": ("Jumelez une fois avec un code QR", "Seuls les appareils jumelés peuvent vous envoyer."),
        "word": ("Ou avec un court code de mots", "Fonctionne entre réseaux, sans caméra."),
    },
    "pt-BR": {
        "home": ("Envie arquivos para seus outros aparelhos. Em privado.", "Sem conta. Sem nuvem. Criptografia de ponta a ponta."),
        "sendto": ("Por perto ou pela internet", "Toque no aparelho. O RelayPony acha o caminho."),
        "transfer": ("Direto quando dá, por relay quando precisa", "O relay só vê dados criptografados."),
        "received": ("Fotos, pastas, texto e links", "Pastas chegam como zip. Links abrem com um toque."),
        "pair": ("Pareie uma vez com um código QR", "Só aparelhos pareados podem enviar para você."),
        "word": ("Ou com um código de palavras curto", "Funciona entre redes, sem câmera."),
    },
    "hi": {
        "home": ("अपने दूसरे डिवाइस पर फ़ाइलें भेजें। निजी तौर पर।", "कोई खाता नहीं। कोई क्लाउड नहीं। एंड-टू-एंड एन्क्रिप्टेड।"),
        "sendto": ("पास में या इंटरनेट के ज़रिए", "डिवाइस पर टैप करें, RelayPony रास्ता खुद ढूंढ लेता है।"),
        "transfer": ("जब हो सके सीधे, वरना रिले से", "रिले को सिर्फ़ एन्क्रिप्टेड डेटा दिखता है।"),
        "received": ("फ़ोटो, फ़ोल्डर, टेक्स्ट और लिंक", "फ़ोल्डर ज़िप बनकर आते हैं। लिंक एक टैप में खुलते हैं।"),
        "pair": ("QR कोड से एक बार पेयर करें", "सिर्फ़ पेयर किए गए डिवाइस ही आपको भेज सकते हैं।"),
        "word": ("या एक छोटे वर्ड कोड से", "अलग नेटवर्क पर भी चलता है, कैमरे के बिना।"),
    },
    "ja": {
        "home": ("ほかの端末へファイルを送る。プライベートに。", "アカウント不要。クラウド不要。エンドツーエンド暗号化。"),
        "sendto": ("近くでも、インターネット越しでも", "端末をタップするだけ。経路はRelayPonyが選びます。"),
        "transfer": ("できる限り直接、必要なときだけリレー", "リレーに見えるのは暗号化されたデータだけ。"),
        "received": ("写真、フォルダ、テキスト、リンク", "フォルダはzipで届き、リンクはワンタップで開けます。"),
        "pair": ("QRコードで一度だけペアリング", "送れるのはペアリング済みの端末だけ。"),
        "word": ("または短いワードコードで", "別のネットワークでも、カメラなしで。"),
    },
}

# Store locale folder -> text language.
LOCALE_LANG = {
    "en-US": "en", "de-DE": "de", "es-ES": "es", "fr-FR": "fr",
    "hi-IN": "hi", "hi": "hi", "ja-JP": "ja", "ja": "ja", "pt-BR": "pt-BR",
}

# Font candidates per script, in order of preference: (path, style name within a .ttc or None).
# macOS system fonts first, then common Linux ones for a quick check of the layout elsewhere.
FONTS = {
    "latin": {
        "bold": [
            ("/System/Library/Fonts/Avenir Next.ttc", "Bold"),
            ("/System/Library/Fonts/HelveticaNeue.ttc", "Bold"),
            ("/System/Library/Fonts/Supplemental/Arial Bold.ttf", None),
            ("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", None),
        ],
        "regular": [
            ("/System/Library/Fonts/Avenir Next.ttc", "Medium"),
            ("/System/Library/Fonts/HelveticaNeue.ttc", "Medium"),
            ("/System/Library/Fonts/Supplemental/Arial.ttf", None),
            ("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", None),
        ],
    },
    "hi": {
        "bold": [
            ("/System/Library/Fonts/Supplemental/Kohinoor Devanagari.ttc", "Bold"),
            ("/System/Library/Fonts/Supplemental/Kohinoor Devanagari.ttc", "Semibold"),
            ("/System/Library/Fonts/Supplemental/Devanagari MT.ttc", "Bold"),
            ("/System/Library/Fonts/Supplemental/Arial Unicode.ttf", None),
            ("/usr/share/fonts/truetype/noto/NotoSansDevanagari-Bold.ttf", None),
        ],
        "regular": [
            ("/System/Library/Fonts/Supplemental/Kohinoor Devanagari.ttc", "Medium"),
            ("/System/Library/Fonts/Supplemental/Kohinoor Devanagari.ttc", "Regular"),
            ("/System/Library/Fonts/Supplemental/Devanagari MT.ttc", "Regular"),
            ("/System/Library/Fonts/Supplemental/Arial Unicode.ttf", None),
            ("/usr/share/fonts/truetype/noto/NotoSansDevanagari-Regular.ttf", None),
        ],
    },
    "ja": {
        "bold": [
            ("/System/Library/Fonts/ヒラギノ角ゴシック W7.ttc", None),
            ("/System/Library/Fonts/ヒラギノ角ゴシック W6.ttc", None),
            ("/System/Library/Fonts/Hiragino Sans GB.ttc", "W6"),
            ("/System/Library/Fonts/Supplemental/Arial Unicode.ttf", None),
            ("/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc", "Noto Sans CJK JP"),
        ],
        "regular": [
            ("/System/Library/Fonts/ヒラギノ角ゴシック W4.ttc", None),
            ("/System/Library/Fonts/ヒラギノ角ゴシック W3.ttc", None),
            ("/System/Library/Fonts/Hiragino Sans GB.ttc", "W3"),
            ("/System/Library/Fonts/Supplemental/Arial Unicode.ttf", None),
            ("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc", "Noto Sans CJK JP"),
        ],
    },
}

_font_cache = {}

# Native (macOS) text rendering, see tools/render-text.swift. Set up by native_renderer().
_native = None


def native_renderer(root):
    """Path of the compiled helper, built on first use; None where it can't be built."""
    global _native
    if _native is not None:
        return _native or None
    source = os.path.join(root, "tools", "render-text.swift")
    if sys.platform != "darwin" or not os.path.exists(source):
        _native = False
        return None
    binary = os.path.join(root, "build", "render-text")
    if not os.path.exists(binary) or os.path.getmtime(binary) < os.path.getmtime(source):
        os.makedirs(os.path.dirname(binary), exist_ok=True)
        print("compiling tools/render-text.swift")
        result = subprocess.run(["swiftc", "-O", source, "-o", binary], capture_output=True, text=True)
        if result.returncode != 0:
            sys.exit("swiftc failed:\n" + result.stderr)
    _native = binary
    return binary


def render_native(binary, jobs):
    """Run the helper on a list of jobs; returns [(path, width, height, lines, size)]."""
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as handle:
        json.dump(jobs, handle)
        jobs_path = handle.name
    try:
        result = subprocess.run([binary, jobs_path], capture_output=True, text=True)
    finally:
        os.unlink(jobs_path)
    if result.returncode != 0:
        sys.exit("render-text failed:\n" + result.stderr)
    out = []
    for line in result.stdout.strip().splitlines():
        path, width, height, lines, size = line.rsplit(" ", 4)
        out.append((path, int(width), int(height), int(lines), int(size)))
    return out


def load_font(lang, weight, size):
    script = lang if lang in ("hi", "ja") else "latin"
    key = (script, weight, size)
    if key in _font_cache:
        return _font_cache[key]
    for path, style in FONTS[script][weight]:
        if not os.path.exists(path):
            continue
        if style is None:
            font = ImageFont.truetype(path, size)
        else:
            font = ttc_style(path, style, size)
            if font is None:
                continue
        _font_cache[key] = font
        return font
    sys.exit("no usable %s %s font found; add one to FONTS" % (script, weight))


def ttc_style(path, style, size):
    """The face in a .ttc whose family or style name contains `style`."""
    for index in range(0, 40):
        try:
            font = ImageFont.truetype(path, size, index=index)
        except (OSError, ValueError):
            break
        family, face = font.getname()
        if style.lower() == face.lower() or style.lower() in family.lower():
            return font
    return None


def wrap(text, font, max_width, lang):
    """Greedy wrap by words; Japanese (no spaces) wraps by character."""
    if lang == "ja":
        units = list(text)
        joiner = ""
    else:
        units = text.split(" ")
        joiner = " "
    no_start = "、。，．）」』】！？ー"
    lines, current = [], ""
    for unit in units:
        candidate = unit if not current else current + joiner + unit
        if font.getlength(candidate) <= max_width or not current or (lang == "ja" and unit in no_start):
            current = candidate
        else:
            lines.append(current)
            current = unit
    if current:
        lines.append(current)
    return lines


def fit_text(text, lang, weight, max_width, start_size, min_size, max_lines):
    size = start_size
    while True:
        font = load_font(lang, weight, size)
        lines = wrap(text, font, max_width, lang)
        if len(lines) <= max_lines or size <= min_size:
            return font, lines
        size -= 2


def gradient(w, h):
    """Diagonal violet-to-cyan gradient with a soft highlight, rendered small and upscaled."""
    sw, sh = 108, max(1, int(108 * h / w))
    px = []
    for y in range(sh):
        for x in range(sw):
            t = (x / (sw - 1) * 0.55 + y / (sh - 1) * 0.45)
            t = min(1.0, max(0.0, t))
            r = VIOLET[0] + (CYAN[0] - VIOLET[0]) * t
            g = VIOLET[1] + (CYAN[1] - VIOLET[1]) * t
            b = VIOLET[2] + (CYAN[2] - VIOLET[2]) * t
            # A light lift toward the top-left so the headline area breathes.
            lift = max(0.0, 0.14 - 0.14 * ((x / sw) ** 2 + (y / sh) ** 2) ** 0.5)
            px.append((int(r + (255 - r) * lift), int(g + (255 - g) * lift), int(b + (255 - b) * lift)))
    small = Image.new("RGB", (sw, sh))
    small.putdata(px)
    return small.resize((w, h), Image.BICUBIC)


def rounded_mask(size, radius):
    mask = Image.new("L", size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size[0] - 1, size[1] - 1), radius=radius, fill=255)
    return mask


def draw_text_pillow(canvas, lang, headline, sub, margin, text_w, scale, y):
    """Headline and subline with Pillow's own fonts. Returns the y below the text."""
    draw = ImageDraw.Draw(canvas)
    head_font, head_lines = fit_text(headline, lang, "bold", text_w, int(74 * scale), int(52 * scale), 2)
    sub_font, sub_lines = fit_text(sub, lang, "regular", text_w, int(38 * scale), int(31 * scale), 1)
    if len(sub_lines) > 1:
        sub_font, sub_lines = fit_text(sub, lang, "regular", text_w, int(36 * scale), int(30 * scale), 2)
    head_lh = int(head_font.size * (1.3 if lang in ("hi", "ja") else 1.18))
    sub_lh = int(sub_font.size * 1.35)
    # Pillow only accepts a language tag when libraqm is present; without it Devanagari is drawn
    # glyph by glyph and the matras land in the wrong place.
    lang_tag = {"hi": "hi", "ja": "ja"}.get(lang) if RAQM else None
    if lang == "hi" and not RAQM:
        print("warning: no libraqm in this Pillow; Hindi text will be mis-shaped", file=sys.stderr)
    for line in head_lines:
        draw.text((margin, y), line, font=head_font, fill=(255, 255, 255), language=lang_tag)
        y += head_lh
    y += int(12 * scale)
    for line in sub_lines:
        draw.text((margin, y), line, font=sub_font, fill=(236, 240, 255), language=lang_tag)
        y += sub_lh
    return y


def draw_text_native(canvas, binary, headline, sub, margin, text_w, scale, y):
    """Headline and subline rendered by the macOS text system. Returns the y below the text."""
    work = tempfile.mkdtemp(prefix="frame-text-")
    head_png = os.path.join(work, "head.png")
    sub_png = os.path.join(work, "sub.png")
    jobs = [
        {"text": headline, "size": int(74 * scale), "minSize": int(52 * scale), "maxLines": 2,
         "weight": "bold", "maxWidth": text_w, "color": [1, 1, 1, 1], "out": head_png},
        {"text": sub, "size": int(38 * scale), "minSize": int(31 * scale), "maxLines": 2,
         "weight": "medium", "maxWidth": text_w, "color": [0.925, 0.94, 1, 1], "out": sub_png},
    ]
    try:
        render_native(binary, jobs)
        head = Image.open(head_png).convert("RGBA")
        canvas.paste(head, (margin, y), head)
        y += head.height + int(6 * scale)
        subline = Image.open(sub_png).convert("RGBA")
        canvas.paste(subline, (margin, y), subline)
        y += subline.height
    finally:
        for path in (head_png, sub_png):
            if os.path.exists(path):
                os.unlink(path)
        os.rmdir(work)
    return y


def frame_one(raw_path, out_path, lang, screen, tablet, native):
    shot = Image.open(raw_path).convert("RGB")
    W, H = shot.size
    canvas = gradient(W, H).convert("RGBA")

    margin = int(W * 0.075)
    text_w = W - 2 * margin
    headline, sub = TEXT[lang][screen]

    landscape = W > H
    if landscape:
        return frame_landscape(canvas, shot, out_path, lang, headline, sub, native)

    # Type scale from the canvas width; tablets get relatively smaller type.
    scale = W / 1080.0 * (0.72 if tablet else 1.0)
    y = int(H * 0.055)
    if native:
        y = draw_text_native(canvas, native, headline, sub, margin, text_w, scale, y)
    else:
        y = draw_text_pillow(canvas, lang, headline, sub, margin, text_w, scale, y)
    # The frame starts at the same height on every screen of a set, lower only if the text needs it.
    text_bottom = max(y + int(H * 0.03), int(H * (0.19 if tablet else 0.225)))

    # Device frame: fills the width below the text and runs off the bottom edge.
    bezel = int(W * (0.016 if tablet else 0.022))
    frame_w = int(W * (0.86 if tablet else 0.80))
    screen_w = frame_w - 2 * bezel
    screen_h = int(screen_w * H / W)
    frame_h = screen_h + 2 * bezel
    fx = (W - frame_w) // 2
    fy = text_bottom
    radius = int(frame_w * (0.045 if tablet else 0.085))

    # Soft shadow under the frame.
    shadow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    sd = ImageDraw.Draw(shadow)
    sd.rounded_rectangle((fx + int(W * 0.01), fy + int(W * 0.03), fx + frame_w + int(W * 0.01), fy + frame_h + int(W * 0.03)),
                         radius=radius, fill=(0, 0, 0, 110))
    shadow = shadow.filter(ImageFilter.GaussianBlur(int(W * 0.035)))
    canvas = Image.alpha_composite(canvas, shadow)
    draw = ImageDraw.Draw(canvas)

    draw.rounded_rectangle((fx, fy, fx + frame_w, fy + frame_h), radius=radius, fill=BEZEL, outline=BEZEL_EDGE, width=max(2, bezel // 8))
    inner = shot.resize((screen_w, screen_h), Image.LANCZOS)
    inner_mask = rounded_mask(inner.size, max(8, radius - bezel))
    canvas.paste(inner, (fx + bezel, fy + bezel), inner_mask)

    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    canvas.convert("RGB").save(out_path, "PNG", optimize=True)
    return (W, H)


def frame_landscape(canvas, shot, out_path, lang, headline, sub, native):
    """Landscape tablets: text in a left column, the device frame on the right, both centred."""
    W, H = shot.size
    margin = int(W * 0.05)
    col_w = int(W * 0.35)
    scale = W / 1080.0 * 0.6

    # Render the text once to measure it, then place it vertically centred.
    probe = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    if native:
        text_h = draw_text_native(probe, native, headline, sub, 0, col_w, scale, 0)
    else:
        text_h = draw_text_pillow(probe, lang, headline, sub, 0, col_w, scale, 0)
    y = max(margin, (H - text_h) // 2)
    if native:
        draw_text_native(canvas, native, headline, sub, margin, col_w, scale, y)
    else:
        draw_text_pillow(canvas, lang, headline, sub, margin, col_w, scale, y)

    bezel = int(W * 0.012)
    radius = int(W * 0.03)
    frame_w = int(W * 0.60)
    screen_w = frame_w - 2 * bezel
    screen_h = int(screen_w * H / W)
    frame_h = screen_h + 2 * bezel
    fx = int(W * 0.42)
    fy = (H - frame_h) // 2

    shadow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle(
        (fx + int(W * 0.006), fy + int(W * 0.014), fx + frame_w + int(W * 0.006), fy + frame_h + int(W * 0.014)),
        radius=radius, fill=(0, 0, 0, 110))
    shadow = shadow.filter(ImageFilter.GaussianBlur(int(W * 0.02)))
    canvas = Image.alpha_composite(canvas, shadow)
    draw = ImageDraw.Draw(canvas)
    draw.rounded_rectangle((fx, fy, fx + frame_w, fy + frame_h), radius=radius, fill=BEZEL, outline=BEZEL_EDGE, width=max(2, bezel // 6))
    inner = shot.resize((screen_w, screen_h), Image.LANCZOS)
    canvas.paste(inner, (fx + bezel, fy + bezel), rounded_mask(inner.size, max(8, radius - bezel)))

    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    canvas.convert("RGB").save(out_path, "PNG", optimize=True)
    return (W, H)


def captures(folder):
    """(n, screen, path) for every <n>_<screen>.png in folder, in store order."""
    found = []
    for path in glob.glob(os.path.join(folder, "*.png")):
        m = re.match(r"(\d+)_([a-z]+)\.png$", os.path.basename(path))
        if m and m.group(2) in SCREENS:
            found.append((int(m.group(1)), m.group(2), path))
    return sorted(found)


def run_android(raw_root, out_root, native):
    done = 0
    for locale in sorted(os.listdir(raw_root)):
        folder = os.path.join(raw_root, locale)
        if not os.path.isdir(folder) or locale not in LOCALE_LANG:
            continue
        lang = LOCALE_LANG[locale]
        shots = captures(folder)
        if not shots:
            continue
        out_dir = os.path.join(out_root, locale, "images", "phoneScreenshots")
        for n, screen, path in shots:
            size = frame_one(path, os.path.join(out_dir, "%d.png" % n), lang, screen, tablet=False, native=native)
            print("%s/%d.png (%dx%d, %s)" % (locale, n, size[0], size[1], screen))
            done += 1
        # Leftover numbered files from an older, different set.
        produced = {n for n, _, _ in shots}
        for old in glob.glob(os.path.join(out_dir, "*.png")):
            m = re.match(r"(\d+)\.png$", os.path.basename(old))
            if m and int(m.group(1)) not in produced:
                os.remove(old)
                print("removed stale %s" % old)

    # Tablet captures: one framed set, written to both Play tablet slots.
    tablet_root = os.path.join(raw_root, "tablet")
    if os.path.isdir(tablet_root):
        for locale in sorted(os.listdir(tablet_root)):
            folder = os.path.join(tablet_root, locale)
            if not os.path.isdir(folder) or locale not in LOCALE_LANG:
                continue
            lang = LOCALE_LANG[locale]
            for n, screen, path in captures(folder):
                ten = os.path.join(out_root, locale, "images", "tenInchScreenshots", "%d.png" % n)
                size = frame_one(path, ten, lang, screen, tablet=True, native=native)
                seven = os.path.join(out_root, locale, "images", "sevenInchScreenshots", "%d.png" % n)
                os.makedirs(os.path.dirname(seven), exist_ok=True)
                with open(ten, "rb") as src, open(seven, "wb") as dst:
                    dst.write(src.read())
                print("%s/tablet %d.png (%dx%d, %s)" % (locale, n, size[0], size[1], screen))
                done += 1
    return done


def run_ios(raw_root, out_root, native):
    done = 0
    for device in ("iphone", "ipad"):
        device_root = os.path.join(raw_root, device)
        if not os.path.isdir(device_root):
            continue
        for locale in sorted(os.listdir(device_root)):
            folder = os.path.join(device_root, locale)
            if not os.path.isdir(folder) or locale not in LOCALE_LANG:
                continue
            lang = LOCALE_LANG[locale]
            for n, screen, path in captures(folder):
                out = os.path.join(out_root, locale, "%s-%d_%s.png" % (device, n, screen))
                size = frame_one(path, out, lang, screen, tablet=(device == "ipad"), native=native)
                print("%s/%s-%d_%s.png (%dx%d)" % (locale, device, n, screen, size[0], size[1]))
                done += 1
    return done


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--platform", choices=["android", "ios"], required=True)
    ap.add_argument("--raw", help="raw capture root (default: build/screenshots)")
    ap.add_argument("--out", help="output root (default: the fastlane tree for the platform)")
    ap.add_argument("--pillow-text", action="store_true", help="draw text with Pillow even on macOS")
    args = ap.parse_args()
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    raw = args.raw or os.path.join(root, "build", "screenshots")
    native = None if args.pillow_text else native_renderer(root)
    print("text: %s" % ("macOS text system" if native else "Pillow fonts"))
    if args.platform == "android":
        out = args.out or os.path.join(root, "fastlane", "metadata", "android")
        done = run_android(raw, out, native)
    else:
        out = args.out or os.path.join(root, "fastlane", "screenshots")
        done = run_ios(raw, out, native)
    if done == 0:
        sys.exit("no captures found under %s" % raw)
    print("framed %d screenshots into %s" % (done, out))


if __name__ == "__main__":
    main()
