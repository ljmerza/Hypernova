#!/usr/bin/env python3
# The Mac's and Windows' icons, from hypernova-client.png (Pillow). The results are checked in: the runners have
# no need of this, only a new picture does. python3 android/desktop/icons/make-icons.py
from pathlib import Path

from PIL import Image

here = Path(__file__).resolve().parent
source = Image.open(here / "hypernova-client.png").convert("RGBA")
source.save(here / "hypernova-client.ico", sizes=[(s, s) for s in (16, 24, 32, 48, 64, 128, 256)])
source.save(here / "hypernova-client.icns")
