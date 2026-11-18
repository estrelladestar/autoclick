import math, os, sys, numpy as np, cv2
from PIL import Image, ImageDraw, ImageFont
PX = 40.0                      # px por mm
W, H = 110.0, 116.0            # lienzo en mm
CX, CY, R = 55.0, 68.0, 45.0   # disco principal
OUT = sys.argv[1]
CFG = sys.argv[2] if len(sys.argv) > 2 else 'cinco'
FONT = '/usr/share/fonts/truetype/dejavu/DejaVuSerifCondensed-Bold.ttf'

LEMA = 'NUESTRA FAMILIA 2026'

def new(): return Image.new('L', (int(W*PX), int(H*PX)), 0)
def P(x, y): return (x*PX, y*PX)
def disc(im, x, y, r, v=255): ImageDraw.Draw(im).ellipse([P(x-r, y-r), P(x+r, y+r)], fill=v)
def poly(im, pts, v=255): ImageDraw.Draw(im).polygon([P(*p) for p in pts], fill=v)
def bez(p0, p1, p2, p3, n=200):
    out = []
    for i in range(n+1):
        t = i/n; a = (1-t)**3; b = 3*(1-t)**2*t; c = 3*(1-t)*t*t; d = t**3
        out.append((a*p0[0]+b*p1[0]+c*p2[0]+d*p3[0], a*p0[1]+b*p1[1]+c*p2[1]+d*p3[1]))
    return out
def stroke(im, pts, w0, w1, v=255):
    n = len(pts)-1
    for i, (x, y) in enumerate(pts):
        disc(im, x, y, (w0+(w1-w0)*i/n)/2, v)
def ellipse(im, x, y, a, b, ang, v=255):
    c, s = math.cos(math.radians(ang)), math.sin(math.radians(ang))
    pts = [(x+a*math.cos(t)*c-b*math.sin(t)*s, y+a*math.cos(t)*s+b*math.sin(t)*c)
           for t in np.linspace(0, 2*math.pi, 120)]
    poly(im, pts, v)
def smooth(im, r=0.25):
    a = np.array(im); k = int(r*PX)*2+1
    a = cv2.GaussianBlur(a, (k, k), 0)
    return Image.fromarray(np.where(a > 127, 255, 0).astype(np.uint8))
def OR(*ims):
    a = np.zeros_like(np.array(ims[0]))
    for i in ims: a = np.maximum(a, np.array(i))
    return Image.fromarray(a)
def SUB(a, b): return Image.fromarray(np.where(np.array(b) > 0, 0, np.array(a)).astype(np.uint8))
def mirror(im): return im.transpose(Image.FLIP_LEFT_RIGHT)  # el lienzo es simétrico en x=55

# ---------- astas ----------
def antlers():
    im = new()
    stroke(im, bez((38, 33), (29, 27), (19, 21), (16, 6)), 7.0, 4.2)          # rama principal
    stroke(im, bez((27, 26), (28.5, 20), (30.5, 15), (31.5, 9.5)), 4.4, 3.4)  # punta interior
    stroke(im, bez((19, 16), (14, 15.5), (10, 14), (7, 9.5)), 4.2, 3.3)       # punta exterior alta
    stroke(im, bez((23, 23.5), (17, 25), (12.5, 27), (9.5, 25)), 4.2, 3.3)    # punta exterior baja
    return smooth(OR(im, mirror(im)), 0.35)

def tab_and_hole():
    t = new(); d = ImageDraw.Draw(t)
    d.rounded_rectangle([P(49.5, 11), P(60.5, 27)], radius=5.5*PX, fill=255)
    h = new(); disc(h, 55, 16.2, 1.9)
    return t, h

# ---------- texto ----------
def glyph_img(ch, size_px):
    f = ImageFont.truetype(FONT, int(size_px))
    adv = f.getlength(ch); asc, desc = f.getmetrics()
    side = int(size_px*2.2)
    g = Image.new('L', (side, side), 0)
    ImageDraw.Draw(g).text((side/2-adv/2, side/2-asc), ch, font=f, fill=255)  # baseline en el centro
    return g, adv
def cap_ratio():
    f = ImageFont.truetype(FONT, 1000); return (f.getbbox('H')[3]-f.getbbox('H')[1])/1000.0
