import struct
def parse_tags(b, start):
    """start = offset right after transforms (the u32 0 / count header)."""
    zero, count = struct.unpack_from('<II', b, start)
    q = start + 9   # header: u32 0, u32 count, u8 0 (first tag has no kind/id)
    tags=[]; kind=None; tid=None
    def rstr(q):
        n=struct.unpack_from('<I', b, q)[0]
        return b[q+4:q+4+n], q+4+n
    for i in range(count):
        name,q=rstr(q)
        n=struct.unpack_from('<I', b, q)[0]; q+=4
        rs=[struct.unpack_from('<IIHHI', b, q+16*k) for k in range(n)]; q+=16*n
        payload,q=rstr(q)
        # Номер и вид метки идут ПОСЛЕ неё (12 байт: вид, номер, 0).
        kind,tid,z=struct.unpack_from('<III', b, q); q+=12
        tags.append(dict(kind=kind,id=tid,name=name.decode('utf-8','replace'),ranges=rs,payload=payload))
    return tags, q
def parse_transforms(b, q):
    trs=[]
    while True:
        n=struct.unpack_from('<I', b, q)[0]
        if n<1 or n>100000 or struct.unpack_from('<I', b, q+4)[0]!=3: break
        rs=[struct.unpack_from('<IIHHI', b, q+4+16*i) for i in range(n)]
        m=struct.unpack_from('<6d', b, q+4+16*n)
        trs.append((rs,m)); q+=4+16*n+48
    return trs, q
