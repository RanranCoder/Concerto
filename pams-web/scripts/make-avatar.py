from PIL import Image

SRC = r"D:/MyApp/PAMS/pams-web/public/lingsi/ranran.jpeg"
OUT_DIR = r"D:/MyApp/PAMS/pams-web/public/lingsi"
PUBLIC = r"D:/MyApp/PAMS/pams-web/public"

src = Image.open(SRC).convert("RGB")
W, H = src.size  # 1536 x 1536（本身就是正方形，不做任何裁切）

# 原图直接等比缩放到正方形，容器也是正方形，object-fit: cover 不会产生任何裁切
for size, name in [
    (256, "ranran-avatar.jpg"),      # 大尺寸展示位
    (96, "ranran-avatar-sm.jpg"),    # 小尺寸头像
]:
    img = src.resize((size, size), Image.LANCZOS)
    path = f"{OUT_DIR}/{name}"
    img.save(path, "JPEG", quality=88, optimize=True, progressive=True)
    print(path, img.size)

# 浏览器标签 favicon：与头像同图，不裁切
icon = src.resize((64, 64), Image.LANCZOS)
icon.save(f"{PUBLIC}/favicon.png", "PNG", optimize=True)
print(f"{PUBLIC}/favicon.png", icon.size)

print("source:", (W, H), "-> no crop")
