import struct, sys
class Node:
    __slots__=('name','attrs','children','ns')
    def __init__(s,name): s.name=name; s.attrs={}; s.children=[]; s.ns=None
def parse(b):
    assert b[:4]==b'BDOM'
    p=6; strs=[]
    while True:
        n=struct.unpack_from('<I',b,p)[0]
        if n==0xff: break
        strs.append(b[p+4:p+4+n].decode('utf-8','replace')); p+=4+n
    p+=4
    unknown={}
    def rstr():
        nonlocal p
        n=struct.unpack_from('<I',b,p)[0]; p+=4
        if n==0xff: return None
        s=b[p:p+n]; p+=n
        return s.decode('utf-8','replace')
    def value(t):
        nonlocal p
        if t in (1,2): return rstr()
        if t==3: v=struct.unpack_from('<I',b,p)[0]; p+=4; return ('ref',strs[v])
        if t==7: v=struct.unpack_from('<i',b,p)[0]; p+=4; return v
        if t==4: v=struct.unpack_from('<q',b,p)[0]; p+=8; return v
        if t==6: v=b[p]; p+=1; return bool(v)
        if t==9: v=struct.unpack_from('<d',b,p)[0]; p+=8; return v
        if t==8: v=struct.unpack_from('<I',b,p)[0]; p+=4; return v
        if t==5: v=struct.unpack_from('<Q',b,p)[0]; p+=8; return v
        if t==0x0a: v=struct.unpack_from('<f',b,p)[0]; p+=4; return v
        raise ValueError('type %02x at %x'%(t,p-1))
    def element():
        nonlocal p
        k=b[p]
        if k==4:
            p+=1
        assert b[p]==3, 'elem %02x at %x'%(b[p],p)
        p+=1
        nm=strs[struct.unpack_from('<I',b,p)[0]]; p+=4
        node=Node(nm)
        if k==4:
            pre=rstr(); uri=rstr(); node.ns=(pre,uri)
        ns2=rstr()
        # attrs
        while b[p]!=0xff:
            assert b[p]==0, 'attr %02x at %x in %s'%(b[p],p,nm)
            p+=1
            an=strs[struct.unpack_from('<I',b,p)[0]]; p+=4
            t=b[p]; p+=1
            node.attrs[an]=value(t)
        p+=1
        while b[p]!=0xff:
            node.children.append(child())
        p+=1
        return node
    def child():
        nonlocal p
        k=b[p]
        if k in (3,4): return element()
        if k in (1,2):
            p+=1
            return ('text', rstr())
        raise ValueError('child kind %02x at %x'%(k,p))
    root=element()
    return strs, root, p
def walk(n, depth=0):
    yield n, depth
    for c in n.children:
        if isinstance(c, Node): yield from walk(c, depth+1)
