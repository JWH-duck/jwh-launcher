import re, xml.etree.ElementTree as ET

svg = r"C:\Users\33095\Documents\xwechat_files\wxid_vz5ngw1tchx412_7462\msg\file\2026-11\tubiao.svg"
tree = ET.parse(svg)
root = tree.getroot()
ns = {"s": "http://www.w3.org/2000/svg"}

def nums(d):
    return [float(x) for x in re.findall(r"-?\d+\.?\d*", d)]

idx = 0
for g in root.findall("s:g", ns):
    for p in g.findall("s:path", ns):
        d = p.get("d")
        v = nums(d)
        xs = v[0::2]; ys = v[1::2]
        print(f"[g] path{idx} x[{min(xs):.0f},{max(xs):.0f}] y[{min(ys):.0f},{max(ys):.0f}] dlen={len(d)}")
        idx += 1

for p in root.findall("s:path", ns):
    d = p.get("d")
    v = nums(d)
    xs = v[0::2]; ys = v[1::2]
    print(f"[top] path{idx} x[{min(xs):.0f},{max(xs):.0f}] y[{min(ys):.0f},{max(ys):.0f}] dlen={len(d)}")
    idx += 1
