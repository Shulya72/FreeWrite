import struct
def walk(b, start=0x110, end=None, verbose=False, limit=None, out_slots=None):
    p=start
    strokes=[]  # (offset, kind, fmt, n, ts, pts)
    end = end or len(b)
    pads=0
    slot=0
    slots=[]
    while p < end:
        if b[p:p+4]==b'\xff\xff\xff\xff':
            q=p
            while q<end and b[q]==0xff: q+=1
            pads+= q-p
            slot += (q-p)//4
            p=q; continue
        slots.append(slot); slot+=1
        if out_slots is not None: out_slots.append(slots[-1])
        flags, ts = struct.unpack_from('<IQ', b, p)
        if flags & 0x80000000:
            x0,y0 = struct.unpack_from('<ff', b, p+12)
            u16 = struct.unpack_from('<H', b, p+20)[0]
            fmt, n = struct.unpack_from('<II', b, p+22)
            if n<=0 or n>200000: return strokes, p, 'bad n %d at %x'%(n,p)
            q=p+30
            dx=struct.unpack_from('<%dh'%n, b, q); q+=2*n
            dy=struct.unpack_from('<%dh'%n, b, q); q+=2*n
            f=b[q:q+n]; q+=n
            strokes.append((p,'c',fmt,n,ts,(x0,y0,dx,dy,f,u16,flags)))
            p=q
        elif flags == 1:
            fmt, a, kind = struct.unpack_from('<III', b, p+12)
            nf = {3111: 4, 3110: 7}.get(fmt)
            if nf is None:
                return strokes, p, 'primitive fmt %d kind %d at %x'%(fmt,kind,p)
            vals = struct.unpack_from('<%df'%nf, b, p+24); tail = struct.unpack_from('<I', b, p+24+4*nf)[0]
            strokes.append((p,'line' if fmt==3111 else 'arc',fmt,nf,ts,(vals,a,tail)))
            p += 24 + 4*nf + 4
        else:
            fmt, n = struct.unpack_from('<II', b, p+12)
            if n<=0 or n>200000: return strokes, p, 'bad raw n %d at %x (flags %x)'%(n,p,flags)
            q=p+20
            xy=struct.unpack_from('<%df'%(2*n), b, q); q+=8*n
            f=struct.unpack_from('<%df'%n, b, q); q+=4*n
            t=struct.unpack_from('<%dI'%n, b, q); q+=4*n
            strokes.append((p,'r',fmt,n,ts,(xy,f,t,flags)))
            p=q
        if limit and len(strokes)>=limit: break
    return strokes, p, 'ok pads=%d'%pads
