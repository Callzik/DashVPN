package app.dash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Main screen tiles on a 4-column grid. In edit mode (long press) a tile is picked (the others fade back):
 * drag it to move, drag a corner to resize; other tiles make room.
 */
public class TileGrid extends ViewGroup {
    interface Host {
        /** A view for the tile at its current size. */
        View make(Tiles.Item it);
        /** The layout changed by hand: save it. */
        void changed(List<Tiles.Item> items);
        /** Edit mode or the picked tile changed (for the edit bar). */
        void editState(boolean edit, String selected);
    }

    private final Host host;
    private List<Tiles.Item> items = new ArrayList<>();
    private final Map<String, View> views = new HashMap<>();
    private final Map<String, String> sizes = new HashMap<>(); // id → "w,h" the view was built for
    private boolean edit;
    private String sel;

    private final int gap, rowH, handle;
    private final Paint ghost = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rf = new RectF();
    private final android.view.animation.Interpolator ease = new android.view.animation.DecelerateInterpolator(1.8f);
    // tiles rebuilt for a new size grow or shrink from their old size, anchored at this corner
    private final Map<String, float[]> grow = new HashMap<>();

    // gesture
    private static final int NONE = 0, MOVE = 1, RESIZE = 2;
    private int mode = NONE, corner; // corner: bit 1 = right, bit 2 = bottom
    private float downX, downY, grabX, grabY;
    private Tiles.Item start; // the picked tile as it was when the gesture began
    private List<Tiles.Item> before;

    public TileGrid(Context c, Host host) {
        super(c);
        this.host = host;
        gap = Ui.dp(c, 10);
        rowH = Ui.dp(c, 68);
        handle = Ui.dp(c, 30);
        // room around the tiles for the lifted tile; the parent pulls the grid out by the same amount
        int pad = pad(c);
        setPadding(pad, pad, pad, pad);
        setWillNotDraw(false);
        setClipChildren(false);
        ghost.setStyle(Paint.Style.STROKE);
        ghost.setStrokeWidth(Ui.dp(c, 1.5f));
        ghost.setColor(0x998B7CFF);
        ghost.setPathEffect(new DashPathEffect(new float[]{Ui.dp(c, 6), Ui.dp(c, 5)}, 0));
    }

    static int pad(Context c) { return Ui.dp(c, 9); }

    void setItems(List<Tiles.Item> list) {
        items = list;
        sync(true);
    }

    List<Tiles.Item> items() { return items; }

    boolean editing() { return edit; }

    void setEdit(boolean on, String select) {
        edit = on;
        sel = on ? select : null;
        for (View v : views.values()) v.setEnabled(!on);
        picked();
        requestLayout();
        invalidate();
    }

    /** No frame: while editing, the tiles that are not picked fade back. */
    private float shade(String id) {
        return !edit || sel == null || id.equals(sel) ? 1f : 0.45f;
    }

    private void picked() {
        host.editState(edit, sel);
        for (Map.Entry<String, View> en : views.entrySet())
            en.getValue().animate().alpha(shade(en.getKey())).setDuration(180).start();
    }

    /** Hides the picked tile. */
    void removeSelected() {
        Tiles.Item it = sel == null ? null : Tiles.find(items, sel);
        if (it == null) return;
        it.on = false;
        Tiles.settle(items, null);
        sel = null;
        sync(false);
        host.changed(items);
        picked();
    }

    /** Shows a hidden tile at the first free spot and picks it. */
    void add(String id) {
        Tiles.Item it = Tiles.find(items, id);
        if (it == null) return;
        it.on = true;
        it.w = Tiles.defaultW(id);
        it.h = 1;
        Tiles.place(items, it);
        sel = id;
        sync(false);
        host.changed(items);
        picked();
    }

    /** Creates views for shown tiles (or rebuilds one whose size changed) and drops views of hidden ones. */
    private void sync(boolean all) {
        for (Tiles.Item it : items) {
            View v = views.get(it.id);
            String size = it.w + "," + it.h;
            if (!it.on) {
                if (v != null) { removeView(v); views.remove(it.id); sizes.remove(it.id); }
                continue;
            }
            if (v == null || all || !size.equals(sizes.get(it.id))) {
                if (v != null) {
                    if (!all && v.getWidth() > 0) { // remember where it was to grow from there
                        boolean right = mode == RESIZE && (corner & 1) == 0, bottom = mode == RESIZE && (corner & 2) == 0;
                        grow.put(it.id, new float[]{v.getLeft(), v.getTop(), v.getWidth(), v.getHeight(), right ? 1 : 0, bottom ? 1 : 0});
                    }
                    removeView(v);
                }
                v = host.make(it);
                v.setEnabled(!edit);
                v.setAlpha(shade(it.id));
                views.put(it.id, v);
                sizes.put(it.id, size);
                addView(v);
            }
        }
        requestLayout();
        invalidate();
    }

