# Заставка ViaDesk (0.29.2): из больших снимков (render_e.js bg, render_hero.js) — картинки приложения.
# Запуск из этой папки: node render_e.js bg && node render_hero.js && python3 make_assets.py
from PIL import Image
import os
out = '../../../app/src/main/res/drawable-nodpi'
for d, size in [('tablet', (1600, 2560)), ('phone', (1080, 2340)), ('land', (2560, 1600))]:
    Image.open(f'e_bg_{d}_big.png').convert('RGB').resize(size, Image.LANCZOS).save(f'{out}/splash_bg_{d}.webp', quality=90, method=6)
h = Image.open('hero_alpha_big.png').convert('RGBa').resize((2472, 744), Image.LANCZOS).convert('RGBA')
h.save(f'{out}/splash_hero.webp', quality=92, method=6)
for f in sorted(os.listdir(out)): print(f, os.path.getsize(f'{out}/{f}'))
