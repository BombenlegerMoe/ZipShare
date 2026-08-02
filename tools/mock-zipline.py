"""Minimal mock of the Zipline v4 endpoints ZipShare's dashboard consumes.

Only for local verification of the Android client against the pinned API shapes.
Serves plain HTTP on 10.0.2.2:8099 as seen from the emulator.
"""
import base64
import hashlib
import hmac
import json
import os
import time
from urllib.parse import unquote_plus
import re
import struct
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TOKEN = "MOCKTOKEN123"

# Username/password sign-in. Set LOGIN_TOTP=1 in the environment to exercise the two-factor path.
LOGIN_USER = "zipshare"
LOGIN_PASS = "zipshare"
LOGIN_CODE = "123456"
LOGIN_TOTP = os.environ.get("LOGIN_TOTP") == "1"
SESSION = "mocksession"
# Two-factor enrollment. TOTP_SECRET is None until the client turns 2FA on.
TOTP_SECRET = None
# Logged-in devices. The current one is never in OTHER_SESSIONS and cannot be deleted.
CURRENT_SESSION = {"id": "sess-current", "client": "ZipShare Android",
                   "device": "Pixel 8 - Android 15", "createdAt": "2026-07-31T09:12:00.000Z"}
OTHER_SESSIONS = [
    {"id": "sess-1", "client": "Firefox 141", "device": "Windows 10",
     "createdAt": "2026-07-28T20:04:00.000Z"},
    {"id": "sess-2", "client": "Safari 18", "device": "iPad",
     "createdAt": "2026-07-22T11:47:00.000Z"},
]
TOTP_OFFERED = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP"
# Most real instances leave this off, so invite-less signup is refused by default (E1037).
OPEN_REGISTRATION = os.environ.get("OPEN_REGISTRATION") == "1"

# Video playback. Put any .mp4 at this path (default: sample.mp4 beside this script) and the
# file below is served from it, so the in-app player can be exercised without a real instance.
VIDEO_ID = "vid1"
VIDEO_PATH = os.environ.get("MOCK_VIDEO", os.path.join(os.path.dirname(__file__), "sample.mp4"))


def png(w, h, rgb):
    """Build a tiny solid-colour PNG without any third-party imaging library."""
    raw = b"".join(b"\x00" + bytes(rgb) * w for _ in range(h))

    def chunk(tag, data):
        c = tag + data
        return struct.pack(">I", len(data)) + c + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)

    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw))
        + chunk(b"IEND", b"")
    )


def _lzw(indices, min_code_size):
    """Minimal GIF LZW encoder - enough for the tiny frames below."""
    clear, end = 1 << min_code_size, (1 << min_code_size) + 1
    code_size = min_code_size + 1
    table = {(i,): i for i in range(clear)}
    nxt = end + 1
    bits = []

    def emit(code):
        for i in range(code_size):
            bits.append((code >> i) & 1)

    emit(clear)
    prev = ()
    for px in indices:
        cur = prev + (px,)
        if cur in table:
            prev = cur
            continue
        emit(table[prev])
        table[cur] = nxt
        nxt += 1
        if nxt > (1 << code_size) and code_size < 12:
            code_size += 1
        prev = (px,)
    if prev:
        emit(table[prev])
    emit(end)

    out = bytearray()
    for i in range(0, len(bits), 8):
        byte = 0
        for j, bit in enumerate(bits[i:i + 8]):
            byte |= bit << j
        out.append(byte)
    return bytes(out)


def _blocks(data):
    """Split LZW output into GIF sub-blocks, terminated by a zero-length block."""
    out = bytearray()
    for i in range(0, len(data), 255):
        piece = data[i:i + 255]
        out.append(len(piece))
        out += piece
    out.append(0)
    return bytes(out)


def animated_gif(size=120, frames=((230, 80, 110), (80, 200, 160), (250, 200, 80)), delay_cs=40):
    """
    A real multi-frame animated GIF89a, built by hand so the mock stays dependency-free.
    Used to prove the app animates GIFs rather than showing a single frozen frame.
    """
    n = len(frames)
    palette = b"".join(bytes(c) for c in frames) + b"\x00" * (3 * (4 - n) if n < 4 else 0)
    # Global colour table of 4 entries -> packed field 0b1_001_0_001 (GCT, 4 colours).
    out = bytearray(b"GIF89a")
    out += struct.pack("<HH", size, size) + bytes([0b10010001, 0, 0]) + palette
    # NETSCAPE2.0 extension: loop forever.
    out += b"\x21\xFF\x0BNETSCAPE2.0\x03\x01\x00\x00\x00"
    for idx in range(n):
        out += b"\x21\xF9\x04\x00" + struct.pack("<H", delay_cs) + b"\x00\x00"  # frame delay
        out += b"\x2C" + struct.pack("<HHHH", 0, 0, size, size) + b"\x00"       # image descriptor
        out += bytes([2]) + _blocks(_lzw([idx] * (size * size), 2))             # solid frame
    out += b"\x3B"
    return bytes(out)


COLOURS = [
    (232, 93, 117), (93, 173, 232), (247, 190, 84), (126, 217, 145), (168, 130, 232),
    (232, 141, 84), (84, 212, 212), (212, 84, 168), (150, 200, 90), (110, 130, 240),
]


def avatar_data_url():
    """
    The account avatar, as the base64 data URL Zipline stores and returns. A plain teal square is
    enough to prove the app renders the server's picture rather than the initial fallback.
    """
    return "data:image/png;base64," + base64.b64encode(png(96, 96, (74, 222, 203))).decode()


def totp_now(secret, step=0):
    """RFC 6238, SHA-1, 6 digits, 30s - the parameters otplib and every authenticator default to."""
    key = base64.b32decode(secret.upper() + "=" * (-len(secret) % 8))
    counter = int(time.time()) // 30 + step
    digest = hmac.new(key, struct.pack(">Q", counter), hashlib.sha1).digest()
    offset = digest[-1] & 0x0F
    code = struct.unpack(">I", digest[offset:offset + 4])[0] & 0x7FFFFFFF
    return f"{code % 1_000_000:06d}"


def totp_valid(secret, code):
    """One step of leeway either way, which is what real servers allow for clock drift."""
    if not secret or not code:
        return False
    return any(hmac.compare_digest(totp_now(secret, s), code) for s in (-1, 0, 1))


