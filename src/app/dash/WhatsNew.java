package app.dash;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/** "Что нового" card rising from the bottom: comet logo, version, one line per change with a star. */
final class WhatsNew {
    private WhatsNew() {}

    static void show(Activity a, String version, List<String> lines) {
        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout sheet = new LinearLayout(a);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setGravity(Gravity.CENTER_HORIZONTAL);
        sheet.setPadding(Ui.dp(a, 20), Ui.dp(a, 26), Ui.dp(a, 20), Ui.dp(a, 18));
        // surface with a violet glow at the top
        GradientDrawable glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow.setColors(new int[]{0x598B7CFF, 0x008B7CFF});
        glow.setGradientCenter(0.5f, 0f);
        glow.setGradientRadius(Ui.dp(a, 220));
        glow.setCornerRadius(Ui.dp(a, 26));
        sheet.setBackground(new LayerDrawable(new android.graphics.drawable.Drawable[]{
                Ui.round(a, Ui.SURFACE, 26, Ui.LINE2), glow}));

        ImageView logo = new ImageView(a);
        logo.setImageResource(R.drawable.ic_logo);
        sheet.addView(logo, new LinearLayout.LayoutParams(Ui.dp(a, 58), Ui.dp(a, 58)));
        TextView kicker = Ui.text(a, "ЧТО НОВОГО", 12, Ui.ACCENT, true);
        kicker.setLetterSpacing(0.12f);
        sheet.addView(kicker, Ui.lp(a, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 12));
        TextView ver = Ui.text(a, "Dash " + version, 26, Ui.FG, true);
        ver.setLetterSpacing(-0.02f);
        sheet.addView(ver, Ui.lp(a, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 2));

        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                View sep = new View(a);
                sep.setBackgroundColor(Ui.LINE);
                list.addView(sep, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
            }
            list.addView(item(a, lines.get(i)));
        }
        ScrollView sv = new ScrollView(a);
        sv.addView(list);
        int maxH = (int) (a.getResources().getDisplayMetrics().heightPixels * 0.46f);
        sheet.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sv.measure(View.MeasureSpec.makeMeasureSpec(a.getResources().getDisplayMetrics().widthPixels - Ui.dp(a, 72), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        if (sv.getMeasuredHeight() > maxH) sv.getLayoutParams().height = maxH;
        ((LinearLayout.LayoutParams) sv.getLayoutParams()).topMargin = Ui.dp(a, 16);

        TextView ok = Ui.text(a, "Отлично", 16, Ui.ACCENT_INK, true);
        ok.setGravity(Gravity.CENTER);
        ok.setBackground(Ui.round(a, Ui.ACCENT, 16, 0));
        ok.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); }
        });
        LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 50));
        okLp.topMargin = Ui.dp(a, 14);
        sheet.addView(ok, okLp);

        FrameLayout wrap = new FrameLayout(a);
        wrap.setPadding(Ui.dp(a, 16), 0, Ui.dp(a, 16), Ui.dp(a, 16));
        wrap.addView(sheet);
        d.setContentView(wrap);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.7f);
        }
        sheet.setTranslationY(Ui.dp(a, 60));
        sheet.setAlpha(0f);
        d.show();
        sheet.animate().translationY(0).alpha(1f).setDuration(450)
                .setInterpolator(new android.view.animation.OvershootInterpolator(0.8f)).start();
    }

    /** "Title: details" becomes a bold title over muted details. */
    private static View item(Activity a, String line) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, Ui.dp(a, 10), 0, Ui.dp(a, 10));
        TextView star = Ui.text(a, "★", 15, Ui.ACCENT, false);
        star.setGravity(Gravity.CENTER);
        star.setBackground(Ui.round(a, Ui.ACCENT_TINT, 10, 0));
        row.addView(star, new LinearLayout.LayoutParams(Ui.dp(a, 30), Ui.dp(a, 30)));

        SpannableStringBuilder sb = new SpannableStringBuilder();
        int i = line.indexOf(": ");
        if (i > 0 && i < 60) {
            String body = line.substring(i + 2);
            if (!body.isEmpty()) body = body.substring(0, 1).toUpperCase() + body.substring(1);
            sb.append(line.substring(0, i));
            sb.setSpan(new StyleSpan(Typeface.BOLD), 0, i, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new RelativeSizeSpan(15 / 14f), 0, i, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append('\n');
            int st = sb.length();
            sb.append(body);
            sb.setSpan(new ForegroundColorSpan(Ui.MUTED), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else {
            sb.append(line);
        }
        TextView t = Ui.text(a, "", 14, Ui.FG, false);
        t.setText(sb);
        t.setLineSpacing(Ui.dp(a, 2), 1f);
        t.setPadding(Ui.dp(a, 12), Ui.dp(a, 4), 0, 0);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }
}
