# -*- coding: utf-8 -*-
import re, html, os, sys

p = r'C:\Users\33095\Documents\xwechat_files\wxid_vz5ngw1tchx412_7462\msg\file\2026-11\启动器初稿\启动器初稿\下载.svg'
s = open(p, encoding='utf-8').read()

style = re.search(r'<style>(.*?)</style>', s, re.S).group(1)
cls = {}
for name, body in re.findall(r'\.(st\d+)\s*\{(.*?)\}', style, re.S):
    fill = re.search(r'fill:\s*([^;]+)', body)
    op = re.search(r'opacity:\s*([^;]+)', body)
    fs = re.search(r'font-size:\s*([^;]+)', body)
    ff = re.search(r'font-family:\s*([^;]+)', body)
    cls[name] = (
        fill.group(1).strip() if fill else '',
        op.group(1).strip() if op else '',
        fs.group(1).strip() if fs else '',
        ff.group(1).strip() if ff else '',
    )

def attr(t, a):
    m = re.search(a + r'="([^"]*)"', t)
    return m.group(1) if m else ''

out = []
for t in re.findall(r'<text\b.*?</text>', s, re.S):
    tr = attr(t, 'transform'); cl = attr(t, 'class')
    txt = ''.join(re.findall(r'<tspan[^>]*>(.*?)</tspan>', t, re.S))
    txt = html.unescape(txt)
    out.append(f'TEXT  [{cl}] tr={tr}  text={txt!r}  style={cls.get(cl, "")}')

for tag in ['rect', 'circle', 'image', 'use', 'path']:
    for t in re.findall(r'<' + tag + r'\b[^>]*/?>', s):
        cl = attr(t, 'class')
        if tag == 'rect':
            out.append(f'RECT  [{cl}] x={attr(t,"x")} y={attr(t,"y")} w={attr(t,"width")} h={attr(t,"height")} rx={attr(t,"rx")} style={cls.get(cl,"")}')
        elif tag == 'circle':
            out.append(f'CIRC  [{cl}] cx={attr(t,"cx")} cy={attr(t,"cy")} r={attr(t,"r")} style={cls.get(cl,"")}')
        elif tag == 'image':
            out.append(f'IMAGE x={attr(t,"x")} y={attr(t,"y")} w={attr(t,"width")} h={attr(t,"height")}')
        elif tag == 'use':
            out.append(f'USE  tr={attr(t,"transform")} href={attr(t,"xlink:href")}')
        elif tag == 'path':
            out.append(f'PATH  [{cl}] d={attr(t,"d")[:80]} style={cls.get(cl,"")}')

op = os.path.join(r'C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview', 'svg-elements.txt')
open(op, 'w', encoding='utf-8').write('\n'.join(out))
print('elements', len(out), '->', op)
