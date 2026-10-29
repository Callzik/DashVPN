package app.dash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * Subscription orbit: the arc is the traffic spent, the number the days left. Accent when fine, amber when
 * under 3 days or 10% left, red with "!" when over, dotted with ∞ when unlimited and endless. Hidden without data.
 */
public final class SubRing extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), arc = new Paint(Paint.ANTI_ALIAS_FLAG),
            fill = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private float part;
    private String label = "";
    private int color;
    private boolean dotted;

    public SubRing(Context c) {
        super(c);
        float sw = Ui.dp(c, 2.5f);
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(sw);
        track.setColor(Ui.LINE);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(sw);
        arc.setStrokeCap(Paint.Cap.ROUND);
        fill.setColor(Ui.SURFACE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        setVisibility(GONE);
    }

    public void set(SubInfo si) {
        if (si == null) {
            setVisibility(GONE);
            return;
        }
        long now = System.currentTimeMillis();
        int d = si.daysLeft(now);
        boolean over = si.over(now);
        color = over ? Ui.BAD : si.low(now) ? Ui.WARN : Ui.ACCENT;
        part = over ? 1f : si.usedPart();
        dotted = !over && si.total <= 0;
        label = over ? "!" : d >= 0 ? (d > 999 ? "999" : String.valueOf(d)) : "∞";
        setContentDescription("Подписка: " + si.text(now));
        setVisibility(VISIBLE);
        invalidate();
    }

    @Override protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight(), sw = arc.getStrokeWidth();
        float rad = Math.min(w, h) / 2f - sw;
        cv.drawCircle(w / 2, h / 2, rad, fill);
        r.set(w / 2 - rad, h / 2 - rad, w / 2 + rad, h / 2 + rad);
        arc.setColor(color);
        if (dotted) {
            arc.setAlpha(110);
            arc.setPathEffect(new android.graphics.DashPathEffect(new float[]{sw * 0.2f, sw * 2.2f}, 0));
            cv.drawCircle(w / 2, h / 2, rad, arc);
            arc.setPathEffect(null);
            arc.setAlpha(255);
        } else {
            cv.drawCircle(w / 2, h / 2, rad, track);
            if (part > 0) cv.drawArc(r, -90, Math.max(4f, 360 * part), false, arc);
        }
        text.setColor(color == Ui.ACCENT ? Ui.FG : color);
        text.setTextSize(label.length() > 2 ? h * 0.26f : h * 0.32f);
        Paint.FontMetrics fm = text.getFontMetrics();
        cv.drawText(label, w / 2, h / 2 - (fm.ascent + fm.descent) / 2, text);
    }
}