# A real, scannable QR of the otpauth URI for TOTP_OFFERED, baked in as a literal so this mock
# stays pure stdlib - encoding one from scratch would mean shipping a Reed-Solomon implementation
# for a test fixture. Regenerate with any QR encoder if TOTP_OFFERED ever changes.
TOTP_QR_B64 = (
    "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"
    "fPhtfi92sVvcxy52sYtd7GK3uI/dbX4vdrf5vVPejV3s3uI7Yhe72MVueTz4xIfc5vdiF7vFfexiF7vYxS52i/vY3eb3"
    "Yneb3zvl3djF7i2+I3axi13slseDT3zIbX4vdrFb3McudrGLXexit7iP3W1+L3a3+b1T3o1d7N7iO2IXu9jFbnk8+MSH"
    "3Ob3Yhe7xX3sYhe72MUudov72N3m92J3m9875d3Yxe4tviN2sYtd7JbHg098yG1+L3axW9zHLnaxi13sYre4j91tfi92"
    "t/m9U96NXeze4jtiF7vYxW55PPjEh9zm92IXu8V97GIXu9jFLnaL+9jd5vdid5vfO+Xd2MXuLb4jdrGLXeyWx4NPfMht"
    "fi92sVvcxy52sYtd7GK3uI/dbX4vdrf5vVPejV3s3uI7Yhe72MVueTz4xIfc5vdiF7vFfexiF7vYxS52i/vY3eb3Yneb"
    "3zvl3djF7i2+I3axi13slseDf51/sFPejd1tfu+Udxf3sYvdKe9+K999yruxi13sYhe75fHgX+cf7JR3Y3eb3zvl3cV9"
    "7GJ3yrvfynef8m7sYhe72MVueTz41/kHO+Xd2N3m9055d3Efu9id8u638t2nvBu72MUudrFbHg/+df7BTnk3drf5vVPe"
    "XdzHLnanvPutfPcp78YudrGLXeyWx4N/nX+wU96N3W1+75R3F/exi90p734r333Ku7GLXexiF7vl8eBf5x/slHdjd5vf"
    "O+XdxX3sYnfKu9/Kd5/ybuxiF7vYxW55PPjX+Qc75d3Y3eb3Tnl3cR+72J3y7rfy3ae8G7vYxS52sVseD/51/sFOeTd2"
    "t/m9U95d3Mcudqe8+6189ynvxi52sYtd7Jbng3+E745d7GIXu8X9W3zHKe+e8u7iPnanvHvKu7GLXexit7iP3anHh3zI"
    "t/LdsYtd7GK3uH+L7zjl3VPeXdzH7pR3T3k3drGLXewW97E79fiQD/lWvjt2sYtd7Bb3b/Edp7x7yruL+9id8u4p78Yu"
    "drGL3eI+dqceH/Ih38p3xy52sYvd4v4tvuOUd095d3Efu1PePeXd2MUudrFb3Mfu1ONDPuRb+e7YxS52sVvcv8V3nPLu"
    "Ke8u7mN3yrunvBu72MUudov72J16fMiHfCvfHbvYxS52i/u3+I5T3j3l3cV97E5595R3Yxe72MVucR+7U48P+ZBv5btj"
    "F7vYxW5x/xbfccq7p7y7uI/dKe+e8m7sYhe72C3uY3fq8SEf8q18d+xiF7vYLe7f4jtOefeUdxf3sTvl3VPejV3sYhe7"
    "xX3sTj0+5ENi9xbfEbvYxS52sYtd7GJ3yruxi93ifnG/uF/cx25xH7vF/Snvxu6Ud2O3PB98YPcW3xG72MUudrGLXexi"
    "d8q7sYvd4n5xv7hf3MducR+7xf0p78bulHdjtzwffGD3Ft8Ru9jFLnaxi13sYnfKu7GL3eJ+cb+4X9zHbnEfu8X9Ke/G"
    "7pR3Y7c8H3xg9xbfEbvYxS52sYtd7GJ3yruxi93ifnG/uF/cx25xH7vF/Snvxu6Ud2O3PB98YPcW3xG72MUudrGLXexi"
    "d8q7sYvd4n5xv7hf3MducR+7xf0p78bulHdjtzwffGD3Ft8Ru9jFLnaxi13sYnfKu7GL3eJ+cb+4X9zHbnEfu8X9Ke/G"
    "7pR3Y7c8H3xg9xbfEbvYxS52sYtd7GJ3yruxi93ifnG/uF/cx25xH7vF/Snvxu6Ud2O3PB98YPcW3xG72MUudrGLXexi"
    "d8q7sYvd4n5xv7hf3MducR+7xf0p78bulHdjtzwffGAXu1PejV3sbvN7sYvd4v6Ud2O3uD/l3dv8XuxOefctvmNxH7tT"
    "jw/5kNjF7pR3Yxe72/xe7GK3uD/l3dgt7k959za/F7tT3n2L71jcx+7U40M+JHaxO+Xd2MXuNr8Xu9gt7k95N3aL+1Pe"
    "vc3vxe6Ud9/iOxb3sTv1+JAPiV3sTnk3drG7ze/FLnaL+1Pejd3i/pR3b/N7sTvl3bf4jsV97E49PuRDYhe7U96NXexu"
    "83uxi93i/pR3Y7e4P+Xd2/xe7E559y2+Y3Efu1OPD/mQ2MXulHdjF7vb/F7sYre4P+Xd2C3uT3n3Nr8Xu1PefYvvWNzH"
    "7tTjQz4kdrE75d3Yxe42vxe72C3uT3k3dov7U969ze/F7pR33+I7FvexO/X4kA+JXexOeTd2sbvN78Uudov7U96N3eL+"
    "lHdv83uxO+Xdt/iOxX3sTj0+5ENiF7tT3o1d7GJ3m997i+9Y3C/uYxe7U96NXexiF7vYnfJu7E55N3axO/X4kA+JXexO"
    "eTd2sYvdbX7vLb5jcb+4j13sTnk3drGLXexid8q7sTvl3djF7tTjQz4kdrE75d3YxS52t/m9t/iOxf3iPnaxO+Xd2MUu"
    "drGL3Snvxu6Ud2MXu1OPD/mQ2MXulHdjF7vY3eb33uI7FveL+9jF7pR3Yxe72MUudqe8G7tT3o1d7E49PuRDYhe7U96N"
    "Xexid5vfe4vvWNwv7mMXu1PejV3sYhe72J3ybuxOeTd2sTv1+JAPiV3sTnk3drGL3W1+7y2+Y3G/uI9d7E55N3axi13s"
    "YnfKu7E75d3Yxe7U40M+JHaxO+Xd2MUudrf5vbf4jsX94j52sTvl3djFLnaxi90p78bulHdjF7tTjw/5kNjF7pR3Yxe7"
    "2N3m997iOxb3i/vYxe6Ud2MXu9jFLnanvBu7U96NXexOPT7kQ2IXu1PejV3sYre4v83vnfJu7G7ze4v72J3ybuxid8q7"
    "i/vYxS52sVvcL88HH9jF7pR3Yxe72C3ub/N7p7wbu9v83uI+dqe8G7vYnfLu4j52sYtd7Bb3y/PBB3axO+Xd2MUudov7"
    "2/zeKe/G7ja/t7iP3Snvxi52p7y7uI9d7GIXu8X98nzwgV3sTnk3drGL3eL+Nr93yruxu83vLe5jd8q7sYvdKe8u7mMX"
    "u9jFbnG/PB98YBe7U96NXexit7i/ze+d8m7sbvN7i/vYnfJu7GJ3yruL+9jFLnaxW9wvzwcf2MXulHdjF7vYLe5v83un"
    "vBu72/ze4j52p7wbu9id8u7iPnaxi13sFvfL88EHdrE75d3YxS52i/vb/N4p78buNr+3uI/dKe/GLnanvLu4j13sYhe7"
    "xf3yfPCBXexOeTd2sYvd4v42v3fKu7G7ze8t7mN3yruxi90p7y7uYxe72MVucb88H3xg9xbfEbvb/F7sYhe7xf0p78Zu"
    "cR+72J3y7uI+drGL3eJ+cb+4j13sTj0+5ENi9xbfEbvb/F7sYhe7xf0p78ZucR+72J3y7uI+drGL3eJ+cb+4j13sTj0+"
    "5ENi9xbfEbvb/F7sYhe7xf0p78ZucR+72J3y7uI+drGL3eJ+cb+4j13sTj0+5ENi9xbfEbvb/F7sYhe7xf0p78ZucR+7"
    "2J3y7uI+drGL3eJ+cb+4j13sTj0+5ENi9xbfEbvb/F7sYhe7xf0p78ZucR+72J3y7uI+drGL3eJ+cb+4j13sTj0+5ENi"
    "9xbfEbvb/F7sYhe7xf0p78ZucR+72J3y7uI+drGL3eJ+cb+4j13sTj0+5ENi9xbfEbvb/F7sYhe7xf0p78ZucR+72J3y"
    "7uI+drGL3eJ+cb+4j13sTj0+5ENi9xbfEbvb/F7sYhe7xf0p78ZucR+72J3y7uI+drGL3eJ+cb+4j13sTj0+5EO+le+O"
    "XexOeXdx/9d8321+L3axu83v/TXfd8q7sTv1+JAP+Va+O3axO+Xdxf1f8323+b3Yxe42v/fXfN8p78bu1ONDPuRb+e7Y"
    "xe6Udxf3f8333eb3Yhe72/zeX/N9p7wbu1OPD/mQb+W7Yxe7U95d3P8133eb34td7G7ze3/N953ybuxOPT7kQ76V745d"
    "7E55d3H/13zfbX4vdrG7ze/9Nd93yruxO/X4kA/5Vr47drE75d3F/V/zfbf5vdjF7ja/99d83ynvxu7U40M+5Fv57tjF"
    "7pR3F/d/zffd5vdiF7vb/N5f832nvBu7U48P+ZBv5btjF7tT3l3c/zXfd5vfi13sbvN7f833nfJu7E5dO/Tz8/Pf4/cP"
    "x8/Pz2O/fzh+fn4e+/3D8fPz89jvH46fn5/Hfv9w/Pz8PPb7h+Pn5+ex3z8cPz8/j/3+4fj5+Xns9w/Hz8/PY79/OH5+"
    "fh77/cPx8/Pz2O8fjp+fn8d+/3D8/Pw89vuH4+fn57HfPxw/Pz+P/f966Ik7MyD7hAAAAABJRU5ErkJggg=="
)


