"""Create a labelled synthetic uncompressed Bayer DNG for repeatable LibRaw tests.

This is a decoder fixture, not a substitute for camera-specific RAW validation.
Uses only the Python standard library. Do not use this as a photographic oracle.
"""
import struct
from pathlib import Path

root = Path(__file__).resolve().parents[1]
out = root / 'app/src/androidTest/assets/audit.dng'
out.parent.mkdir(parents=True, exist_ok=True)
w, h = 512, 384
entries = []
def tag(key, typ, values):
    fmt = {1:'B', 2:'B', 3:'H', 4:'I', 5:'I', 10:'i'}[typ]
    if isinstance(values, str): values = values.encode('ascii') + b'\0'
    if isinstance(values, bytes): data, count = values, len(values)
    else:
        if not isinstance(values, (list, tuple)): values = [values]
        data = struct.pack('<' + fmt * len(values), *values)
        count = len(values) // 2 if typ in (5,10) else len(values)
    entries.append((key,typ,count,data))

for k,v in [(254,0),(256,w),(257,h),(273,0),(278,h),(279,w*h*2)]: tag(k,4,v)
for k,v in [(258,16),(259,1),(262,32803),(274,1),(277,1),(284,1),(50711,1),(50717,4095),(50778,21)]: tag(k,3,v)
tag(271,2,'SpektraLab QA');tag(272,2,'Synthetic Bayer fixture');tag(50708,2,'SpektraLab Synthetic DNG')
tag(33421,3,[2,2]);tag(33422,1,bytes([0,1,1,2]))
tag(50706,1,bytes([1,4,0,0]));tag(50707,1,bytes([1,1,0,0]));tag(50710,1,bytes([0,1,2]))
tag(50713,3,[1,1]);tag(50714,5,[64,1]);tag(50718,5,[1,1,1,1]);tag(50719,3,[0,0]);tag(50720,3,[w,h])
tag(50721,10,[7000,10000,-2000,10000,-1000,10000,-3000,10000,13000,10000,1000,10000,0,10000,-3000,10000,12000,10000])
tag(50728,5,[1,2,1,1,2,3]);tag(50829,4,[0,0,h,w])
entries.sort()
extra = bytearray();ifd = bytearray();offset = 8+2+len(entries)*12+4
for key,typ,count,data in entries:
    if len(data)<=4: value = data.ljust(4,b'\0')
    else:
        value = struct.pack('<I', offset+len(extra));extra.extend(data)
        if len(extra)%2: extra.append(0)
    ifd.extend(struct.pack('<HHI',key,typ,count)+value)
pixel_offset = offset + len(extra)
for i,(key,*_) in enumerate(entries):
    if key==273: ifd[i*12+8:i*12+12]=struct.pack('<I',pixel_offset)
raw = bytearray()
for y in range(h):
    for x in range(w):
        channel = (0,1,1,2)[(y%2)*2+x%2]
        val = (0.04+0.4*x/w,0.04+0.5*y/h,0.14)[channel]
        raw.extend(struct.pack('<H',min(4095,int(64+val*4031))))
out.write_bytes(b'II'+struct.pack('<HI',42,8)+struct.pack('<H',len(entries))+ifd+struct.pack('<I',0)+extra+raw)
print(f'{out}: {len(raw)} Bayer bytes')