CAP = cap_ratio()

def text_center(im, s, x, y, max_w, cap_mm):
    size = cap_mm/CAP*PX
    f = ImageFont.truetype(FONT, int(size))
    while f.getlength(s) > max_w*PX:
        size *= 0.97; f = ImageFont.truetype(FONT, int(size))
    bb = f.getbbox('H'); capp = bb[3]-bb[1]; asc, _ = f.getmetrics()
    wpx = f.getlength(s)
    ImageDraw.Draw(im).text((x*PX-wpx/2, y*PX+capp/2-asc), s, font=f, fill=255)
    return size/PX*CAP

def text_arc_bottom(im, s, rb, cap_mm, track=0.4):
    size = cap_mm/CAP*PX
    f = ImageFont.truetype(FONT, int(size))
    advs = [f.getlength(c)/PX + track for c in s]; total = sum(advs) - track
    pos = -total/2
    for c, a in zip(s, advs):
        mid = pos + (a-track)/2; pos += a
        if c == ' ': continue
        th = math.pi/2 - mid/rb         # 90° = abajo; avanza hacia la derecha
        g, _ = glyph_img(c, size)
        g = g.rotate(-(math.degrees(th)-90), resample=Image.BICUBIC)
        x = CX + rb*math.cos(th); y = CY + rb*math.sin(th)    # punto de la línea base
        im.paste(255, (int(x*PX-g.width/2), int(y*PX-g.height/2)), g.point(lambda v: 255 if v > 110 else 0))

# ---------- bolas ----------
CONFIGS = {  # x, y, radio, forma, nombre, y donde empieza el hilo
  'cinco': [
    (35, 50.5, 9.5, 'round', 'RAFAEL', 36), (75, 50.5, 9.5, 'round', 'CINTA', 36),
    (55, 62.5, 9.5, 'onion', 'RAFAEL', 30),
    (35, 76.5, 10.5, 'round', 'ALEJANDRO', 59), (75, 76.5, 10.5, 'round', 'DIEGO', 59)],
  'juanfri': [
    (31, 61, 10.0, 'onion', 'JUANFRI', 37), (55, 50, 9.5, 'round', 'DYLAN', 30),
    (79, 61, 10.0, 'onion', 'TERE', 37), (55, 77, 10.5, 'round', 'CORAIMA', 58)],
  'cuatro': [
    (31, 61, 10.0, 'onion', 'JOSE', 37), (55, 50, 9.5, 'round', 'IZAN', 30),
    (79, 61, 10.0, 'onion', 'JOSE', 37), (55, 77, 10.5, 'round', 'ESTRELLA', 58)],
}
BALLS = CONFIGS[CFG]
def ball_shape(im, x, y, r, kind):
    disc(im, x, y, r)
    if kind == 'onion':
        poly(im, [(x-r*0.72, y+r*0.62), (x+r*0.72, y+r*0.62), (x, y+r*1.42)])
        poly(im, [(x-r*0.55, y-r*0.75), (x+r*0.55, y-r*0.75), (x, y-r*1.22)])
    top = y - (r*1.22 if kind == 'onion' else r)
    ImageDraw.Draw(im).rounded_rectangle([P(x-2.4, top-2.6), P(x+2.4, top+0.8)], radius=0.6*PX, fill=255)
    return top-2.6

def balls_layer():
    im = new()
    tops = []
    for x, y, r, k, n, ys in BALLS:
        tops.append(ball_shape(im, x, y, r, k))
    # banda superior (queda tapada por las ramitas)
    band = new(); disc(band, CX, CY, 40.4); disc(band, CX, CY, 36.6, 0)
    wedge = new(); ang = np.radians(np.linspace(222, 318, 60))
    poly(wedge, [(CX, CY)] + [(CX+50*math.cos(a), CY+50*math.sin(a)) for a in ang])
    band = Image.fromarray(np.minimum(np.array(band), np.array(wedge)))
    d = ImageDraw.Draw(im)
    for (x, y, r, k, n, ys), t in zip(BALLS, tops):
        d.rectangle([P(x-0.8, ys), P(x+0.8, t+0.5)], fill=255)
    return smooth(OR(im, band), 0.3)