def parse_tokens(template, file):
    """
    Zipline's {type.prop::modifier} substitution, cut down to the tokens the app's own UI
    advertises. Enough to prove the client points at a page whose OpenGraph tags really do come
    from the user's view settings.
    """
    def sub(m):
        kind, prop, mod = m.group(1), m.group(2), m.group(3)
        source = file if kind == "file" else USER["user"]
        value = source.get(prop)
        if value is None:
            return m.group(0)
        if mod == "bytes" and isinstance(value, int):
            for unit in ("B", "KiB", "MiB", "GiB"):
                if value < 1024 or unit == "GiB":
                    return f"{value:.2f} {unit}" if unit != "B" else f"{value} B"
                value /= 1024
        if mod in ("locale", "date", "time"):
            return str(value).replace("T", ", ").replace("Z", "")
        return str(value)

    return re.sub(r"\{(file|user)\.(\w+)(?:::(\w+))?\}", sub, template)


def view_page(name):
    """The HTML Discord actually fetches: OpenGraph tags built from USER's view settings."""
    file = next((f for f in FILES if f["name"] == name), None) or {
        "name": name, "size": 0, "createdAt": "", "type": "application/octet-stream",
    }
    view = USER["user"].get("view") or {}
    raw = f"/u/{name}"

    def esc(s):
        return (str(s).replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace('"', "&quot;"))

    tags = [f'<meta property="og:image" content="{esc(raw)}">',
            '<meta name="twitter:card" content="summary_large_image">']
    if view.get("embed"):
        for key, prop in (("embedTitle", "og:title"), ("embedDescription", "og:description"),
                          ("embedSiteName", "og:site_name")):
            if view.get(key):
                tags.append(f'<meta property="{prop}" content="{esc(parse_tokens(view[key], file))}">')
        if view.get("embedColor"):
            tags.append(f'<meta name="theme-color" content="{esc(view["embedColor"])}">')
    return ("<!doctype html><html><head><meta charset=\"utf-8\">"
            f"<title>{esc(name)}</title>" + "".join(tags) +
            f'</head><body><img src="{esc(raw)}" alt="{esc(name)}"></body></html>')


def totp_qr_data_url():
    """The real server renders the QR itself and hands back exactly this shape of data URL."""
    return "data:image/png;base64," + TOTP_QR_B64
FILES = [
    {
        "id": f"file{i}",
        "name": f"screenshot-{i}.png",
        "type": "image/png",
        "size": 1024 * (40 + i * 7),
        "url": f"/u/screenshot-{i}.png",
        "createdAt": "2026-07-29T10:00:00.000Z",
        "deletesAt": None,
        "views": i * 3,
        "favorite": i % 4 == 0,
        "originalName": None,
        # a third of them live in the Screenshots folder so the folder filter is observable
        "folderId": "fld1" if i % 3 == 0 else None,
        "thumbnail": None,
    }
    for i in range(1, 26)
]

# A text entry so the in-app text viewer/editor has something to open.
TEXT_ID = "txt1"
TEXT_BODY = (
    "# notes.md\n\n"
    "Served by the mock so the in-app text viewer can be exercised.\n\n"
    "- edit me\n"
    "- saving uploads a NEW file, since Zipline cannot replace contents\n\n"
    "    indented code stays aligned because the viewer does not wrap\n"
)
FILES.insert(0, {
    "id": TEXT_ID, "name": "notes.md", "type": "text/markdown", "size": len(TEXT_BODY),
    "url": "/u/notes.md", "createdAt": "2026-07-31T09:00:00.000Z", "deletesAt": None,
    "views": 2, "favorite": False, "originalName": None, "folderId": None, "thumbnail": None,
})

# An animated GIF entry. It carries a thumbnail, which is exactly the case that used to show a
# frozen frame: the viewer must load the raw file, not the still.
FILES.insert(0, {
    "id": "gif1", "name": "loop.gif", "type": "image/gif", "size": 4096,
    "url": "/u/loop.gif", "createdAt": "2026-07-30T12:30:00.000Z", "deletesAt": None,
    "views": 7, "favorite": False, "originalName": None, "folderId": None,
    "thumbnail": {"path": "thumb-gif1"},
})

# A video entry so the in-app player has something to play. Served from VIDEO_PATH when present.
FILES.insert(0, {
    "id": VIDEO_ID, "name": "clip.mp4", "type": "video/mp4", "size": 1_200_000,
    "url": "/u/clip.mp4", "createdAt": "2026-07-30T12:00:00.000Z", "deletesAt": None,
    "views": 3, "favorite": False, "originalName": None, "folderId": None,
    "thumbnail": {"path": "thumb-vid1"},
})

FOLDERS = [
    {"id": "fld1", "name": "Screenshots", "public": False, "allowUploads": False, "parentId": None},
]

TAGS = [
    {"id": "tag1", "name": "work", "color": "#5DADE8"},
    {"id": "tag2", "name": "memes", "color": "#F7BE54"},
]

URLS = [
    {"id": "url1", "code": "abc123", "vanity": "docs", "destination": "https://example.com/a-very-long-document-link",
     "views": 42, "maxViews": 100, "enabled": True, "createdAt": "2026-07-20T09:00:00.000Z"},
    {"id": "url2", "code": "xy9z", "vanity": None, "destination": "https://example.org/second",
     "views": 5, "maxViews": None, "enabled": True, "createdAt": "2026-07-22T09:00:00.000Z"},
    {"id": "url3", "code": "off1", "vanity": "disabled-one", "destination": "https://example.net/third",
     "views": 40, "maxViews": None, "enabled": False, "createdAt": "2026-07-25T09:00:00.000Z"},
]

