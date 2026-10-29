package app.dash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Line icons drawn in code on a 24×24 grid, so the APK needs no image resources. */
final class IconView extends View {
    static final int SUBS = 0, AUTO = 1, ROUTE = 2, SHARE = 3, APPS = 4, HOME = 5, SERVERS = 6,
            UPDATE = 7, LOG = 8, SEARCH = 9, CHEVRON = 10, BACK = 11, CLEAR = 12, ISLAND = 13,
            REFRESH = 14, CHECK = 15, LOCK = 16;

    private final int kind;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();

    IconView(Context c, int kind, int color) {
        super(c);
        this.kind = kind;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(color);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onDraw(Canvas cv) {
        float s = Math.min(getWidth(), getHeight()) / 24f;
        cv.save();
        cv.translate((getWidth() - 24 * s) / 2f, (getHeight() - 24 * s) / 2f);
        cv.scale(s, s);
        p.setStrokeWidth(1.8f);
        p.setStyle(Paint.Style.STROKE);
        path.reset();
        switch (kind) {
            case SUBS: // stacked cards
                rect(cv, 4, 10, 20, 20, 3);
                line(cv, 6, 6.5f, 18, 6.5f);
                line(cv, 8.5f, 3.5f, 15.5f, 3.5f);
                break;
            case AUTO: // lightning
                path.moveTo(13, 2.5f); path.lineTo(5, 13.5f); path.lineTo(11.5f, 13.5f);
                path.lineTo(10.5f, 21.5f); path.lineTo(19, 10.5f); path.lineTo(12.5f, 10.5f); path.close();
                cv.drawPath(path, p);
                break;
            case ROUTE: // fork
                path.moveTo(12, 21); path.lineTo(12, 13); path.lineTo(6, 6);
                path.moveTo(12, 13); path.lineTo(18, 6);
                cv.drawPath(path, p);
                dot(cv, 6, 5, 2); dot(cv, 18, 5, 2);
                break;
            case SHARE: // hotspot waves
                dot(cv, 12, 12, 1.8f);
                arcs(cv, 5); arcs(cv, 9);
                break;
            case APPS:
                rect(cv, 4, 4, 10.5f, 10.5f, 2.5f); rect(cv, 13.5f, 4, 20, 10.5f, 2.5f);
                rect(cv, 4, 13.5f, 10.5f, 20, 2.5f); rect(cv, 13.5f, 13.5f, 20, 20, 2.5f);
                break;
            case HOME: // phone screen with tiles
                rect(cv, 6, 2.5f, 18, 21.5f, 3);
                line(cv, 9.5f, 7.5f, 14.5f, 7.5f);
                line(cv, 9.5f, 11.5f, 14.5f, 11.5f);
                line(cv, 10.5f, 18.5f, 13.5f, 18.5f);
                break;
            case SERVERS:
                rect(cv, 3.5f, 4, 20.5f, 10.5f, 2.5f); rect(cv, 3.5f, 13.5f, 20.5f, 20, 2.5f);
                fill(cv, 7.5f, 7.25f, 1.1f); fill(cv, 7.5f, 16.75f, 1.1f);
                break;
            case UPDATE:
                cv.drawCircle(12, 12, 9, p);
                path.moveTo(12, 7.5f); path.lineTo(12, 16);
                path.moveTo(8.5f, 12.5f); path.lineTo(12, 16); path.lineTo(15.5f, 12.5f);
                cv.drawPath(path, p);
                break;
            case LOG: // pulse
                path.moveTo(2.5f, 12); path.lineTo(6.5f, 12); path.lineTo(9.5f, 5.5f);
                path.lineTo(14.5f, 18.5f); path.lineTo(17.5f, 12); path.lineTo(21.5f, 12);
                cv.drawPath(path, p);
                break;
            case SEARCH:
                cv.drawCircle(10.5f, 10.5f, 6, p);
                line(cv, 15, 15, 20, 20);
                break;
            case CHEVRON:
                path.moveTo(9.5f, 6); path.lineTo(15.5f, 12); path.lineTo(9.5f, 18);
                cv.drawPath(path, p);
                break;
            case BACK:
                path.moveTo(14.5f, 5.5f); path.lineTo(8, 12); path.lineTo(14.5f, 18.5f);
                cv.drawPath(path, p);
                break;
            case CLEAR:
                line(cv, 7, 7, 17, 17); line(cv, 17, 7, 7, 17);
                break;
            case REFRESH: // circular arrow
                r.set(4, 4, 20, 20);
                cv.drawArc(r, -45, -270, false, p);
                path.moveTo(18, 2.5f); path.lineTo(18, 6.8f); path.lineTo(13.7f, 6.8f);
                cv.drawPath(path, p);
                break;
            case CHECK:
                path.moveTo(5, 12.5f); path.lineTo(9.5f, 17); path.lineTo(19, 7.5f);
                cv.drawPath(path, p);
                break;
            case ISLAND: // pill at the top of a phone screen
                rect(cv, 6, 2.5f, 18, 21.5f, 3);
                rect(cv, 8.5f, 5.5f, 15.5f, 8.5f, 1.5f);
                break;
            case LOCK: // padlock
                rect(cv, 4.5f, 10.5f, 19.5f, 21, 3);
                r.set(8, 3, 16, 11);
                cv.drawArc(r, 180, 180, false, p);
                line(cv, 8, 7, 8, 10.5f); line(cv, 16, 7, 16, 10.5f);
                fill(cv, 12, 15.75f, 1.4f);
                break;
        }
        cv.restore();
    }

    private void rect(Canvas cv, float l, float t, float rt, float b, float rad) {
        r.set(l, t, rt, b);
        cv.drawRoundRect(r, rad, rad, p);
    }

    private void line(Canvas cv, float x1, float y1, float x2, float y2) {
        cv.drawLine(x1, y1, x2, y2, p);
    }

    private void dot(Canvas cv, float x, float y, float rad) {
        cv.drawCircle(x, y, rad, p);
    }

    private void fill(Canvas cv, float x, float y, float rad) {
        p.setStyle(Paint.Style.FILL);
        cv.drawCircle(x, y, rad, p);
        p.setStyle(Paint.Style.STROKE);
    }

    private void arcs(Canvas cv, float rad) {
        r.set(12 - rad, 12 - rad, 12 + rad, 12 + rad);
        cv.drawArc(r, 140, 80, false, p);
        cv.drawArc(r, -40, 80, false, p);
    }
}
