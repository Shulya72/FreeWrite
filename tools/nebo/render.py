"""Нарисовать доску из распакованного .nebo в PNG (проверка разбора): python3 render.py <папка> <out.png> [px_на_мм] [x0 y0 x1 y1]"""
import sys, os, re, math
sys.path.insert(0, os.path.dirname(__file__))
import model
from PIL import Image, ImageDraw

def css_color(s):
    m = re.search(r'color:\s*#([0-9a-fA-F]{8})', s)
    if not m: return None
    h = m.group(1); return tuple(int(h[i:i+2],16) for i in (0,2,4,6))

def build(d):
    root, strokes, trs, tags = model.load(d)
    color = {}; width = {}; fake = set(); prio = {}
    for t in tags:
        nm = t['name']
        if nm == '.STYLE':
            c = css_color(t['payload'].decode('utf-8','replace'))
            if c:
                for r in t['ranges']:
                    for s in model.range_strokes(r): color[s] = c
        elif re.fullmatch(r'pen-\d{3}', nm):
            w = int(nm[4:]) / 100.0 * 0.625 / 0.35
            for r in t['ranges']:
                for s in model.range_strokes(r): width[s] = w
        elif nm == 'imageFakeContent':
            for r in t['ranges']:
                for s in model.range_strokes(r): fake.add(s)
    mat = {}
    for rs, m in trs:
        for r in rs:
            for s in model.range_strokes(r): mat[s] = m
    layout = [c for c in root.children if not isinstance(c, tuple) and c.name == 'layout'][0]
    ltr = {t.attrs['id']: [model.dbl(t.attrs[k]) for k in 'abcdef'] for t in [c for c in layout.children if c.name == 'transforms'][0].children}
    ab = layout.children[0].children[1].children[0].children[0]
    byid = {t['id']: t for t in tags}
    items = []
    for x in ab.children:
        if x.name == 'object':
            a = x.attrs; m = ltr.get(a.get('transform'), [1,0,0,0,1,0])
            X, Y, W, H = a['x'], a['y'], a['width'], a['height']
            items.append(('image', a['url'], (m[0]*X+m[2], m[4]*Y+m[5], m[0]*W, m[4]*H)))
        elif x.name == 'strokes':
            t = byid[x.attrs['tag']]
            for r in t['ranges']:
                for s in model.range_strokes(r):
                    if s in strokes and s not in fake:
                        pts, f = model.points(strokes[s])
                        m = mat.get(s)
                        if m: pts = [(m[0]*px+m[1]*py+m[2], m[3]*px+m[4]*py+m[5]) for px, py in pts]
                        items.append(('stroke', s, pts, color.get(s, (0,0,0,255)), width.get(s, 0.625), strokes[s][1]))
    return items

if __name__ == '__main__':
    d, out = sys.argv[1], sys.argv[2]
    k = float(sys.argv[3]) if len(sys.argv) > 3 else 0.5
    items = build(d)
    if len(sys.argv) > 7:
        x0, y0, x1, y1 = map(float, sys.argv[4:8])
    else:
        xs=[]; ys=[]
        for it in items:
            if it[0]=='image': x,y,w,h=it[2]; xs+= [x,x+w]; ys+=[y,y+h]
            else:
                for px,py in it[2]: xs.append(px); ys.append(py)
        x0,y0,x1,y1=min(xs)-10,min(ys)-10,max(xs)+10,max(ys)+10
    W, H = int((x1-x0)*k), int((y1-y0)*k)
    img = Image.new('RGB', (W, H), 'white'); dr = ImageDraw.Draw(img, 'RGBA')
    page = os.listdir(d+'/pages')[0]
    for it in items:
        if it[0] == 'image':
            x, y, w, h = it[2]
            if x+w < x0 or x > x1 or y+h < y0 or y > y1: continue
            try:
                im = Image.open(f'{d}/objects/{it[1]}').convert('RGB').resize((max(1,int(w*k)), max(1,int(h*k))))
                img.paste(im, (int((x-x0)*k), int((y-y0)*k)))
            except Exception as e:
                print('image', it[1], e)
        else:
            _, s, pts, c, w, kind = it
            p = [((px-x0)*k, (py-y0)*k) for px, py in pts]
            lw = max(1, int(round(w*k)))
            if len(p) == 1: dr.ellipse([p[0][0]-lw/2, p[0][1]-lw/2, p[0][0]+lw/2, p[0][1]+lw/2], fill=c)
            else: dr.line(p, fill=c, width=lw, joint='curve')
    img.save(out)
    print('saved', out, W, H, 'bounds', round(x0), round(y0), round(x1), round(y1), 'items', len(items))
