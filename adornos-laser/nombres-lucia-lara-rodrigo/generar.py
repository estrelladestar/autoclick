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
def render(name, em_mm, tr=-0.045):
    f = font(em_mm*PX)
    track = tr*em_mm*PX        # letras pegadas
    xs, x = [], 0
    for ch in name: xs.append(x); x += f.getlength(ch) + track
    w = int(x + em_mm*PX*1.5); h = int(em_mm*PX*2)
    im = Image.new('L', (w, h), 0); d = ImageDraw.Draw(im)
    for ch, cx in zip(name, xs): d.text((cx + em_mm*PX*0.4, em_mm*PX*0.3), ch, font=f, fill=255)
    a = np.array(im)
    k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (int(0.35*PX)|1,)*2)
    a = cv2.dilate(a, k)                          # engorda un poco para soldar las letras
    a = cv2.GaussianBlur(a, (9, 9), 0); a = np.where(a > 127, 255, 0).astype(np.uint8)
    a = weld(a)
    ys, xs_ = np.nonzero(a)
    return a[ys.min():ys.max()+1, xs_.min():xs_.max()+1]
def weld(a, bridge_mm=1.6):
    # une puntos de la i y tildes al resto del nombre con un puente
    while True:
        n, lab, st, _ = cv2.connectedComponentsWithStats(a, 8)
        if n <= 2: return a
        main = 1 + int(np.argmax(st[1:, cv2.CC_STAT_AREA]))
        if all(c == main or st[c, cv2.CC_STAT_AREA] > 40*PX*PX for c in range(1, n)): return a
        mys, mxs = np.nonzero(lab == main); M = np.stack([mxs, mys], 1)[::7]
        for comp in range(1, n):
            if comp == main or st[comp, cv2.CC_STAT_AREA] > 40*PX*PX: continue
            ys, xs = np.nonzero(lab == comp); C = np.stack([xs, ys], 1)[::3]
            cx, cy = C.mean(0)
            # punto del nombre más cercano al centro de la pieza suelta
            j = np.argmin(((M - [cx, cy])**2).sum(1)); tx, ty = M[j]
            i = np.argmin(((C - [tx, ty])**2).sum(1)); sx, sy = C[i]
            # entra un poco en ambas piezas para que quede bien soldado
            v = np.array([tx-sx, ty-sy], float); L = np.hypot(*v) or 1; v /= L
            p0 = (int(sx - v[0]*0.6*PX), int(sy - v[1]*0.6*PX)); p1 = (int(tx + v[0]*0.6*PX), int(ty + v[1]*0.6*PX))
            cv2.line(a, p0, p1, 255, int(bridge_mm*PX), cv2.LINE_AA)
        a = np.where(cv2.GaussianBlur(a, (9, 9), 0) > 127, 255, 0).astype(np.uint8)

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
    # junta las letras: aprieta el espacio hasta que el nombre sea una sola pieza
    for tr in np.arange(-0.045, -0.16, -0.005):
        a = render(n, EM, tr)
        if cv2.connectedComponents(a, connectivity=8)[0] == 2: break
    print(n, 'espaciado', round(tr, 3), 'piezas', cv2.connectedComponents(a, connectivity=8)[0]-1)
    s = 1.0
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
