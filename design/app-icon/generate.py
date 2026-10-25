#!/usr/bin/env python3
"""Generate matching SVG masters and native Android launcher vectors."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "app/src/main/res"
OUT = Path(__file__).resolve().parent
BACKGROUND = "#161618"

# Hand-built cubic paths from concept.png. Coordinates retain the concept's
# proportions; the adaptive foreground scales the artwork into the safe zone.
TRUNK = (
    "M638,458 C706,505 723,550 691,602 "
    "C668,640 623,675 599,704 C577,690 557,687 532,680 "
    "C501,672 486,660 481,637 L435,640 "
    "C442,681 459,701 505,714 C558,730 585,757 601,800 "
    "C627,858 597,883 552,889 L785,889 "
    "C778,856 751,831 729,807 C735,779 759,759 798,747 "
    "C845,733 868,715 880,676 L842,673 "
    "C832,700 809,708 774,716 C736,725 709,743 691,766 "
    "C663,733 681,702 711,663 C745,617 759,579 739,532 "
    "C727,502 711,481 696,467 Z"
)
TOP = (
    "M429,389 C431,350 459,330 499,311 "
    "C540,292 559,263 593,245 C655,211 727,215 775,242 "
    "C818,266 832,297 860,319 C891,344 927,359 942,393 "
    "C962,438 934,472 891,486 C833,507 777,490 724,476 "
    "C669,461 640,459 585,460 C506,461 425,451 429,389 Z"
)
LEFT = (
    "M212,593 C211,558 244,540 276,519 "
    "C316,491 342,472 384,465 C446,456 496,468 529,496 "
    "C556,520 566,527 594,542 C627,561 638,588 625,610 "
    "C611,639 569,643 523,642 C478,641 449,640 412,650 "
    "C358,666 314,672 266,660 C231,652 213,625 212,593 Z"
)
RIGHT = (
    "M767,628 C773,600 803,590 827,572 "
    "C865,538 902,530 940,539 C970,546 988,565 1011,583 "
    "C1043,608 1055,635 1030,660 C1001,690 958,692 922,684 "
    "C884,676 864,676 833,678 C792,680 759,662 767,628 Z"
)
BOWL = (
    "M358,887 H895 C912,887 921,899 915,915 "
    "C885,986 818,1032 746,1032 H506 "
    "C432,1032 368,987 339,916 C333,901 340,887 358,887 Z"
)
SHAPES = [(TRUNK, "trunk"), (TOP, "crown"), (LEFT, "crown"),
          (RIGHT, "crown"), (BOWL, "bowl")]
GRADIENTS = {
    "trunk": (470, 460, 780, 930, [(0, "#E0D0FF"), (0.6, "#C4ADFF"), (1, "#9160E8")]),
    "crown": (510, 220, 650, 680, [(0, "#D8C4FF"), (0.48, "#AD83F0"), (1, "#7545D6")]),
    "bowl": (360, 1020, 880, 890, [(0, "#7545D6"), (0.55, "#A47AED"), (1, "#E0D0FF")]),
}
ANDROID = 'xmlns:android="http://schemas.android.com/apk/res/android"'


def write(relative, content):
    path = RES / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def vector(scale, background=False, monochrome=False, size=108):
    lines = ['<?xml version="1.0" encoding="utf-8"?>',
             f'<vector {ANDROID} xmlns:aapt="http://schemas.android.com/aapt"',
             f'    android:width="{size}dp" android:height="{size}dp"',
             '    android:viewportWidth="1254" android:viewportHeight="1254">']
    if background:
        lines.append(f'    <path android:fillColor="{BACKGROUND}" android:pathData="M0,0H1254V1254H0Z" />')
    lines.append(f'    <group android:pivotX="627" android:pivotY="627" android:scaleX="{scale}" android:scaleY="{scale}">')
    for path, gradient in SHAPES:
        if monochrome:
            lines.append(f'        <path android:fillColor="#FFFFFF" android:pathData="{path}" />')
        else:
            x1, y1, x2, y2, stops = GRADIENTS[gradient]
            lines += [f'        <path android:pathData="{path}">',
                      '            <aapt:attr name="android:fillColor">',
                      f'                <gradient android:type="linear" android:startX="{x1}" android:startY="{y1}" android:endX="{x2}" android:endY="{y2}">']
            lines += [f'                    <item android:offset="{offset}" android:color="{color}" />' for offset, color in stops]
            lines += ['                </gradient>', '            </aapt:attr>', '        </path>']
    return "\n".join(lines + ['    </group>', '</vector>', ''])


def svg(scale=0.86, background=True, monochrome=False):
    lines = ['<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" viewBox="0 0 1254 1254">', '<defs>']
    for name, (x1, y1, x2, y2, stops) in GRADIENTS.items():
        lines.append(f'<linearGradient id="{name}" gradientUnits="userSpaceOnUse" x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}">')
        lines += [f'<stop offset="{offset}" stop-color="{color}"/>' for offset, color in stops]
        lines.append('</linearGradient>')
    lines.append('</defs>')
    if background:
        lines.append(f'<path fill="{BACKGROUND}" d="M0 0H1254V1254H0Z"/>')
    lines.append(f'<g transform="translate(627 627) scale({scale}) translate(-627 -627)">')
    for path, gradient in SHAPES:
        fill = "#FFFFFF" if monochrome else f"url(#{gradient})"
        lines.append(f'<path fill="{fill}" d="{path}"/>')
    return "\n".join(lines + ['</g>', '</svg>', ''])


def main():
    write("drawable/ic_launcher_foreground.xml", vector(0.86))
    write("drawable/ic_launcher_monochrome.xml", vector(0.86, monochrome=True))
    # Notification small icons must be alpha masks, with no opaque tile.
    write("drawable/ic_notification_bonsai.xml", vector(1.16, monochrome=True, size=24))
    write("values/launcher_colors.xml", '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="launcher_background">#161618</color>\n</resources>\n')
    # minSdk 28 already supports adaptive icons. Older Android versions ignore
    # the monochrome layer, which Android 13+ uses for themed launcher icons.
    write("mipmap-anydpi/ic_launcher.xml",
          f'<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon {ANDROID}>\n    <background android:drawable="@color/launcher_background" />\n    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n</adaptive-icon>\n')
    # Adaptive layers are 108dp; a resting launcher mask exposes the central
    # 72dp. Crop only the preview, never the Android foreground resource.
    (OUT / "foreground.svg").write_text(svg(background=False), encoding="utf-8")
    launcher = svg().replace('width="108" height="108" viewBox="0 0 1254 1254"',
                             'width="1024" height="1024" viewBox="209 209 836 836"')
    (OUT / "launcher.svg").write_text(launcher, encoding="utf-8")
    (OUT / "monochrome.svg").write_text(svg(background=False, monochrome=True), encoding="utf-8")
    artwork = svg().split('>', 1)[1].rsplit('</svg>', 1)[0]
    mono = svg(monochrome=True).split('>', 1)[1].rsplit('</svg>', 1)[0]
    mono = mono.replace(f'fill="{BACKGROUND}"', 'fill="#E9DEFF"').replace('fill="#FFFFFF"', 'fill="#4F3583"')
    preview = f'''<svg xmlns="http://www.w3.org/2000/svg" width="960" height="344" viewBox="0 0 960 344">
<defs>
<symbol id="art" viewBox="209 209 836 836">{artwork}</symbol>
<symbol id="mono" viewBox="209 209 836 836">{mono}</symbol>
<clipPath id="circle"><circle cx="140" cy="164" r="86"/></clipPath>
<clipPath id="rounded"><rect x="292" y="78" width="172" height="172" rx="43"/></clipPath>
<clipPath id="themed"><circle cx="616" cy="164" r="86"/></clipPath>
<clipPath id="small"><rect x="830" y="140" width="48" height="48" rx="12"/></clipPath>
</defs>
<path fill="#FAFAFA" d="M0 0H960V344H0Z"/>
<text x="54" y="40" font-family="sans-serif" font-size="22" font-weight="600" fill="#1B1B20">Bonsai · App-Icon</text>
<use href="#art" x="54" y="78" width="172" height="172" clip-path="url(#circle)"/>
<use href="#art" x="292" y="78" width="172" height="172" clip-path="url(#rounded)"/>
<use href="#mono" x="530" y="78" width="172" height="172" clip-path="url(#themed)"/>
<use href="#art" x="830" y="140" width="48" height="48" clip-path="url(#small)"/>
<g font-family="sans-serif" font-size="16" fill="#62626E" text-anchor="middle">
<text x="140" y="292">Rund</text><text x="378" y="292">Abgerundet</text>
<text x="616" y="292">Android-Designfarbe</text><text x="854" y="292">48 px</text>
</g></svg>'''
    (OUT / "preview.svg").write_text(preview, encoding="utf-8")


if __name__ == "__main__":
    main()