USERS_LIST = [
    {"id": "u1", "username": "zipshare", "role": "SUPERADMIN", "createdAt": "2026-01-01T00:00:00.000Z",
     "quota": {"filesQuota": "BY_BYTES", "maxBytes": "10.0gb", "maxFiles": None, "maxUrls": 50}},
    {"id": "u2", "username": "alice", "role": "ADMIN", "createdAt": "2026-02-01T00:00:00.000Z", "quota": None},
    {"id": "u3", "username": "bob", "role": "USER", "createdAt": "2026-03-01T00:00:00.000Z",
     "quota": {"filesQuota": "BY_FILES", "maxBytes": None, "maxFiles": 250, "maxUrls": None}},
]

LATEST_METRIC = {
    "id": "m1",
    "createdAt": "2026-07-29T00:00:00.000Z",
    "data": {
        "users": 3, "files": 25, "fileViews": 1432, "urls": 4, "urlViews": 87,
        "storage": 1503238553,
        "filesUsers": [
            {"username": "zipshare", "sum": 18, "storage": 1200000000, "views": 1100},
            {"username": "alice", "sum": 5, "storage": 250000000, "views": 300},
            {"username": "bob", "sum": 2, "storage": 53238553, "views": 32},
        ],
        "urlsUsers": [{"username": "zipshare", "sum": 4, "views": 87}],
        "types": [{"type": "image/png", "sum": 18}, {"type": "video/mp4", "sum": 4},
                  {"type": "application/pdf", "sum": 2}, {"type": "text/plain", "sum": 1}],
    },
}

# newest first, as getMetricsPoints orders by createdAt desc
METRIC_POINTS = [
    {"id": f"p{i}", "createdAt": f"2026-07-{29 - i:02d}T00:00:00.000Z",
     "users": 3, "files": 25 - i, "fileViews": 1432 - i * 40, "urls": 4,
     "urlViews": 87 - i, "storage": str(1503238553 - i * 40000000)}
    for i in range(0, 14)
]

INVITES = [
    {"id": "inv1", "code": "welcome1", "createdAt": "2026-07-01T00:00:00.000Z",
     "expiresAt": None, "uses": 2, "maxUses": 10,
     "inviter": {"id": "u1", "username": "zipshare", "role": "SUPERADMIN"}},
    {"id": "inv2", "code": "temp2", "createdAt": "2026-07-15T00:00:00.000Z",
     "expiresAt": "2026-08-15T00:00:00.000Z", "uses": 0, "maxUses": None,
     "inviter": {"id": "u1", "username": "zipshare", "role": "SUPERADMIN"}},
]

ZERO_FILES = [{"id": "z1", "name": "broken-1.png"}, {"id": "z2", "name": "broken-2.mp4"}]

# identifier -> bytes accumulated, for the chunked upload endpoint
PARTIALS = {}

# A representative slice of Zipline's settings object, covering each control type.
SERVER_SETTINGS = {
    "coreReturnHttpsUrls": True,
    "coreDefaultDomain": "",
    "coreTempDirectory": "/tmp/zipline",
    "filesRoute": "/u",
    "filesLength": 6,
    "filesDefaultFormat": "random",
    "filesDisabledExtensions": "",
    "filesMaxFileSize": "100mb",
    "filesDefaultExpiration": "",
    "filesAssumeMimetypes": False,
    "filesRemoveGpsMetadata": True,
    "featuresImageCompression": True,
    "featuresRobotsTxt": True,
    "featuresHealthcheck": True,
    "featuresUserRegistration": False,
    "featuresOauthRegistration": False,
    "featuresDeleteOnMaxViews": True,
    "featuresMetricsEnabled": True,
    "featuresMetricsAdminOnly": False,
    "featuresThumbnailsEnabled": True,
    "invitesEnabled": True,
    "invitesLength": 8,
    "ratelimitEnabled": False,
    "ratelimitMax": 10,
    "websiteTitle": "Zipline",
    "websiteExternalLinks": "",
    "chunksEnabled": True,
    "chunksMax": "95mb",
    "chunksSize": "25mb",
    "tasksDeleteInterval": "30m",
    "tasksMetricsInterval": "1h",
}

# Keys pinned by env/config file on the server; Zipline reports these as "tampered".
TAMPERED = ["coreTempDirectory", "filesMaxFileSize"]

STATS = {
    "filesUploaded": 25,
    "favoriteFiles": 7,
    "views": 1432,
    "avgViews": 57.28,
    "storageUsed": 1503238553.0,
    "avgStorageUsed": 60129542.1,
    "urlsCreated": 4,
    "urlViews": 87,
    "sortTypeCount": {"image/png": 18, "video/mp4": 4, "application/pdf": 2, "text/plain": 1},
}

