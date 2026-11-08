import sys, struct, os
sys.path.insert(0, os.path.dirname(__file__))
import bdom, bink, tags as T
def dbl(v): return struct.unpack('<d', struct.pack('<Q', v & 0xffffffffffffffff))[0]
def load(d):
    page=[x for x in os.listdir(d+'/pages')][0]
    pb=open(f'{d}/pages/{page}/page.bdom','rb').read()
    ib=open(f'{d}/pages/{page}/ink.bink','rb').read()
    strs, root, _ = bdom.parse(pb)
    slots=[]
    recs, p, msg = bink.walk(ib, out_slots=slots)
    strokes = {slots[i]: recs[i] for i in range(len(recs))}
    trs, q = T.parse_transforms(ib, p)
    tags, _ = T.parse_tags(ib, q)
    return root, strokes, trs, tags
def points(rec):
    off, kind, fmt, n, ts, data = rec
    if kind=='c':
        x0,y0,dx,dy,f,u16,flags = data
        xs=[];ys=[];x=x0;y=y0
        for i in range(n):
            x+=dx[i]/1000.0; y+=dy[i]/1000.0; xs.append(x); ys.append(y)
        return list(zip(xs,ys)), [v/255.0 for v in f]
    if kind=='r':
        xy,f,t,flags = data
        return [(xy[2*i],xy[2*i+1]) for i in range(n)], list(f)
    if kind=='line':
        v=data[0]; return [(v[0],v[1]),(v[2],v[3])], [1,1]
    if kind=='arc':
        import math
        cx,cy,rx,ry,phi,a0,sw = data[0]
        m=max(8,int(abs(sw)*20)); pts=[]
        for i in range(m+1):
            a=a0+sw*i/m
            x=rx*math.cos(a); y=ry*math.sin(a)
            pts.append((cx+x*math.cos(phi)-y*math.sin(phi), cy+x*math.sin(phi)+y*math.cos(phi)))
        return pts, [1]*len(pts)
def range_strokes(r):
    a,s1,f,p2,s2 = r
    return range(s1, s2+1)
