# Заставка ViaDesk «Стекло» (0.29.2): из больших снимков render_glass.js — картинки приложения.
# Запуск из этой папки: node render_glass.js && python3 make_assets.py
from PIL import Image
import os
out = '../../../app/src/main/res/drawable-nodpi'
for d, size in [('tablet', (1600, 2560)), ('phone', (1080, 2340)), ('land', (2560, 1600))]:
    Image.open(f'g_bg_{d}_big.png').convert('RGB').resize(size, Image.LANCZOS).save(f'{out}/splash_bg_{d}.webp', quality=92, method=6)
# Плашка со знаком и надписью: уменьшение с учётом прозрачности (RGBa), чтобы края не темнели.
h = Image.open('g_hero_big.png').convert('RGBa').resize((2184, 840), Image.LANCZOS).convert('RGBA')
h.save(f'{out}/splash_hero.webp', quality=92, method=6)
for f in sorted(os.listdir(out)): print(f, os.path.getsize(f'{out}/{f}'))