def names_engrave():
    im = new(); caps = []
    cap = 3.0
    for x, y, r, k, n, ys in BALLS:
        caps.append(text_center(im, n, x, y + (0.6 if k == 'onion' else 0), r*1.72, cap))
    return im, caps

# ---------- ramitas ----------
def leaves():
    im = new()
    rs = 39.2
    for side in (-1, 1):
        angs = np.radians(270 + side*np.linspace(0, 46, 120))
        stroke(im, [(CX+rs*math.cos(a), CY+rs*math.sin(a)) for a in angs], 1.6, 1.0)
        for i, deg in enumerate(np.arange(5, 46, 6.5)):
            a = math.radians(270 + side*deg)
            bx, by = CX+rs*math.cos(a), CY+rs*math.sin(a)
            tang = math.degrees(a) + 90*side       # dirección hacia la punta de la rama
            out = 1 if i % 2 == 0 else -1
            ang = tang - side*out*42
            L, Wd = 6.4, 3.1
            ca, sa = math.cos(math.radians(ang)), math.sin(math.radians(ang))
            pts = []
            for t in np.linspace(0, 1, 40):
                pts.append((t*L, Wd/2*math.sin(math.pi*t)**0.75))
            for t in np.linspace(1, 0, 40):
                pts.append((t*L, -Wd/2*math.sin(math.pi*t)**0.75))
            poly(im, [(bx+px*ca-py*sa, by+px*sa+py*ca) for px, py in pts])
        # hoja final en la punta
        a = math.radians(270 + side*46); bx, by = CX+rs*math.cos(a), CY+rs*math.sin(a)
        ang = math.degrees(a) + 90*side; ca, sa = math.cos(math.radians(ang)), math.sin(math.radians(ang))
        pts = [(t*7, 1.8*math.sin(math.pi*t)**0.75) for t in np.linspace(0, 1, 40)] + \
              [(t*7, -1.8*math.sin(math.pi*t)**0.75) for t in np.linspace(1, 0, 40)]
        poly(im, [(bx+px*ca-py*sa, by+px*sa+py*ca) for px, py in pts])
    return smooth(im, 0.3)

# ---------- lazo ----------
def bow():
    im = new()
    ellipse(im, 48.3, 25.6, 7.4, 4.0, -14)
    ellipse(im, 48.6, 25.9, 4.3, 1.5, -14, 0)
    poly(im, [(54.2, 29.5), (52.2, 29.2), (46.5, 38.8), (48.6, 37.6), (49.6, 40.2), (55, 31.5)])
    im = OR(im, mirror(im))
    ellipse(im, 55, 28.2, 3.0, 3.3, 0)
    return smooth(im, 0.3)

# ---------- capas ----------
ant = antlers(); tab, hole = tab_and_hole()
base = new(); disc(base, CX, CY, R); base = SUB(OR(base, ant, tab), hole)
ring = new(); disc(ring, CX, CY, R); disc(ring, CX, CY, 41, 0)
frame = SUB(OR(ring, ant, tab), hole)
balls = balls_layer()
names, caps = names_engrave()
lema = new(); text_arc_bottom(lema, LEMA, 38.2, 3.4)
lf = leaves(); bw = bow()

# ---------- vectorizar ----------
def _smooth_contour(c, sigma):
    p = c[:, 0, :].astype(np.float64)
    n = len(p)
    if n < 8 or sigma <= 0: return p
    k = int(sigma*3)
    xs = np.arange(-k, k+1); w = np.exp(-xs**2/(2*sigma**2)); w /= w.sum()
    out = np.zeros_like(p)
    for i, wi in zip(xs, w): out += wi*np.roll(p, -i, axis=0)
    return out
def _bezier_path(pts):
    # Catmull-Rom cerrada -> curvas Bézier cúbicas (contorno suave, sin escalones)
    n = len(pts); d = f'M{pts[0][0]:.3f},{pts[0][1]:.3f}'
    for i in range(n):
        p0, p1, p2, p3 = pts[i-1], pts[i], pts[(i+1) % n], pts[(i+2) % n]
        c1 = p1 + (p2-p0)/6.0; c2 = p2 - (p3-p1)/6.0
        d += f' C{c1[0]:.3f},{c1[1]:.3f} {c2[0]:.3f},{c2[1]:.3f} {p2[0]:.3f},{p2[1]:.3f}'
    return d + 'Z'
