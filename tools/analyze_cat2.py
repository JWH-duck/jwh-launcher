from PIL import Image
src = r'C:\Users\33095\AppData\Local\Doubao\User Data\ClipboardTemp\bd616877-747c-4e17-b918-dc16b8543037.png'
im = Image.open(src).convert('RGBA')
w, h = im.size
px = im.load()

def cls(r, g, b):
    if r > 200 and g > 190 and b > 175: return 'C'  # cream
    if abs(r-g) < 25 and abs(g-b) < 25 and r < 120: return 'D'  # dark cat
    if r > 90 and b > 80 and g < 80: return 'P'  # purple
    return '?'

for y in range(300, 440, 12):
    row = []
    for x in range(600, 860, 12):
        r, g, b, a = px[x, y]
        row.append(cls(r, g, b))
    print(y, ' '.join(row))
