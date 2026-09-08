package com.flowlink.client;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    static final int BACKGROUND = Color.rgb(247, 249, 248);
    static final int INK = Color.rgb(23, 42, 38);
    static final int MUTED = Color.rgb(100, 116, 112);
    static final int GREEN = Color.rgb(16, 122, 101);
    static final int PALE_GREEN = Color.rgb(226, 244, 239);
    static final int SOFT_GREEN = Color.rgb(235, 247, 243);
    static final int BORDER = Color.rgb(222, 230, 227);
    static final int RED = Color.rgb(177, 54, 54);
    static final int SOFT_RED = Color.rgb(252, 235, 235);

    private Ui() {}

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static LinearLayout column(Context context, int left, int top, int right, int bottom) {
        LinearLayout value = new LinearLayout(context);
        value.setOrientation(LinearLayout.VERTICAL);
        value.setPadding(dp(context, left), dp(context, top),
                dp(context, right), dp(context, bottom));
        return value;
    }

    static LinearLayout row(Context context) {
        LinearLayout value = new LinearLayout(context);
        value.setOrientation(LinearLayout.HORIZONTAL);
        value.setGravity(Gravity.CENTER_VERTICAL);
        return value;
    }

    static LinearLayout card(Context context, int padding) {
        LinearLayout value = column(context, padding, padding, padding, padding);
        value.setBackground(rounded(Color.WHITE, 22));
        value.setElevation(dp(context, 2));
        return value;
    }

    static TextView text(Context context, String value, int sp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.create("sans", Typeface.BOLD));
        return view;
    }

    static TextView iconButton(Context context, String value) {
        TextView view = text(context, value, 30, INK, false);
        view.setGravity(Gravity.CENTER);
        view.setBackground(rounded(Color.WHITE, 16));
        view.setElevation(dp(context, 2));
        return view;
    }

    static TextView pill(Context context, String value, int background, int foreground) {
        TextView view = text(context, value, 12, foreground, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(context, 10), dp(context, 6),
                dp(context, 10), dp(context, 6));
        view.setBackground(rounded(background, 30));
        return view;
    }

    static Button primaryButton(Context context, String value) {
        Button button = new Button(context);
        button.setText(value);
        button.setTextSize(16);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setTypeface(Typeface.create("sans", Typeface.BOLD));
        button.setBackground(rounded(GREEN, 18));
        button.setStateListAnimator(null);
        return button;
    }

    static EditText input(Context context, String hint, boolean visiblePassword) {
        EditText value = new EditText(context);
        value.setHint(hint);
        value.setTextSize(15);
        value.setTextColor(INK);
        value.setHintTextColor(Color.rgb(143, 154, 151));
        value.setSingleLine(true);
        value.setPadding(dp(context, 15), 0, dp(context, 15), 0);
        value.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
                (visiblePassword
                        ? android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        : android.text.InputType.TYPE_TEXT_VARIATION_NORMAL));
        value.setBackground(outlined(Color.WHITE, BORDER, 15));
        return value;
    }

    static GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radiusDp * 3f);
        return drawable;
    }

    static GradientDrawable oval(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        return drawable;
    }

    static GradientDrawable outlined(int color, int stroke, int radiusDp) {
        GradientDrawable drawable = rounded(color, radiusDp);
        drawable.setStroke(1, stroke);
        return drawable;
    }

    static LinearLayout.LayoutParams matchWrap(Context context) {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }
}
