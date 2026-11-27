"""从离线稿面裁出中央猫头图标，导出为登录页共用资源 /icons/login_cat.png。"""
from PIL import Image
import os

SRC = r"C:\Users\33095\AppData\Local\Doubao\User Data\ClipboardTemp\bd616877-747c-4e17-b918-dc16b8543037.png"
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "src", "main", "resources", "icons", "login_cat.png")

im = Image.open(SRC).convert("RGB")
# 米白底板包围盒（分析得出），四周留 2px 圆角余量
box = (653, 320, 740, 407)
crop = im.crop(box)
side = min(crop.size)
crop = crop.crop(((crop.width - side) // 2, (crop.height - side) // 2,
                  (crop.width + side) // 2, (crop.height + side) // 2))
out = crop.resize((156, 156), Image.LANCZOS)
out.save(OUT)
print("saved", out.size)
