package com.example.autoclick;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Busca una imagen pequeña (la plantilla) dentro de una captura de pantalla, sin OpenCV.
 *
 * La puntuación es la misma que da OpenCV con TM_CCOEFF_NORMED (1 = idéntica): primero se busca
 * en versiones reducidas de las dos imágenes (rápido) y luego se afina alrededor de los mejores
 * sitios, hasta llegar al tamaño real.
 *
 * Se usa en los móviles de 32 bits, donde cargar OpenCV puede cerrar el servicio de accesibilidad.
 * No usa nada de Android, para poder probarlo en un ordenador.
 */
public final class FastMatch {
    private FastMatch() {}

    /** Imagen en gris: un valor de 0 a 255 por píxel, fila a fila. */
    public static final class Gray {
        public final int[] px;
        public final int w, h;

        public Gray(int[] px, int w, int h) {
            this.px = px;
            this.w = w;
            this.h = h;
        }
    }

    /** Dónde está la plantilla (esquina de arriba a la izquierda) y cuánto se parece (1 = igual). */
    public static final class Result {
        public final int x, y;
        public final double score;

        Result(int x, int y, double score) {
            this.x = x;
            this.y = y;
            this.score = score;
        }
    }

    /** Límite de trabajo de la búsqueda gruesa, para que vaya rápida en móviles flojos. */
    private static final long COARSE_WORK = 25_000_000L;
    /** Sitios que se apuntan en la búsqueda gruesa y que se siguen afinando después. */
    private static final int FIRST_CANDIDATES = 20;
    private static final int KEEP_CANDIDATES = 6;

    /** Colores ARGB → gris, en el mismo array (mismas cuentas que OpenCV: 0,299 R + 0,587 G + 0,114 B). */
    public static void toGrayInPlace(int[] argb) {
        for (int i = 0; i < argb.length; i++) {
            int c = argb[i];
            argb[i] = (((c >> 16) & 0xff) * 4899 + ((c >> 8) & 0xff) * 9617 + (c & 0xff) * 1868 + 8192) >> 14;
        }
    }

    /**
     * Busca la plantilla t en la imagen s y devuelve el sitio que más se parece.
     * Devuelve null si la plantilla es más grande que la imagen o es de un solo color.
     */
    public static Result find(Gray s, Gray t) {
        if (t.w < 1 || t.h < 1 || t.w > s.w || t.h > s.h) return null;
        int minSide = Math.min(t.w, t.h);
        int f = Math.max(1, minSide / 16);
        while (minSide / (f + 1) >= 6 && coarseWork(s, t, f) > COARSE_WORK) f++;
        int[] levels = levels(f);

        // 1) Nivel más reducido: se mira la plantilla en todas las posiciones
        int g = levels[0];
        Gray sg = g == 1 ? s : shrink(s, g);
        Gray tg = g == 1 ? t : shrink(t, g);
        float[] map = scoreMap(sg, tg);
        if (map == null) return null;
        int rw = sg.w - tg.w + 1, rh = sg.h - tg.h + 1;
        if (levels.length == 1) {
            int best = 0;
            for (int i = 1; i < map.length; i++) if (map[i] > map[best]) best = i;
            return new Result(best % rw, best / rw, map[best]);
        }
        List<Result> cands = pickBest(map, rw, rh, FIRST_CANDIDATES, Math.max(1, tg.w / 4), Math.max(1, tg.h / 4));

        // 2) Afinar alrededor de esos sitios, cada vez con más detalle, hasta el tamaño real
        int pf = g;
        for (int li = 1; li < levels.length; li++) {
            int cf = levels[li];
            Gray sc = cf == 1 ? s : shrink(s, cf);
            Gray tc = cf == 1 ? t : shrink(t, cf);
            long n = (long) tc.w * tc.h;
            long sumT = 0, sumT2 = 0;
            for (int v : tc.px) {
                sumT += v;
                sumT2 += (long) v * v;
            }
            long varT = n * sumT2 - sumT * sumT;
            if (varT <= 0) return null;
            double sdT = Math.sqrt((double) varT);
            int r = (pf + cf - 1) / cf + 2;
            double top = cands.get(0).score;
            for (Result c : cands) top = Math.max(top, c.score);
            List<Result> next = new ArrayList<>();
            for (Result c : cands) {
                if (c.score < top - 0.3) continue; // muy por debajo del mejor: no merece la pena afinarlo
                int ex = c.x * pf / cf, ey = c.y * pf / cf;
                // Se mira alrededor; si lo mejor sale en el borde de lo mirado, se sigue hacia ese lado
                Result best = null;
                for (int it = 0; it < 8; it++) {
                    Result b = searchAround(sc, tc, sumT, sdT, ex, ey, r);
                    if (b == null || (best != null && b.score <= best.score)) break;
                    best = b;
                    if (Math.abs(b.x - ex) < r && Math.abs(b.y - ey) < r) break;
                    ex = b.x;
                    ey = b.y;
                }
                if (best != null) next.add(best);
            }
            if (next.isEmpty()) return null;
            next.sort((a, b) -> Double.compare(b.score, a.score));
            List<Result> kept = new ArrayList<>();
            for (Result c : next) {
                boolean dup = false;
                for (Result k : kept) if (k.x == c.x && k.y == c.y) { dup = true; break; }
                if (!dup) kept.add(c);
                if (kept.size() >= KEEP_CANDIDATES) break;
            }
            cands = kept;
            pf = cf;
        }
        return cands.get(0);
    }