    private int cellW() { return (getWidth() - getPaddingLeft() - getPaddingRight() - gap * (Tiles.COLS - 1)) / Tiles.COLS; }

    private float colX(int c) { return getPaddingLeft() + c * (cellW() + gap); }

    private float rowY(int r) { return getPaddingTop() + r * (rowH + gap); }

    @Override protected void onMeasure(int ws, int hs) {
        int w = MeasureSpec.getSize(ws);
        int cw = (w - getPaddingLeft() - getPaddingRight() - gap * (Tiles.COLS - 1)) / Tiles.COLS;
        int rows = Tiles.rows(items);
        for (Tiles.Item it : items) {
            View v = views.get(it.id);
            if (v == null) continue;
            v.measure(MeasureSpec.makeMeasureSpec(it.w * cw + (it.w - 1) * gap, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(it.h * rowH + (it.h - 1) * gap, MeasureSpec.EXACTLY));
        }
        // in edit mode one spare row below, so a tile can be dropped under the others
        int r = rows + (edit ? 1 : 0);
        setMeasuredDimension(w, r == 0 ? 0 : r * rowH + (r - 1) * gap + getPaddingTop() + getPaddingBottom());
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (Tiles.Item it : items) {
            View v = views.get(it.id);
            if (v == null) continue;
            int x = (int) colX(it.c), y = (int) rowY(it.r);
            int ox = v.getLeft(), oy = v.getTop();
            boolean had = v.getWidth() > 0;
            v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
            boolean dragged = mode == MOVE && it.id.equals(sel);
            if (dragged && had) { // stays under the finger
                v.setTranslationX(v.getTranslationX() + ox - x);
                v.setTranslationY(v.getTranslationY() + oy - y);
            } else if (had && (ox != x || oy != y)) { // other tiles slide to their new place
                v.setTranslationX(v.getTranslationX() + ox - x);
                v.setTranslationY(v.getTranslationY() + oy - y);
                v.animate().translationX(0).translationY(0).setDuration(280).setInterpolator(ease).start();
            }
            float[] g = grow.remove(it.id);
            if (g != null && v.getMeasuredWidth() > 0) { // resized: grow from the old size at the anchored corner
                float nw = v.getMeasuredWidth(), nh = v.getMeasuredHeight();
                v.setPivotX(g[4] == 1 ? nw : 0);
                v.setPivotY(g[5] == 1 ? nh : 0);
                v.setScaleX(g[2] / nw);
                v.setScaleY(g[3] / nh);
                v.setAlpha(0.6f);
                v.animate().scaleX(1).scaleY(1).alpha(shade(it.id)).setDuration(220).setInterpolator(ease).start();
            }
        }
    }

    @Override protected void dispatchDraw(Canvas c) {
        float rad = Ui.dp(getContext(), 16);
        Tiles.Item s = edit && sel != null ? Tiles.find(items, sel) : null;
        if (s != null && mode == MOVE) { // where the tile lands
            rect(s, 0);
            c.drawRoundRect(rf, rad, rad, ghost);
        }
        super.dispatchDraw(c);
    }

    private void rect(Tiles.Item it, float out) {
        int cw = cellW();
        rf.set(colX(it.c) - out, rowY(it.r) - out, colX(it.c) + it.w * cw + (it.w - 1) * gap + out,
                rowY(it.r) + it.h * rowH + (it.h - 1) * gap + out);
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        return edit;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (!edit) return super.onTouchEvent(e);
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                getParent().requestDisallowInterceptTouchEvent(true);
                downX = x;
                downY = y;
                mode = NONE;
                Tiles.Item s = sel == null ? null : Tiles.find(items, sel);
                if (s != null) {
                    rect(s, 0);
                    float[][] cs = {{rf.left, rf.top}, {rf.right, rf.top}, {rf.left, rf.bottom}, {rf.right, rf.bottom}};
                    for (int i = 0; i < 4; i++) {
                        if (Math.abs(x - cs[i][0]) < handle && Math.abs(y - cs[i][1]) < handle) {
                            mode = RESIZE;
                            corner = i;
                            break;
                        }
                    }
                }
                if (mode == NONE) {
                    Tiles.Item hit = hit(x, y);
                    if (hit != null) {
                        if (!hit.id.equals(sel)) {
                            sel = hit.id;
                            picked();
                        }
                        mode = MOVE;
                        View hv = views.get(hit.id);
                        if (hv != null) { // pick the tile up: it shrinks a little, so it never grows past the edges
                            hv.bringToFront();
                            hv.setPivotX(hv.getWidth() / 2f);
                            hv.setPivotY(hv.getHeight() / 2f);
                            hv.animate().scaleX(0.96f).scaleY(0.96f).setDuration(160).setInterpolator(ease).start();
                        }
                        rect(hit, 0);
                        grabX = x - rf.left;
                        grabY = y - rf.top;
                    } else {
                        sel = null;
                        picked();
                    }
                }
                if (mode != NONE) {
                    start = Tiles.find(items, sel).copy();
                    before = new ArrayList<>();
                    for (Tiles.Item it : items) before.add(it.copy());
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mode == MOVE) drag(x, y);
                else if (mode == RESIZE) resize(x, y);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (mode == MOVE) {
                    View v = views.get(sel);
                    if (v != null) v.animate().translationX(0).translationY(0).scaleX(1).scaleY(1)
                            .setDuration(260).setInterpolator(ease).start();
                }
                if (mode != NONE) Tiles.settle(items, null);
                boolean any = mode != NONE && !same(before, items);
                mode = NONE;
                requestLayout();
                invalidate();
                if (any) host.changed(items);
                return true;
            }
        }
        return true;
    }

    private Tiles.Item hit(float x, float y) {
        for (Tiles.Item it : items) {
            if (!it.on) continue;
            rect(it, 0);
            if (rf.contains(x, y)) return it;
        }
        return null;
    }

    /** The picked tile follows the finger; its cell is the nearest one, and other tiles make room. */
    private void drag(float x, float y) {
        Tiles.Item s = Tiles.find(items, sel);
        View v = views.get(sel);
        if (s == null || v == null) return;
        // the tile stays inside the grid so nothing is cut off at the screen edges
        float left = Math.max(getPaddingLeft(), Math.min(getWidth() - getPaddingRight() - v.getWidth(), x - grabX));
        float top = Math.max(getPaddingTop(), y - grabY);
        int c = Math.round((left - getPaddingLeft()) / (cellW() + gap));
        int r = Math.round(top / (rowH + gap));
        c = Math.max(0, Math.min(Tiles.COLS - s.w, c));
        r = Math.max(0, Math.min(Tiles.rows(before), r)); // down to the spare row
        if (c != s.c || r != s.r) apply(c, r, s.w, s.h);
        v.setTranslationX(left - v.getLeft());
        v.setTranslationY(top - v.getTop());
        invalidate();
    }

    /** The dragged corner snaps to the nearest grid line; the opposite corner stays put. */
    private void resize(float x, float y) {
        int cw = cellW();
        boolean right = (corner & 1) != 0, bottom = (corner & 2) != 0;
        int c0 = start.c, r0 = start.r, c1 = start.c + start.w, r1 = start.r + start.h; // edges in cells
        if (right) c1 = Math.round((x + gap / 2f - getPaddingLeft()) / (cw + gap));
        else c0 = Math.round((x + gap / 2f - getPaddingLeft()) / (cw + gap));
        if (bottom) r1 = Math.round((y + gap / 2f) / (rowH + gap));
        else r0 = Math.round((y + gap / 2f) / (rowH + gap));
        if (right) c1 = Math.max(c0 + 1, Math.min(Tiles.COLS, c1));
        else c0 = Math.max(0, Math.min(c1 - 1, c0));
        if (bottom) r1 = Math.max(r0 + 1, Math.min(r0 + Tiles.MAX_H, r1));
        else r0 = Math.max(Math.max(0, r1 - Tiles.MAX_H), Math.min(r1 - 1, r0));
        Tiles.Item s = Tiles.find(items, sel);
        if (s.c != c0 || s.r != r0 || s.w != c1 - c0 || s.h != r1 - r0) {
            apply(c0, r0, c1 - c0, r1 - r0);
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
    }

    /** Lays the grid out again from the state at gesture start with the picked tile at the new spot. */
    private void apply(int c, int r, int w, int h) {
        List<Tiles.Item> next = new ArrayList<>();
        Tiles.Item moved = null;
        for (Tiles.Item it : before) {
            Tiles.Item n = it.copy();
            if (n.id.equals(sel)) { n.c = c; n.r = r; n.w = w; n.h = h; moved = n; }
            next.add(n);
        }
        Tiles.settle(next, moved, false);
        // keep the same objects so the view map stays valid
        for (Tiles.Item n : next) {
            Tiles.Item it = Tiles.find(items, n.id);
            it.c = n.c; it.r = n.r; it.w = n.w; it.h = n.h;
        }
        sync(false);
    }

    private static boolean same(List<Tiles.Item> a, List<Tiles.Item> b) {
        for (Tiles.Item x : a) {
            Tiles.Item y = Tiles.find(b, x.id);
            if (y == null || x.on != y.on || x.c != y.c || x.r != y.r || x.w != y.w || x.h != y.h) return false;
        }
        return true;
    }
}