USER = {
    "user": {
        "id": "u1",
        "username": "zipshare",
        "role": "SUPERADMIN",
        "avatar": avatar_data_url(),
        "quota": {
            "filesQuota": "BY_BYTES",
            "maxBytes": "10.0gb",
            "maxFiles": None,
            "maxUrls": 50,
        },
        # Zipline's per-user "Viewing Files" settings, edited through PATCH /api/user.
        "view": {
            "enabled": True,
            "disableTextFiles": False,
            "showMimetype": True,
            "showTags": False,
            "showFolder": False,
            "content": "<h1>{file.name}</h1>",
            "align": "center",
            "embed": True,
            "embedMediaOnly": False,
            "embedTitle": "{file.name}",
            "embedDescription": "Uploaded by {user.username}",
            "embedSiteName": "ZipShare demo",
            "embedColor": "#4f46e5",
        },
    }
}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        auth = self.headers.get("authorization")
        shown = "none" if not auth else ("VALID" if auth == TOKEN else "WRONG")
        print(f"  {self.command} {self.path}  auth={shown}", flush=True)

    def _send(self, code, body, ctype="application/json", extra_headers=()):
        if isinstance(body, (dict, list)):
            body = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        for name, value in extra_headers:
            self.send_header(name, value)
        self.end_headers()
        self.wfile.write(body)

    def _err(self, code, msg, status):
        self._send(status, {"error": f"E{code}: {msg}", "code": code, "statusCode": status})

    def _send_ranged(self, data, ctype):
        """
        Range-aware response. ExoPlayer issues `Range: bytes=...` and expects 206 with
        Content-Range; answering 200 with the whole body makes seeking fail and can stall
        startup, so this mirrors what a real file server does.
        """
        rng = self.headers.get("Range")
        total = len(data)
        start, end = 0, total - 1
        status = 200
        if rng:
            m = re.match(r"bytes=(\d*)-(\d*)", rng)
            if m:
                if m.group(1):
                    start = int(m.group(1))
                if m.group(2):
                    end = int(m.group(2))
                end = min(end, total - 1)
                status = 206
        chunk = data[start:end + 1]
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(len(chunk)))
        if status == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{total}")
        self.end_headers()
        self.wfile.write(chunk)
        print(f"    -> media {status} bytes {start}-{end}/{total}", flush=True)

    def do_GET(self):
        path = self.path.split("?")[0]
        query = self.path.split("?")[1] if "?" in self.path else ""

        # The view page. Unauthenticated on purpose: Discord and friends fetch it with no token,
        # which is the whole reason it exists as a separate route from /u/.
        m = re.match(r"^/view/([\w.\-]+)$", path)
        if m:
            return self._send(200, view_page(m.group(1)).encode(), "text/html; charset=utf-8")

        if path == "/api/healthcheck":
            return self._send(200, {"pass": True})

        # Exchanges the login session cookie for the API token. Authenticated by the cookie, NOT
        # by the token header - this is the one call the app makes before it has a token.
        if path == "/api/user/token":
            cookie = self.headers.get("cookie") or ""
            if f"zipline_session={SESSION}" not in cookie:
                print("    -> /api/user/token WITHOUT session cookie", flush=True)
                return self._err(2001, "Unauthorized", 401)
            print("    -> token issued from session", flush=True)
            return self._send(200, {"token": TOKEN})

        # Everything else needs the raw token, no Bearer prefix.
        if self.headers.get("authorization") != TOKEN:
            return self._err(2001, "Invalid token", 401)

        if path == "/api/user/mfa/totp":
            # The QR is withheld once 2FA is on - that absence is how the client knows the state.
            if TOTP_SECRET:
                return self._send(200, {"secret": TOTP_SECRET, "qrcode": None})
            return self._send(200, {"secret": TOTP_OFFERED, "qrcode": totp_qr_data_url()})
        if path == "/api/user/sessions":
            return self._send(200, {"current": CURRENT_SESSION, "other": OTHER_SESSIONS})
        if path == "/api/user":
            return self._send(200, USER)
        if path == "/api/version":
            # Full shape from src/server/routes/api/version.ts. `data` is only present when the
            # instance could reach GitHub to compare releases, so VERSION_OFFLINE=1 drops it -
            # an air-gapped server must not make the client claim anything about updates.
            details = {"version": "4.6.5", "sha": "ca75062f1b8e4d3a9c0e7b21"}
            if os.environ.get("VERSION_OFFLINE") == "1":
                return self._send(200, {"details": details, "cached": False})
            latest = os.environ.get("VERSION_LATEST", "v4.6.5")
            return self._send(200, {
                "data": {
                    "isUpstream": False,
                    "isRelease": True,
                    "isLatest": latest == "v4.6.5",
                    "version": {"tag": "v4.6.5", "sha": "ca75062f1b8e4d3a9c0e7b21",
                                "url": "https://github.com/diced/zipline"},
                    "latest": {"tag": latest, "url": "https://github.com/diced/zipline/releases"},
                },
                "details": details,
                "cached": False,
            })
        if path == "/api/user/stats":
            # Derived from the live FILES list so an upload visibly moves the numbers.
            live = dict(STATS)
            live["filesUploaded"] = len(FILES)
            return self._send(200, live)
        if path == "/api/user/folders":
            return self._send(200, FOLDERS)
        if path == "/api/user/recent":
            m = re.search(r"take=(\d+)", query)
            take = int(m.group(1)) if m else 3
            if not 1 <= take <= 100:
                return self._err(1000, "Invalid request schema", 400)
            print(f"    -> serving {take} recent files", flush=True)
            return self._send(200, FILES[:take])
        if path == "/api/user/files":
            per = int(re.search(r"perpage=(\d+)", query).group(1)) if "perpage=" in query else 30
            pg = int(re.search(r"page=(\d+)", query).group(1)) if "page=" in query else 1
            folder = re.search(r"folder=(\w+)", query)
            pool = [f for f in FILES if f["folderId"] == folder.group(1)] if folder else list(FILES)

            # favourite filter
            if "favorite=true" in query:
                pool = [f for f in pool if f.get("favorite")]

            # search: searchField + searchQuery, matching the server's enum
            sq = re.search(r"searchQuery=([^&]*)", query)
            sf = re.search(r"searchField=(\w+)", query)
            if sq and sq.group(1):
                needle = unquote_plus(sq.group(1)).lower()
                field = sf.group(1) if sf else "name"
                if field == "tags":
                    pool = [f for f in pool
                            if any(needle in t["name"].lower() for t in f.get("tags", []))]
                else:
                    pool = [f for f in pool if needle in str(f.get(field) or "").lower()]

            # sort: sortBy + order
            sb = re.search(r"sortBy=(\w+)", query)
            od = re.search(r"order=(\w+)", query)
            key = sb.group(1) if sb else "createdAt"
            reverse = (od.group(1) if od else "desc") == "desc"
            pool = sorted(pool, key=lambda f: (f.get(key) is None, f.get(key) or 0), reverse=reverse)

            start = (pg - 1) * per
            chunk = pool[start:start + per]
            pages = max(1, -(-len(pool) // per))
            print(f"    -> files page {pg}/{pages}, {len(chunk)} rows, sort={key} "
                  f"{'desc' if reverse else 'asc'}"
                  f"{', q=' + unquote_plus(sq.group(1)) if sq and sq.group(1) else ''}"
                  f"{', favourites' if 'favorite=true' in query else ''}"
                  f"{', folder=' + folder.group(1) if folder else ''}", flush=True)
            return self._send(200, {"page": chunk, "total": len(pool), "pages": pages})
        if path == "/api/user/tags":
            return self._send(200, TAGS)
        # Zipline serves the avatar as a bare data-URL string, not wrapped in JSON.
        if path == "/api/user/avatar":
            # Must follow the account: a client that clears the avatar and then reads it back
            # here would otherwise see the old one and think the removal failed.
            current = USER["user"]["avatar"]
            return self._send(200, (current or "").encode(), "text/plain")
        if path == "/api/user/urls":
            return self._send(200, URLS)
        if path == "/api/users":
            return self._send(200, USERS_LIST)
        if path == "/api/stats":
            all_time = "all=true" in query
            pts = METRIC_POINTS if all_time else METRIC_POINTS[:8]
            print(f"    -> stats all={all_time}, {len(pts)} points", flush=True)
            return self._send(200, {"latest": LATEST_METRIC, "points": pts})
        if path == "/api/auth/invites":
            return self._send(200, INVITES)
        if path == "/api/server/clear_zeros":
            return self._send(200, {"files": ZERO_FILES})
        if path == "/api/server/settings":
            return self._send(200, {"settings": SERVER_SETTINGS, "tampered": TAMPERED})

        # Thumbnail paths contain hyphens, which \w does not match - every thumbnail 404'd.
        m = re.match(r"^/api/user/files/([\w.-]+)/raw$", path)
        if m:
            fid = m.group(1)
            # Serve a real video when one is present, so playback can actually be exercised.
            # Drop any .mp4 next to this script and reference it from VIDEO_FILE.
            if fid == TEXT_ID:
                print("    -> serving text body", flush=True)
                return self._send(200, TEXT_BODY.encode(), "text/markdown; charset=utf-8")
            if fid == VIDEO_ID and os.path.exists(VIDEO_PATH):
                data = open(VIDEO_PATH, "rb").read()
                return self._send_ranged(data, "video/mp4")
            # The animated original vs its static thumbnail - the app must request the former.
            if fid == "gif1":
                print("    -> serving ANIMATED gif (raw)", flush=True)
                return self._send(200, animated_gif(), "image/gif")
            if fid == "thumb-gif1":
                print("    -> serving STATIC gif thumbnail", flush=True)
                return self._send(200, png(160, 160, (230, 80, 110)), "image/png")
            if fid == "thumb-vid1":
                return self._send(200, png(160, 160, (90, 120, 200)), "image/png")
            idx = int(fid.replace("file", "") or 1)
            return self._send(200, png(160, 160, COLOURS[(idx - 1) % len(COLOURS)]), "image/png")

        self._err(9002, "Not found", 404)

    def do_POST(self):
        # Python wants every global declared before its first use in the function.
        global TOTP_SECRET, LOGIN_USER, LOGIN_PASS

        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""
        path = self.path.split("?")[0]

        # Login and register are the unauthenticated POSTs - the app has no token yet at this
        # point, so the token check must come after these branches, not before them.
        if path not in ("/api/auth/login", "/api/auth/register") and \
                self.headers.get("authorization") != TOKEN:
            return self._err(2001, "Invalid token", 401)

        if path == "/api/upload":
            # Log the multipart part headers so the uploaded filename + content-type are visible.
            head = raw[:600].decode("latin-1", "replace")
            fn = re.search(r'filename="([^"]*)"', head)
            ct = re.search(r"Content-Type:\s*([^\r\n]+)", head, re.I)
            zh = {k: v for k, v in self.headers.items() if k.lower().startswith("x-zipline")}
            print(f"    -> UPLOAD {length}B filename={fn.group(1) if fn else '<none>'!r} "
                  f"part-ct={ct.group(1).strip() if ct else '<none>'!r} zh={zh}", flush=True)
            # Prepend to FILES so /api/user/recent really does change after an upload -
            # otherwise the dashboard-refresh test would pass without proving anything.
            n = len(FILES) + 1
            new = {"id": f"upl{n}", "name": f"uploaded-{n}.md", "type": "text/markdown",
                   "size": length, "url": f"/u/uploaded-{n}.md",
                   "createdAt": "2026-07-29T12:00:00.000Z", "deletesAt": None,
                   "views": 0, "favorite": False, "originalName": None,
                   "folderId": None, "thumbnail": None}
            FILES.insert(0, new)
            print(f"    -> upload, {length} bytes -> {new['name']} (now {len(FILES)} files)", flush=True)
            return self._send(200, {"files": [new]})
        if path == "/api/upload/partial":
            h = self.headers
            rng = h.get("content-range", "")
            m = re.match(r"bytes (\d+)-(\d+)/(\d+)", rng or "")
            if not m:
                print(f"    !! PARTIAL bad content-range {rng!r}", flush=True)
                return self._err(1002, "Invalid partial upload", 400)
            start, end, total = (int(x) for x in m.groups())
            ident = h.get("x-zipline-p-identifier")
            last = h.get("x-zipline-p-lastchunk") == "true"
            fname = h.get("x-zipline-p-filename")
            clen = h.get("x-zipline-p-content-length")

            # Zipline asserts the received body length matches end-start, and rejects end > total.
            body_len = len(raw.split(b"\r\n\r\n", 1)[1]) - len(b"\r\n") if b"\r\n\r\n" in raw else 0
            if end > total:
                return self._err(1002, "chunk end beyond total", 400)
            if start == 0:
                ident = f"partial{len(PARTIALS) + 1}"
                PARTIALS[ident] = 0
            if ident not in PARTIALS:
                return self._err(1003, "Partial upload identifier is invalid", 400)
            PARTIALS[ident] += end - start
            print(f"    -> PARTIAL {rng} id={ident} last={last} fname={fname} "
                  f"declared-total={clen} accumulated={PARTIALS[ident]}", flush=True)

            if last:
                if PARTIALS[ident] != total:
                    return self._err(1002, f"assembled {PARTIALS[ident]} != {total}", 400)
                n = len(FILES) + 1
                new = {"id": f"prt{n}", "name": f"chunked-{n}.bin", "type": "application/octet-stream",
                       "size": total, "url": f"/u/chunked-{n}.bin",
                       "createdAt": "2026-07-29T12:00:00.000Z", "deletesAt": None, "views": 0,
                       "favorite": False, "originalName": None, "folderId": None, "thumbnail": None}
                FILES.insert(0, new)
                return self._send(200, {"files": [dict(new, pending=True)],
                                        "partialSuccess": True, "partialIdentifier": ident})
            return self._send(200, {"files": [], "partialSuccess": True,
                                    "partialIdentifier": ident})
        if path == "/api/auth/login":
            # Login is the one unauthenticated POST: it opens a session rather than returning a
            # token, exactly like the real server. LOGIN_TOTP toggles the two-factor branch.
            body = json.loads(raw or b"{}")
            user, pw, code = body.get("username"), body.get("password"), body.get("code")
            if user != LOGIN_USER or pw != LOGIN_PASS:
                print(f"    -> login FAILED for {user!r}", flush=True)
                return self._err(1044, "Invalid username or password", 401)
            if LOGIN_TOTP and not code:
                print("    -> login needs TOTP", flush=True)
                return self._send(200, {"totp": True})
            if LOGIN_TOTP and code != LOGIN_CODE:
                print(f"    -> login bad TOTP {code!r}", flush=True)
                return self._err(1045, "Invalid code", 401)
            print(f"    -> login OK for {user!r} (session cookie set)", flush=True)
            # USER is already {"user": {...}} - wrapping it again would double-nest.
            self._send(200, USER, extra_headers=[
                ("Set-Cookie", f"zipline_session={SESSION}; Path=/; HttpOnly"),
            ])
            return
        if path == "/api/auth/register":
            # Mirrors src/server/routes/api/auth/register.ts: the invite is looked up by id OR
            # code, checked for expiry and exhaustion, then its use count goes up. Registration
            # ends with a session cookie, not a token - same as login - so the client still has
            # to spend it on /api/user/token.
            body = json.loads(raw or b"{}")
            name = str(body.get("username", "")).strip()
            pw = str(body.get("password", "")).strip()
            code = body.get("code")
            if not name or not pw:
                return self._err(1000, "body/username Required", 400)
            if code is None:
                if not OPEN_REGISTRATION:
                    print("    -> register REFUSED: open registration disabled", flush=True)
                    return self._err(1037, "user registration is disabled", 403)
            else:
                inv = next(
                    (i for i in INVITES if i["code"] == code or i["id"] == code), None
                )
                if inv is None:
                    print(f"    -> register REFUSED: no such invite {code!r}", flush=True)
                    return self._err(1035, "invalid invite code", 400)
                if inv["maxUses"] is not None and inv["uses"] >= inv["maxUses"]:
                    print(f"    -> register REFUSED: invite {code!r} used up", flush=True)
                    return self._err(1035, "invite code is expired", 400)
                inv["uses"] += 1
            if name == LOGIN_USER or any(u["username"] == name for u in USERS_LIST):
                print(f"    -> register REFUSED: {name!r} taken", flush=True)
                return self._err(1039, "user already exists", 400)
            USERS_LIST.append({"id": f"u{len(USERS_LIST) + 1}", "username": name, "role": "USER",
                               "createdAt": "2026-07-31T00:00:00.000Z", "quota": None})
            # Deliberately NO session cookie. The real server does set one, but it is Secure
            # whenever core.returnHttpsUrls is on, so a client that assumes it arrives registers
            # the account and then cannot fetch a token - which is exactly the bug this omission
            # keeps fixed. The client must log in explicitly afterwards.
            LOGIN_USER, LOGIN_PASS = name, pw
            print(f"    -> register OK: {name!r} via invite {code!r} (no session cookie)",
                  flush=True)
            self._send(200, {"user": USERS_LIST[-1]})
            return
        if path == "/api/user/mfa/totp":
            # Mirrors src/server/routes/api/user/mfa/totp.ts. The code is checked with a real
            # RFC 6238 verification rather than a fixed string, so a wrong-but-plausible code
            # from an authenticator fails here the same way it would on the real server.
            body = json.loads(raw or b"{}")
            code, secret = str(body.get("code", "")), str(body.get("secret", ""))
            if not totp_valid(secret, code):
                print(f"    -> totp enable REFUSED: bad code {code!r}", flush=True)
                return self._err(1045, "invalid code", 400)
            TOTP_SECRET = secret
            print(f"    -> totp ENABLED with secret {secret}", flush=True)
            return self._send(200, USER)
        if path == "/api/user/tags":
            body = json.loads(raw or b"{}")
            if not str(body.get("name", "")).strip():
                return self._err(1001, "name is required", 400)
            tag = {"id": f"tag{len(TAGS) + 1}", "name": body["name"],
                   "color": body.get("color", "#888888")}
            TAGS.append(tag)
            print(f"    -> tag created: {tag['name']} {tag['color']}", flush=True)
            return self._send(200, tag)
        if path == "/api/user/folders":
            body = json.loads(raw or b"{}")
            if not str(body.get("name", "")).strip():
                return self._err(1001, "name is required", 400)
            new = {"id": f"fld{len(FOLDERS) + 1}", "name": body["name"],
                   "public": bool(body.get("isPublic", False)),
                   "allowUploads": False, "parentId": body.get("parentId")}
            FOLDERS.append(new)
            print(f"    -> folder created: {body} -> {new['id']}", flush=True)
            return self._send(200, new)
        if path == "/api/user/urls":
            body = json.loads(raw or b"{}")
            dest = str(body.get("destination", ""))
            if not dest.startswith("http"):
                return self._err(1001, "destination must be a http url", 400)
            if "vanity" in body and body["vanity"] is None:
                # zod .optional() rejects an explicit null - fail like the real server would.
                return self._err(1001, "vanity: expected string, received null", 400)
            n = len(URLS) + 1
            new = {"id": f"url{n}", "code": f"c{n}x", "vanity": body.get("vanity"),
                   "destination": dest, "views": 0, "maxViews": None, "enabled": True,
                   "createdAt": "2026-07-30T00:00:00.000Z"}
            URLS.insert(0, new)
            print(f"    -> url created: {body} -> {new['code']}", flush=True)
            return self._send(200, dict(new, url=f"/go/{body.get('vanity') or new['code']}"))
        if path == "/api/users":
            body = json.loads(raw or b"{}")
            if not str(body.get("username", "")).strip() or not str(body.get("password", "")).strip():
                return self._err(1001, "username and password are required", 400)
            new = {"id": f"u{len(USERS_LIST) + 1}", "username": body["username"],
                   "role": body.get("role", "USER"),
                   "createdAt": "2026-07-30T00:00:00.000Z", "quota": None}
            USERS_LIST.append(new)
            print(f"    -> user created: {new['username']} role={new['role']}", flush=True)
            return self._send(200, new)
        if path == "/api/auth/invites":
            body = json.loads(raw or b"{}")
            inv = {"id": f"inv{len(INVITES) + 1}", "code": f"code{len(INVITES) + 1}",
                   "createdAt": "2026-07-29T00:00:00.000Z", "expiresAt": body.get("expiresAt"),
                   "uses": 0, "maxUses": body.get("maxUses"),
                   "inviter": {"id": "u1", "username": "zipshare", "role": "SUPERADMIN"}}
            INVITES.append(inv)
            print(f"    -> invite created: {inv['code']} maxUses={inv['maxUses']}", flush=True)
            return self._send(200, inv)
        if path == "/api/server/requery_size":
            body = json.loads(raw or b"{}")
            return self._send(200, {"status": f"Re-queried 25 files "
                                              f"(forceUpdate={body.get('forceUpdate')}, "
                                              f"forceDelete={body.get('forceDelete')})"})
        if path == "/api/server/thumbnails":
            body = json.loads(raw or b"{}")
            return self._send(200, {"status": f"Thumbnails queued (rerun={body.get('rerun')})"})
        self._err(9002, "Not found", 404)

    def do_PATCH(self):
        if self.headers.get("authorization") != TOKEN:
            return self._err(2001, "Invalid token", 401)
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""
        path = self.path.split("?")[0]

        # Editing your own account. Mirrors src/server/routes/api/user/index.ts, including the
        # current-password rules, since a client that forgets currentPassword must fail here too.
        if path == "/api/user":
            global LOGIN_PASS, LOGIN_USER, OTHER_SESSIONS
            body = json.loads(raw or b"{}")
            me = USER["user"]
            if "password" in body and body["password"] is not None:
                if not body.get("currentPassword"):
                    return self._err(1067, "current password is required", 400)
                if body["currentPassword"] != LOGIN_PASS:
                    print("    -> password change REFUSED: wrong current password", flush=True)
                    return self._err(1066, "invalid current password", 403)
                LOGIN_PASS = body["password"]
                # The real server drops every other session on a password change.
                OTHER_SESSIONS = []
                print("    -> password changed; other sessions invalidated", flush=True)
            if body.get("username"):
                if any(u["username"] == body["username"] for u in USERS_LIST if u["id"] != "u1"):
                    return self._err(1038, "username already taken", 400)
                LOGIN_USER = me["username"] = body["username"]
                for u in USERS_LIST:
                    if u["id"] == "u1":
                        u["username"] = body["username"]
                print(f"    -> username is now {body['username']!r}", flush=True)
            # Present-but-null clears the avatar; absent leaves it alone. That distinction is
            # the whole reason the client sends a hand-built JSON null for removal.
            if "avatar" in body:
                me["avatar"] = body["avatar"]
                print(f"    -> avatar {'cleared' if body['avatar'] is None else 'updated'}",
                      flush=True)
            if body.get("view"):
                me.setdefault("view", {}).update(body["view"])
                print(f"    -> view settings: {body['view']}", flush=True)
            return self._send(200, USER)

        # Bulk favourite / move. Must be matched before the single-file route below, since
        # "transaction" would otherwise look like a file id.
        if path == "/api/user/files/transaction":
            body = json.loads(raw or b"{}")
            ids = set(body.get("files") or [])
            if not ids:
                return self._err(1000, "files must not be empty", 400)
            touched = 0
            for f in FILES:
                if f["id"] in ids:
                    if "favorite" in body:
                        f["favorite"] = bool(body["favorite"])
                    if body.get("folder"):
                        f["folderId"] = body["folder"]
                    touched += 1
            print(f"    -> bulk PATCH {touched} file(s): "
                  f"{ {k: v for k, v in body.items() if k != 'files'} }", flush=True)
            return self._send(200, {"count": touched})

        m = re.match(r"^/api/user/folders/(\w+)$", path)
        if m:
            body = json.loads(raw or b"{}")
            folder = next((f for f in FOLDERS if f["id"] == m.group(1)), None)
            if not folder:
                return self._err(4007, "Folder not found", 404)
            if body.get("name"):
                folder["name"] = body["name"]
            # The GET shape calls it "public" while the PATCH body says "isPublic".
            if "isPublic" in body and body["isPublic"] is not None:
                folder["public"] = bool(body["isPublic"])
            if "allowUploads" in body and body["allowUploads"] is not None:
                folder["allowUploads"] = bool(body["allowUploads"])
            print(f"    -> folder updated: {folder}", flush=True)
            return self._send(200, folder)

        m = re.match(r"^/api/users/(\w+)$", path)
        if m:
            body = json.loads(raw or b"{}")
            user = next((u for u in USERS_LIST if u["id"] == m.group(1)), None)
            if not user:
                return self._err(9002, "User not found", 404)
            if body.get("username"):
                user["username"] = body["username"]
            if body.get("role"):
                user["role"] = body["role"]
            if body.get("quota"):
                user["quota"] = body["quota"]
            # A password change is accepted but never echoed back.
            print(f"    -> user updated: {user['username']} role={user['role']} "
                  f"password={'set' if body.get('password') else 'unchanged'}", flush=True)
            return self._send(200, user)

        m = re.match(r"^/api/user/tags/(\w+)$", path)
        if m:
            body = json.loads(raw or b"{}")
            tag = next((t for t in TAGS if t["id"] == m.group(1)), None)
            if not tag:
                return self._err(9002, "Tag not found", 404)
            if body.get("name"):
                if any(t["name"] == body["name"] and t["id"] != tag["id"] for t in TAGS):
                    return self._err(1034, "A tag with that name already exists", 400)
                tag["name"] = body["name"]
            if body.get("color"):
                tag["color"] = body["color"]
            # Tags are embedded in each file, so the copies have to follow the rename.
            for f in FILES:
                for t in f.get("tags", []):
                    if t["id"] == tag["id"]:
                        t.update(tag)
            print(f"    -> tag updated: {tag}", flush=True)
            return self._send(200, tag)

        m = re.match(r"^/api/user/files/(\w+)$", path)
        if m:
            body = json.loads(raw or b"{}")
            target = next((f for f in FILES if f["id"] == m.group(1)), None)
            if not target:
                return self._err(9002, "File not found", 404)
            for field in ("favorite", "maxViews", "name", "originalName", "type"):
                if field in body and body[field] is not None:
                    target[field] = body[field]
            # The real server never echoes the hash back - responses expose password as a
            # boolean. An explicit JSON null clears it, which is the whole point of sending one.
            if "password" in body:
                target["password"] = bool(body["password"]) or None
                print(f"    -> password {'set' if target['password'] else 'CLEARED'}", flush=True)
            if body.get("tags") is not None:
                wanted = set(body["tags"])
                target["tags"] = [t for t in TAGS if t["id"] in wanted]
            print(f"    -> PATCH file {target['id']}: "
                  f"{ {k: v for k, v in body.items()} }", flush=True)
            return self._send(200, target)

        if path == "/api/server/settings":
            patch = json.loads(raw or b"{}")
            print(f"    -> settings PATCH: {patch}", flush=True)
            SERVER_SETTINGS.update(patch)
            return self._send(200, {"settings": SERVER_SETTINGS, "tampered": TAMPERED})
        self._err(9002, "Not found", 404)

    def do_DELETE(self):
        # Python wants every global declared before its first use in the function.
        global FILES, TAGS, ZERO_FILES, INVITES, URLS, FOLDERS, USERS_LIST, TOTP_SECRET

        if self.headers.get("authorization") != TOKEN:
            return self._err(2001, "Invalid token", 401)
        path = self.path.split("?")[0]
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""

        if path == "/api/user/sessions":
            global OTHER_SESSIONS
            body = json.loads(raw or b"{}")
            if body.get("all"):
                print(f"    -> sessions: dropped all {len(OTHER_SESSIONS)} other(s)", flush=True)
                OTHER_SESSIONS = []
            else:
                sid = body.get("sessionId")
                # The real server refuses to delete the session making the request.
                if sid == CURRENT_SESSION["id"]:
                    return self._err(1021, "cannot delete the current session", 400)
                if not any(s["id"] == sid for s in OTHER_SESSIONS):
                    return self._err(1031, "session not found", 404)
                OTHER_SESSIONS = [s for s in OTHER_SESSIONS if s["id"] != sid]
                print(f"    -> session {sid} signed out", flush=True)
            return self._send(200, {"current": CURRENT_SESSION, "other": OTHER_SESSIONS})
        if path == "/api/user/mfa/totp":
            if not TOTP_SECRET:
                return self._err(1053, "totp is not enabled", 400)
            code = str(json.loads(raw or b"{}").get("code", ""))
            if not totp_valid(TOTP_SECRET, code):
                print(f"    -> totp disable REFUSED: bad code {code!r}", flush=True)
                return self._err(1045, "invalid code", 400)
            TOTP_SECRET = None
            print("    -> totp DISABLED", flush=True)
            return self._send(200, USER)

        # Bulk delete carries a body on DELETE, which is unusual but is what Zipline does.
        if path == "/api/user/files/transaction":
            body = json.loads(raw or b"{}")
            ids = set(body.get("files") or [])
            if not ids:
                return self._err(1000, "files must not be empty", 400)
            before = len(FILES)
            FILES = [f for f in FILES if f["id"] not in ids]
            removed = before - len(FILES)
            print(f"    -> bulk DELETE {removed} file(s), "
                  f"datasource={body.get('delete_datasourceFiles')}", flush=True)
            return self._send(200, {"count": removed})
        m = re.match(r"^/api/user/folders/(\w+)$", path)
        if m:
            body = json.loads(raw or b"{}")
            fid = m.group(1)
            # The real server validates this enum; the mock has to as well, or a client that
            # silently omits the field passes here and fails against a real instance.
            if body.get("delete") not in ("file", "folder"):
                return self._err(1000, "body/delete Invalid option: expected one of file|folder", 400)
            keep = body.get("childrenAction") != "cascade-files"
            if keep:
                for f in FILES:
                    if f.get("folderId") == fid:
                        f["folderId"] = None
            else:
                FILES = [f for f in FILES if f.get("folderId") != fid]
            FOLDERS = [f for f in FOLDERS if f["id"] != fid]
            print(f"    -> folder {fid} deleted, files "
                  f"{'moved to root' if keep else 'deleted too'}", flush=True)
            return self._send(200, {"success": True})

        m = re.match(r"^/api/users/(\w+)$", path)
        if m:
            body = json.loads(raw or b"{}")
            uid = m.group(1)
            gone = [u for u in USERS_LIST if u["id"] == uid]
            USERS_LIST = [u for u in USERS_LIST if u["id"] != uid]
            print(f"    -> user {uid} deleted, content="
                  f"{'deleted' if body.get('delete') else 'kept'}", flush=True)
            return self._send(200, gone[0] if gone else {"id": uid})

        m = re.match(r"^/api/user/tags/(\w+)$", path)
        if m:
            gone = [t for t in TAGS if t["id"] == m.group(1)]
            TAGS = [t for t in TAGS if t["id"] != m.group(1)]
            return self._send(200, gone[0] if gone else {"id": m.group(1), "name": "x"})

        if path == "/api/server/clear_temp":
            return self._send(200, {"status": "Cleared 3 temporary files"})
        if path == "/api/server/clear_zeros":
            global ZERO_FILES
            n = len(ZERO_FILES)
            ZERO_FILES = []
            return self._send(200, {"status": f"Cleared {n} zero-byte files"})
        m = re.match(r"^/api/auth/invites/(\w+)$", path)
        if m:
            global INVITES
            gone = [i for i in INVITES if i["id"] == m.group(1)]
            INVITES = [i for i in INVITES if i["id"] != m.group(1)]
            print(f"    -> invite deleted: {m.group(1)}", flush=True)
            return self._send(200, gone[0] if gone else {"id": m.group(1), "code": "x"})
        m = re.match(r"^/api/user/urls/(\w+)$", path)
        if m:
            URLS = [u for u in URLS if u["id"] != m.group(1)]
            return self._send(200, {"id": m.group(1), "code": "x", "destination": "x"})
        m = re.match(r"^/api/user/files/(\w+)$", path)
        if m:
            FILES = [f for f in FILES if f["id"] != m.group(1)]
            return self._send(200, {"id": m.group(1), "name": "deleted", "type": "image/png", "size": 0})
        self._err(9002, "Not found", 404)


if __name__ == "__main__":
    import sys

    tls = "--tls" in sys.argv
    port = 8443 if tls else 8099
    # --port lets several mocks run at once, which is how the multi-server profile list gets
    # exercised: one instance per port, one app profile pointing at each.
    if "--port" in sys.argv:
        port = int(sys.argv[sys.argv.index("--port") + 1])
    # Threading: the app holds a keep-alive connection for the API while Coil opens a second
    # one for previews. A single-threaded server would deadlock the image requests behind it.
    srv = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    if tls:
        import ssl

        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(os.environ.get("MOCK_TLS_CERT", "tls/server.pem"))
        srv.socket = ctx.wrap_socket(srv.socket, server_side=True)
    print(f"mock zipline on 0.0.0.0:{port} tls={tls} token={TOKEN}", flush=True)
    srv.serve_forever()