    /** Cuánto trabajo costaría la búsqueda gruesa reduciendo f veces. */
    private static long coarseWork(Gray s, Gray t, int f) {
        long tw = t.w / f, th = t.h / f;
        long pw = s.w / f - tw + 1, ph = s.h / f - th + 1;
        return pw * ph * tw * th;
    }

    /** Factores de cada nivel: f, f/2, f/4 … hasta 1. */
    private static int[] levels(int f) {
        int[] tmp = new int[32];
        int k = 0;
        int g = f;
        while (true) {
            tmp[k++] = g;
            if (g == 1) break;
            g = Math.max(1, g / 2);
        }
        return Arrays.copyOf(tmp, k);
    }

    /** Reduce la imagen f veces (cada píxel nuevo es la media de un cuadrado de f×f). */
    static Gray shrink(Gray s, int f) {
        int ow = s.w / f, oh = s.h / f;
        int[] out = new int[ow * oh];
        int[] acc = new int[ow];
        int area = f * f;
        for (int oy = 0; oy < oh; oy++) {
            Arrays.fill(acc, 0);
            for (int dy = 0; dy < f; dy++) {
                int row = (oy * f + dy) * s.w;
                for (int ox = 0; ox < ow; ox++) {
                    int base = row + ox * f;
                    int sum = 0;
                    for (int dx = 0; dx < f; dx++) sum += s.px[base + dx];
                    acc[ox] += sum;
                }
            }
            int o = oy * ow;
            for (int ox = 0; ox < ow; ox++) out[o + ox] = (acc[ox] + area / 2) / area;
        }
        return new Gray(out, ow, oh);
    }

