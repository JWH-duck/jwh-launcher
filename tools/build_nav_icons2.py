# -*- coding: utf-8 -*-
"""
生成「日志」「关于」两张导航图标（透明 PNG）：
  - 亮度阈值分离黑色轮廓，硬边二值化消除半透明灰边发虚，去除棋盘格与右下角浅灰水印
  - 裁掉外圈边距去边缘暗角，按前景包围盒贴紧主体
  - 主体按最大边对齐 52，贴 64 画布
  - 「关于」圆环与 i 分开处理：圆环膨胀加粗，i 圆点/竖杆不膨胀，避免圆点粘连成“1”
"""
from PIL import Image, ImageFilter
import os
import collections

PROJ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(PROJ, "src", "main", "resources", "icons")

# 图标 -> (源素材, 整体膨胀次数, 是否只对最大连通块(圆环)膨胀)
ICONS = {
    "nav_log.png": (r"C:\Users\33095\Documents\xwechat_files\wxid_vz5ngw1tchx412_7462\temp\RWTemp\2026-11\638a1b5ba14a26a44a218ea2e2501337\f50ee44b55f9e053a6288d2a2e2442ce.png", 1, False),
    "nav_about.png": (r"C:\Users\33095\Documents\xwechat_files\wxid_vz5ngw1tchx412_7462\temp\RWTemp\2026-11\638a1b5ba14a26a44a218ea2e2501337\e6f64f10f401bbd1382ba399dbeff18d.png", 1, True),
}

SIZE = 64
CONTENT = 52
DARK = 100
LIGHT = 150
WORK = 768
MARGIN = 0.05
ISCALE = 1.18  # 关于图标中 i 整体等比放大系数（不膨胀，避免圆点粘连竖杆）


def label_components(mask, w, h):
    """4 邻域连通域，返回 {cid: [(x,y), ...]}。"""
    visited = [[False] * w for _ in range(h)]
    comps = {}
    cid = 0
    for sy in range(h):
        for sx in range(w):
            if mask[sy][sx] == 0 or visited[sy][sx]:
                continue
            q = collections.deque([(sx, sy)])
            visited[sy][sx] = True
            pts = []
            while q:
                x, y = q.popleft()
                pts.append((x, y))
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    nx, ny = x + dx, y + dy
                    if 0 <= nx < w and 0 <= ny < h and not visited[ny][nx] and mask[ny][nx] > 0:
                        visited[ny][nx] = True
                        q.append((nx, ny))
            comps[cid] = pts
            cid += 1
    return comps


def process(src: str, dilate: int, ring_only: bool) -> Image.Image:
    im = Image.open(src).convert("RGB")
    scale = WORK / max(im.size)
    im = im.resize((round(im.width * scale), round(im.height * scale)), Image.LANCZOS)
    w, h = im.size
    px = im.load()

    mask = [[0] * w for _ in range(h)]
    mx0, my0 = int(w * MARGIN), int(h * MARGIN)
    mx1, my1 = w - mx0, h - my0
    for y in range(h):
        for x in range(w):
            r, g, b = px[x, y]
            mxv = max(r, g, b)
            if x < mx0 or x >= mx1 or y < my0 or y >= my1:
                continue
            mask[y][x] = 255 if mxv < LIGHT else 0

    comps = label_components(mask, w, h)
    if not comps:
        raise RuntimeError(f"未识别到主体：{src}")
    ring_id = max(comps, key=lambda k: len(comps[k]))

    final = [[0] * w for _ in range(h)]
    if ring_only:
        # 圆环块单独膨胀加粗
        ring_mask = Image.new("L", (w, h), 0)
        rp = ring_mask.load()
        for x, y in comps[ring_id]:
            rp[x, y] = 255
        for _ in range(dilate):
            ring_mask = ring_mask.filter(ImageFilter.MaxFilter(3))

        # 非圆环块（i 圆点/竖杆）合并为一个掩码，等比放大一档后贴回（不膨胀，避免圆点粘竖杆）
        i_mask = Image.new("L", (w, h), 0)
        ip = i_mask.load()
        for cid, pts in comps.items():
            if cid != ring_id:
                for x, y in pts:
                    ip[x, y] = 255
        ib = i_mask.getbbox()
        if ib:
            crop = i_mask.crop(ib)
            cw, ch = crop.size
            nw, nh = round(cw * ISCALE), round(ch * ISCALE)
            crop = crop.resize((nw, nh), Image.LANCZOS)
            cp = crop.load()
            for yy in range(nh):
                for xx in range(nw):
                    cp[xx, yy] = 255 if cp[xx, yy] > 128 else 0
            cx, cy = (ib[0] + ib[2]) // 2, (ib[1] + ib[3]) // 2
            i_mask = Image.new("L", (w, h), 0)
            i_mask.paste(crop, (cx - nw // 2, cy - nh // 2), crop)

        rp, ip = ring_mask.load(), i_mask.load()
        for y in range(h):
            for x in range(w):
                if rp[x, y] > 0 or ip[x, y] > 0:
                    final[y][x] = 255
    else:
        for y in range(h):
            for x in range(w):
                final[y][x] = mask[y][x]
        if dilate > 0:
            m = Image.new("L", (w, h), 0)
            mp = m.load()
            for y in range(h):
                for x in range(w):
                    mp[x, y] = final[y][x]
            for _ in range(dilate):
                m = m.filter(ImageFilter.MaxFilter(3))
            mp = m.load()
            for y in range(h):
                for x in range(w):
                    final[y][x] = mp[x, y]

    # 前景包围盒
    minx, miny, maxx, maxy = w, h, -1, -1
    for y in range(h):
        for x in range(w):
            if final[y][x] > 0:
                minx = min(minx, x); maxx = max(maxx, x)
                miny = min(miny, y); maxy = max(maxy, y)
    if maxx < 0:
        raise RuntimeError(f"裁剪失败：{src}")

    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    op = out.load()
    for y in range(h):
        for x in range(w):
            if final[y][x] > 0:
                op[x, y] = (0, 0, 0, 255)
    crop = out.crop((minx, miny, maxx + 1, maxy + 1))

    side = max(crop.width, crop.height)
    nw = round(CONTENT * crop.width / side)
    nh = round(CONTENT * crop.height / side)
    crop = crop.resize((nw, nh), Image.LANCZOS)

    canvas = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    canvas.paste(crop, ((SIZE - nw) // 2, (SIZE - nh) // 2), crop)
    return canvas


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    for name, (src, dilate, ring_only) in ICONS.items():
        icon = process(src, dilate, ring_only)
        icon.save(os.path.join(OUT_DIR, name))
        bbox = icon.split()[3].point(lambda v: 255 if v > 8 else 0).getbbox()
        opaque = sum(1 for v in icon.split()[3].getdata() if v > 8)
        print(f"OK {name} bbox={bbox} w={bbox[2]-bbox[0]} h={bbox[3]-bbox[1]} opaque={opaque}")


if __name__ == "__main__":
    main()