def paths(im, ox=0, oy=0, minarea=0, sigma=5.0):
    a = np.array(im)
    cs, _ = cv2.findContours(a, cv2.RETR_CCOMP, cv2.CHAIN_APPROX_NONE)
    out = []
    for c in cs:
        if cv2.contourArea(c) < minarea*PX*PX: continue
        p = _smooth_contour(c, sigma if len(c) > 60 else sigma/2)
        p = cv2.approxPolyDP(p.astype(np.float32).reshape(-1, 1, 2), 0.35, True)[:, 0, :].astype(np.float64)
        if len(p) < 3: continue
        pts = p/PX + np.array([ox, oy])
        out.append(_bezier_path(pts))
    return ' '.join(out)
def cut(im, ox=0, oy=0): return f'<path d="{paths(im, ox, oy, 1.0)}" fill="none" stroke="#ff0000" stroke-width="0.1" fill-rule="evenodd"/>'
def eng(im, ox=0, oy=0): return f'<path d="{paths(im, ox, oy, 0, 2.0)}" fill="#0000ff" stroke="none" fill-rule="evenodd"/>'
def svg(body, w=W, h=H): return f'<svg xmlns="http://www.w3.org/2000/svg" width="{w:.1f}mm" height="{h:.1f}mm" viewBox="0 0 {w:.2f} {h:.2f}">{body}</svg>'
def bbox(im):
    ys, xs = np.nonzero(np.array(im)); return xs.min()/PX, ys.min()/PX, xs.max()/PX, ys.max()/PX

os.makedirs(f'{OUT}/capas', exist_ok=True)
layers = [
    ('1_base_madera_oscura', base, lema, 'Base madera oscura (grabar el lema)'),
    ('2_aro_astas_madera_clara', frame, None, 'Aro + astas, madera clara'),
    ('3_bolas_con_nombres', balls, names, 'Bolas con nombres, madera clara'),
    ('4_ramitas_verdes', lf, None, 'Ramitas, verde'),
    ('5_lazo_rojo', bw, None, 'Lazo, rojo'),
]
for fn, c, e, _ in layers:
    open(f'{OUT}/capas/{fn}.svg', 'w').write(svg((eng(e) if e is not None else '') + cut(c)))

# todas las piezas en una hoja
x0, y0, gap, body, sheet_h = 3.0, 8.0, 6.0, '', 0
for fn, c, e, label in layers:
    bx0, by0, bx1, by1 = bbox(c)
    ox, oy = x0 - bx0, y0 - by0
    body += f'<text x="{x0:.1f}" y="{y0-2:.1f}" font-size="3" font-family="sans-serif" fill="#00aa00">{label}</text>'
    body += (eng(e, ox, oy) if e is not None else '') + cut(c, ox, oy)
    x0 += (bx1-bx0) + gap; sheet_h = max(sheet_h, by1-by0+y0+3)
open(f'{OUT}/familia_bolas_capas_en_un_archivo.svg', 'w').write(svg(body, x0, sheet_h))

# montaje de referencia a color
def fillp(im, col, op=1): return f'<path d="{paths(im)}" fill="{col}" fill-opacity="{op}" stroke="#3a2510" stroke-width="0.12" fill-rule="evenodd"/>'
mont = (fillp(base, '#8a5530') + f'<path d="{paths(lema)}" fill="#3a2414"/>' + fillp(frame, '#ecd3a8')
        + fillp(balls, '#f2dcb4') + f'<path d="{paths(names)}" fill="#7a5532"/>'
        + fillp(lf, '#93b89c') + fillp(bw, '#d8261f'))
open(f'{OUT}/capas/0_montaje_referencia.svg', 'w').write(svg(mont))
open(f'{OUT}/familia_bolas_montaje.svg', 'w').write(svg(mont))
print('caps nombres (mm):', [round(c, 2) for c in caps])
print('tamaño adorno (mm):', [round(v, 1) for v in bbox(base)])
