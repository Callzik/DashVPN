package app.dash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import java.util.Random;

/** Star background: still when off, a warp jump while connecting, a slow drift and a nebula while connected. */
public class StarField extends View {
    private float[] x, y, r, a, ph, z, age;
    private boolean[] tw, tint;
    private int lastW, lastH;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tailP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random crnd = new Random();
    // background comet: flies by every few seconds
    private long cStart = -1, cNext = android.os.SystemClock.uptimeMillis() + 1200;
    private float cx0, cy0, cdx, cdy, cDist;
    private static final long C_DUR = 1400;
    // mode: warp and drift ease toward 0/1 so the jump speeds up and slows down
    private int mode = AppState.OFF;
    private float warp, drift, ccx = -1, ccy = -1;
    private long lastFrame;
    private final Paint neb = new Paint(Paint.ANTI_ALIAS_FLAG);

    public StarField(Context c) {
        super(c);
        setWillNotDraw(false);
        tailP.setStyle(Paint.Style.STROKE);
        tailP.setStrokeCap(Paint.Cap.ROUND);
        tailP.setStrokeWidth(Ui.dp(c, 2));
    }

    public void setMode(int m) {
        if (m == mode) return;
        mode = m;
        invalidate();
    }

    /** The point stars fly away from and the nebula glows around: the connect button's centre. */
    public void setCenter(float x, float y) {
        ccx = x;
        ccy = y;
    }

    private void drawNebula(Canvas c, float k) {
        float dp = Ui.dp(getContext(), 1);
        float[][] blobs = {{0, 0, 260, 0.28f}, {-90, 80, 180, 0.22f}, {110, -60, 150, 0.14f}, {40, 170, 170, 0.15f}};
        int[] cols = {0x8B7CFF, 0x5A3CC8, 0xC878FF, 0x465ADC};
        for (int i = 0; i < blobs.length; i++) {
            float bx = ccx + blobs[i][0] * dp, by = ccy + blobs[i][1] * dp, br = blobs[i][2] * dp;
            neb.setShader(new android.graphics.RadialGradient(bx, by, br,
                    (Math.round(blobs[i][3] * k * 255) << 24) | cols[i], cols[i], android.graphics.Shader.TileMode.CLAMP));
            c.drawCircle(bx, by, br, neb);
        }
        neb.setShader(null);
    }

    /** Returns true while a comet is in flight (needs smooth frames). */
    private boolean drawComet(Canvas c, int w, int h, long now) {
        if (cStart < 0) {
            if (now < cNext) return false;
            cStart = now;
            cx0 = (-0.1f + crnd.nextFloat() * 0.6f) * w;
            cy0 = (0.45f + crnd.nextFloat() * 0.5f) * h;
            double ang = Math.toRadians(-38 + (crnd.nextFloat() - 0.5f) * 20); // up and to the right, like the logo
            cdx = (float) Math.cos(ang);
            cdy = (float) Math.sin(ang);
            cDist = 0.75f * Math.max(w, h);
        }
        float t = (now - cStart) / (float) C_DUR;
        if (t >= 1f) {
            cStart = -1;
            cNext = now + 1500 + crnd.nextInt(3000);
            return false;
        }
        float e = 1f - (1f - t) * (1f - t); // ease-out
        float fade = t < 0.15f ? t / 0.15f : t > 0.7f ? (1f - t) / 0.3f : 1f;
        float dp = Ui.dp(getContext(), 1);
        float hx = cx0 + cdx * cDist * e, hy = cy0 + cdy * cDist * e;
        float len = dp * 120 * (0.4f + 0.6f * fade);
        float tx = hx - cdx * len, ty = hy - cdy * len;
        int acc = Ui.ACCENT & 0x00FFFFFF;
        tailP.setShader(new android.graphics.LinearGradient(tx, ty, hx, hy,
                acc, (Math.round(0.85f * fade * 255) << 24) | acc, android.graphics.Shader.TileMode.CLAMP));
        c.drawLine(tx, ty, hx, hy, tailP);
        tailP.setShader(null);
        p.setShader(new android.graphics.RadialGradient(hx, hy, dp * 9,
                new int[]{(Math.round(0.5f * fade * 255) << 24) | acc, acc}, null, android.graphics.Shader.TileMode.CLAMP));
        c.drawCircle(hx, hy, dp * 9, p);
        p.setShader(null);
        p.setColor((Math.round(fade * 255) << 24) | 0xE6E1FF);
        c.drawCircle(hx, hy, dp * 2.2f, p);
        return true;
    }

    @Override protected void onWindowVisibilityChanged(int v) {
        super.onWindowVisibilityChanged(v);
        if (v == VISIBLE) invalidate(); // restart the twinkle loop after returning to the screen
    }

