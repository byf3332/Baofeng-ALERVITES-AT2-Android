from pathlib import Path
import shutil

base = Path(__file__).resolve().parent

# 自动找 index-*.js；如果有多个，手动改成 index-f91c8683.js
candidates = sorted(base.glob("index-*.js"))
if len(candidates) == 1:
    js = candidates[0]
else:
    js = base / "index-f91c8683.js"

if not js.exists():
    print("Found candidates:")
    for c in candidates:
        print(" ", c.name)
    raise FileNotFoundError(js)

bak = js.with_suffix(js.suffix + ".before_expose_dealer_ui_full.bak")
if not bak.exists():
    shutil.copy2(js, bak)
    print(f"Backup created: {bak}")
else:
    print(f"Backup already exists: {bak}")

text = js.read_text(encoding="utf-8")
changed = 0

def rep(old, new, desc, all_occ=False):
    global text, changed
    c = text.count(old)
    if c == 0:
        print(f"WARNING not found: {desc}")
        return
    if all_occ:
        text = text.replace(old, new)
        changed += c
        print(f"Patched {c}: {desc}")
    else:
        text = text.replace(old, new, 1)
        changed += 1
        print(f"Patched 1/{c}: {desc}")

# 1) 软件模式从 user 改成 complete
# 这样 dealer / production / write password 区域会按 complete 版本显示
rep('{ucchipKey:L}={ucchipKey:"user"}', '{ucchipKey:L}={ucchipKey:"complete"}',
    "header ucchipKey user -> complete", all_occ=True)

rep('{ucchipKey:r}={ucchipKey:"user"}', '{ucchipKey:r}={ucchipKey:"complete"}',
    "app ucchipKey user -> complete", all_occ=True)

# 2) 强制显示几个原本按 r.value 条件隐藏的区域
replacements = [
    ('r.value!=="user"?(z(),be("div",obe,', 'true?(z(),be("div",obe,'),
    ('r.value!=="user"?(z(),be("div",fbe,', 'true?(z(),be("div",fbe,'),
    ('r.value==="complete"?(z(),be("div",hbe,', 'true?(z(),be("div",hbe,'),
]
for old, new in replacements:
    c = text.count(old)
    if c:
        text = text.replace(old, new, 1)
        changed += 1
        print(f"Patched condition: {old}")
    else:
        print(f"WARNING condition not found: {old}")

# 3) 默认 dealer 不再是 unknown，而是宝锋 / CN059500 / subSystemId 1
old_default = 'y=ce({label:i.value.unknown,value:"0",subSystemId:-1})'
new_default = 'y=ce({label:"宝锋",value:"CN059500",subSystemId:1})'
if old_default in text:
    text = text.replace(old_default, new_default, 1)
    changed += 1
    print("Patched default dealer: unknown -> 宝锋")
else:
    print("WARNING default dealer snippet not found")

# 4) fixedInfo vendorCode 为空时，不要再 O('',0)，而是默认用 CN059500
# 这样打开后不会回到 unknown
old_vendor = 'O(L.vendorCode,0)'
new_vendor = 'O(L.vendorCode||"CN059500",0)'
if old_vendor in text:
    text = text.replace(old_vendor, new_vendor, 1)
    changed += 1
    print("Patched vendorCode fallback: empty -> CN059500")
else:
    print("WARNING vendorCode matching snippet not found")

js.write_text(text, encoding="utf-8")
print(f"Done. Total patches: {changed}")
print(f"Patched file: {js}")