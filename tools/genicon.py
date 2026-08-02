"""ZipShare icon - original mark.

A bold Z whose top-right terminal launches into an upward arrowhead: the letter and the
upload gesture in one shape. No geometry borrowed from ShareX or Zipline.

Adaptive icon: 108x108 viewport, content kept inside the 66dp safe circle (radius 33 from 54,54).
"""

BG_A = "#FF161A33"   # deep indigo
BG_B = "#FF3A1E63"   # violet
Z_COLOR = "#FFF4F6FF"
ARROW_COLOR = "#FF4ADECB"

SW = 11.0            # Z stroke width

# Z: top bar -> diagonal down-left -> bottom bar. Round caps/joins keep it friendly at 48dp.
# Sat 6dp lower than the first pass so the arrow + Z together are centred in the disc.
Z_PATH = "M34,50 L65,50 L36,74 L74,74"

# Arrowhead growing straight out of the top bar's right terminal, touching it so the two read
# as one shape rather than a triangle parked above a letter.
ARROW = "M65,24 L77,47 L53,47 Z"


def vector(mono: bool) -> str:
    z = "#FFFFFFFF" if mono else Z_COLOR
    a = "#FFFFFFFF" if mono else ARROW_COLOR
    return f"""<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:pathData="{Z_PATH}"
        android:strokeColor="{z}"
        android:strokeWidth="{SW}"
        android:strokeLineCap="round"
        android:strokeLineJoin="round"
        android:fillColor="#00000000" />
    <path
        android:pathData="{ARROW}"
        android:fillColor="{a}" />
</vector>
"""


BACKGROUND = f"""<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:pathData="M0,0 L108,0 L108,108 L0,108 Z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="linear"
                android:startX="0" android:startY="0"
                android:endX="108" android:endY="108">
                <item android:offset="0" android:color="{BG_A}" />
                <item android:offset="1" android:color="{BG_B}" />
            </gradient>
        </aapt:attr>
    </path>
</vector>
"""

base = "../app/src/main/res/drawable/"
open(base + "ic_launcher_foreground.xml", "w").write(vector(mono=False))
open(base + "ic_launcher_monochrome.xml", "w").write(vector(mono=True))
open(base + "ic_launcher_background.xml", "w").write(BACKGROUND)
print("wrote foreground, monochrome, gradient background")

