import zipfile, io
from PIL import Image
jar = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\target\JWH-Launcher.jar"
z = zipfile.ZipFile(jar)
for n in ["nav_home","nav_log","nav_download","nav_skin","nav_settings","nav_about","nav_music"]:
    p = f"icons/{n}.png"
    try:
        data = z.read(p)
        im = Image.open(io.BytesIO(data))
        print(n, im.size, len(data), "bytes")
    except KeyError:
        print(n, "MISSING")
