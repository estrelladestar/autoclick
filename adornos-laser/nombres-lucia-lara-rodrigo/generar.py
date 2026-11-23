import sys, numpy as np, cv2
from PIL import Image, ImageDraw, ImageFont
sys.path.insert(0, '/home/claude/work')
PX = 40.0
FONT = '/usr/share/fonts/truetype/google-fonts/Lora-Italic-Variable.ttf'
def font(px):
    f = ImageFont.truetype(FONT, int(px)); f.set_variation_by_axes([700]); return f
NAMES = sys.argv[2].split(',')
OUT = sys.argv[1]
MAXW = 66.0
def render(name, em_mm):
    f = font(em_mm*PX)
    track = -0.045*em_mm*PX        # letras pegadas
    xs, x = [], 0
    for ch in name: xs.append(x); x += f.getlength(ch) + track
    w = int(x + em_mm*PX*1.5); h = int(em_mm*PX*2)
    im = Image.new('L', (w, h), 0); d = ImageDraw.Draw(im)
    for ch, cx in zip(name, xs): d.text((cx + em_mm*PX*0.4, em_mm*PX*0.3), ch, font=f, fill=255)
    a = np.array(im)
    k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (int(0.35*PX)|1,)*2)
    a = cv2.dilate(a, k)                          # engorda un poco para soldar las letras
    a = cv2.GaussianBlur(a, (9, 9), 0); a = np.where(a > 127, 255, 0).astype(np.uint8)
    ys, xs_ = np.nonzero(a)
    return a[ys.min():ys.max()+1, xs_.min():xs_.max()+1]
def smooth_c(c, s):
    p = c[:, 0, :].astype(float); k = int(s*3); xs = np.arange(-k, k+1)
    w = np.exp(-xs**2/(2*s*s)); w /= w.sum(); out = np.zeros_like(p)
    for i, wi in zip(xs, w): out += wi*np.roll(p, -i, axis=0)
    return out
def bez(pts):
    n = len(pts); d = f'M{pts[0][0]:.3f},{pts[0][1]:.3f}'
    for i in range(n):
        p0, p1, p2, p3 = pts[i-1], pts[i], pts[(i+1) % n], pts[(i+2) % n]
        c1 = p1+(p2-p0)/6; c2 = p2-(p3-p1)/6
        d += f' C{c1[0]:.3f},{c1[1]:.3f} {c2[0]:.3f},{c2[1]:.3f} {p2[0]:.3f},{p2[1]:.3f}'
    return d+'Z'
def paths(a, scale, ox, oy):
    cs, _ = cv2.findContours(a, cv2.RETR_CCOMP, cv2.CHAIN_APPROX_NONE)
    out = []
    for c in cs:
        if cv2.contourArea(c) < 0.15*PX*PX/scale**2: continue
        p = smooth_c(c, 3.0) if len(c) > 30 else c[:, 0, :].astype(float)
        p = cv2.approxPolyDP(p.astype(np.float32).reshape(-1, 1, 2), 0.35, True)[:, 0, :].astype(float)
        if len(p) < 3: continue
        out.append(bez(p*scale/PX + [ox, oy]))
    return ' '.join(out)
EM = 23.4   # mismo tamaño de letra que los nombres anteriores (Izan = 44 mm)
items = []
for n in NAMES:
    a = render(n, EM); s = 1.0
    if a.shape[1]/PX > MAXW: s = MAXW/(a.shape[1]/PX)
    items.append((n, a, s))
body, x, y, rowh, col = '', 6.0, 8.0, 0, 0
for n, a, s in items:
    w, h = a.shape[1]/PX*s, a.shape[0]/PX*s
    body += f'<text x="{x:.1f}" y="{y:.1f}" font-family="Arial" font-size="2.5" fill="#00a000">{n} ({w:.0f} × {h:.0f} mm)</text>'
    body += f'<path d="{paths(a, s, x, y+3)}" fill="none" stroke="#ff0000" stroke-width="0.1" fill-rule="evenodd"/>'
    x += 72; rowh = max(rowh, h+12); col += 1
    if col == 4: x, y, col, rowh = 6.0, y+rowh, 0, 0
W = 6+72*min(4, len(items)); H = y+rowh+3
open(OUT, 'w').write(f'<svg xmlns="http://www.w3.org/2000/svg" width="{W:.1f}mm" height="{H:.1f}mm" viewBox="0 0 {W:.2f} {H:.2f}"><!-- Rojo = cortar, Verde = etiquetas (no cortar) -->{body}</svg>')
for n, a, s in items: print(n, round(a.shape[1]/PX*s, 1), round(a.shape[0]/PX*s, 1))
