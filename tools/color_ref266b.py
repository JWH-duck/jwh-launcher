from PIL import Image
src = r"C:\Users\33095\AppData\Local\Doubao\User Data\ClipboardTemp\d5381efb-11b0-4f1b-a53d-c2bfd1e29a6a.png"
im = Image.open(src).convert("RGB")

def avg(x0,y0,x1,y1,label):
    px=[im.getpixel((x,y)) for x in range(x0,x1) for y in range(y0,y1)]
    r=sum(p[0] for p in px)//len(px); g=sum(p[1] for p in px)//len(px); b=sum(p[2] for p in px)//len(px)
    print(f"{label:20s} #{r:02x}{g:02x}{b:02x} ({r},{g},{b})")

# 下划线（选中标签底部粉条）：扫 y 84..92, x 45..100
best=None
for y in range(82,94):
    for x in range(45,105):
        r,g,b=im.getpixel((x,y))
        if r>150 and b>120 and g<130:
            best=(r,g,b); break
    if best: break
print("underline pink:", best, f"#{best[0]:02x}{best[1]:02x}{best[2]:02x}" if best else "")

avg(150,55,215,66,"unselected tab text")
avg(655,143,760,158,"search placeholder")
avg(168,244,205,256,"type tag [正式版]")
avg(105,266,200,276,"meta time")
avg(46,300,1336,302,"row divider line")
avg(46,210,1336,212,"row divider top")
avg(300,380,700,400,"page bg")
avg(46,143,224,158,"combo bg")