    private void build(int w, int h) {
        lastW = w; lastH = h;
        Random rnd = new Random(7);
        float dp = Ui.dp(getContext(), 1);
        int n = Math.max(40, (int) (w * h / (dp * dp) / 1800f));
        x = new float[n]; y = new float[n]; r = new float[n]; a = new float[n]; ph = new float[n]; z = new float[n]; age = new float[n];
        tw = new boolean[n]; tint = new boolean[n];
        for (int i = 0; i < n; i++) {
            x[i] = rnd.nextFloat() * w;
            y[i] = rnd.nextFloat() * h;
            float s = rnd.nextFloat();
            r[i] = dp * (0.5f + s * s * 1.1f);
            a[i] = 0.12f + rnd.nextFloat() * 0.45f;
            ph[i] = rnd.nextFloat() * 6.283f;
            tw[i] = rnd.nextFloat() < 0.25f;
            tint[i] = rnd.nextFloat() < 0.2f;
            z[i] = 0.4f + rnd.nextFloat() * 0.6f;
            age[i] = 1f;
        }
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        if (x == null || w != lastW || h != lastH) build(w, h);
        if (ccx < 0) { ccx = w / 2f; ccy = h * 0.4f; }
        long nowMs = android.os.SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 0 : Math.min(100, nowMs - lastFrame) / 1000f;
        lastFrame = nowMs;
        float tw0 = mode == AppState.CONNECTING ? 1 : 0, td = mode == AppState.ON ? 1 : 0;
        warp += (tw0 - warp) * Math.min(1f, dt * (tw0 > 0 ? 1.5f : 3f));
        drift += (td - drift) * Math.min(1f, dt * 1.2f);
        if (warp < 0.002f) warp = tw0;
        if (Math.abs(drift - td) < 0.002f) drift = td;
        if (drift > 0.01f) drawNebula(c, drift);

        float dp = Ui.dp(getContext(), 1);
        float dim = mode == AppState.OFF || mode == AppState.WAITING ? 0.6f : 1f;
        double t = nowMs / 1000.0;
        Random rr = crnd;
        for (int i = 0; i < x.length; i++) {
            float vx = -14 * dp * drift * z[i], vy = 7 * dp * drift * z[i];
            if (warp > 0) {
                float dx = x[i] - ccx, dy = y[i] - ccy, L = Math.max(1f, (float) Math.hypot(dx, dy));
                float v = warp * (40 * dp + L * 2.2f) * z[i];
                vx += dx / L * v;
                vy += dy / L * v;
            }
            x[i] += vx * dt;
            y[i] += vy * dt;
            float m = 60 * dp;
            if (x[i] < -m || x[i] > w + m || y[i] < -m || y[i] > h + m) {
                // a star that left the screen comes back as a new one that fades in: near the centre
                // (under the button) during the jump, anywhere otherwise
                if (warp > 0.05f) {
                    double ang = rr.nextFloat() * 6.283, rad = (30 + rr.nextFloat() * 60) * dp;
                    x[i] = ccx + (float) (Math.cos(ang) * rad);
                    y[i] = ccy + (float) (Math.sin(ang) * rad);
                } else {
                    x[i] = rr.nextFloat() * w;
                    y[i] = rr.nextFloat() * h;
                }
                age[i] = 0;
            }
            age[i] = Math.min(1f, age[i] + dt);
            float al = a[i] * dim * Math.min(1f, age[i] / 0.5f);
            if (tw[i]) {
                al *= 0.45f + 0.55f * (float) (0.5 + 0.5 * Math.sin(t * 0.9 + ph[i]));
            }
            int base = tint[i] ? 0xC4BCFF : 0xFFFFFF;
            int col = (Math.round(al * 255) << 24) | base;
            float sp = (float) Math.hypot(vx, vy), len = Math.min(70 * dp, sp * 0.12f) * Math.min(1f, age[i] / 0.35f);
            if (len > 1.2f * dp) {
                tailP.setShader(null);
                tailP.setColor(col);
                float sw = tailP.getStrokeWidth();
                tailP.setStrokeWidth(r[i] * 1.4f);
                c.drawLine(x[i], y[i], x[i] - vx / sp * len, y[i] - vy / sp * len, tailP);
                tailP.setStrokeWidth(sw);
            } else {
                p.setColor(col);
                c.drawCircle(x[i], y[i], r[i], p);
            }
        }
        boolean flying = warp <= 0.3f && drawComet(c, w, h, nowMs);
        // ~15 fps is plenty for the slow twinkle, ~30 for the drift; smooth frames for a comet or the jump
        if (isShown()) postInvalidateDelayed(flying || warp > 0 ? 16 : drift > 0 ? 33 : 66);
        else lastFrame = 0;
    }
}