    /** Puntuación de la plantilla en todas las posiciones (null si la plantilla es de un solo color). */
    private static float[] scoreMap(Gray s, Gray t) {
        int rw = s.w - t.w + 1, rh = s.h - t.h + 1;
        long n = (long) t.w * t.h;
        long sumT = 0, sumT2 = 0;
        for (int v : t.px) {
            sumT += v;
            sumT2 += (long) v * v;
        }
        long varT = n * sumT2 - sumT * sumT;
        if (varT <= 0) return null;
        double sdT = Math.sqrt((double) varT);

        // Sumas acumuladas de la captura (para sacar rápido la suma y la suma de cuadrados de cada trozo)
        int W = s.w + 1;
        long[] I = new long[W * (s.h + 1)];
        long[] I2 = new long[W * (s.h + 1)];
        for (int y = 0; y < s.h; y++) {
            long row = 0, row2 = 0;
            int up = y * W, here = (y + 1) * W, src = y * s.w;
            for (int x = 0; x < s.w; x++) {
                int v = s.px[src + x];
                row += v;
                row2 += (long) v * v;
                I[here + x + 1] = I[up + x + 1] + row;
                I2[here + x + 1] = I2[up + x + 1] + row2;
            }
        }

        float[] out = new float[rw * rh];
        for (int y = 0; y < rh; y++) {
            for (int x = 0; x < rw; x++) {
                int a = y * W + x, b = a + t.w, c = (y + t.h) * W + x, d = c + t.w;
                long ss = I[d] - I[b] - I[c] + I[a];
                long ss2 = I2[d] - I2[b] - I2[c] + I2[a];
                long varS = n * ss2 - ss * ss;
                if (varS <= 0) continue; // trozo de un solo color: no se parece (se queda en 0)
                long st = 0;
                for (int j = 0; j < t.h; j++) {
                    int si = (y + j) * s.w + x, ti = j * t.w;
                    int rowSum = 0;
                    for (int i = 0; i < t.w; i++) rowSum += s.px[si + i] * t.px[ti + i];
                    st += rowSum;
                }
                out[y * rw + x] = (float) ((n * st - ss * sumT) / (Math.sqrt((double) varS) * sdT));
            }
        }
        return out;
    }

    /** El mejor sitio en un cuadrado de ±r alrededor de (cx, cy). */
    private static Result searchAround(Gray s, Gray t, long sumT, double sdT, int cx, int cy, int r) {
        Result best = null;
        for (int y = cy - r; y <= cy + r; y++) {
            if (y < 0 || y > s.h - t.h) continue;
            for (int x = cx - r; x <= cx + r; x++) {
                if (x < 0 || x > s.w - t.w) continue;
                double v = scoreAt(s, t, sumT, sdT, x, y);
                if (best == null || v > best.score) best = new Result(x, y, v);
            }
        }
        return best;
    }

    /** La puntuación en una sola posición (calculando todas las sumas a mano). */
    private static double scoreAt(Gray s, Gray t, long sumT, double sdT, int x, int y) {
        long n = (long) t.w * t.h;
        long ss = 0, ss2 = 0, st = 0;
        for (int j = 0; j < t.h; j++) {
            int si = (y + j) * s.w + x, ti = j * t.w;
            int rs = 0, rs2 = 0, rst = 0;
            for (int i = 0; i < t.w; i++) {
                int a = s.px[si + i];
                rs += a;
                rs2 += a * a;
                rst += a * t.px[ti + i];
            }
            ss += rs;
            ss2 += rs2;
            st += rst;
        }
        long varS = n * ss2 - ss * ss;
        if (varS <= 0) return 0;
        return (n * st - ss * sumT) / (Math.sqrt((double) varS) * sdT);
    }

    /** Los mejores sitios del mapa, separados entre sí (para no apuntar el mismo sitio varias veces). */
    private static List<Result> pickBest(float[] map, int rw, int rh, int count, int rx, int ry) {
        float[] m = map.clone();
        List<Result> out = new ArrayList<>();
        for (int k = 0; k < count; k++) {
            int best = -1;
            float bv = -3f;
            for (int i = 0; i < m.length; i++) {
                if (m[i] > bv) {
                    bv = m[i];
                    best = i;
                }
            }
            if (best < 0 || bv <= -2f) break;
            int bx = best % rw, by = best / rw;
            out.add(new Result(bx, by, bv));
            for (int y = Math.max(0, by - ry); y <= Math.min(rh - 1, by + ry); y++) {
                for (int x = Math.max(0, bx - rx); x <= Math.min(rw - 1, bx + rx); x++) m[y * rw + x] = -2.5f;
            }
        }
        return out;
    }
}
