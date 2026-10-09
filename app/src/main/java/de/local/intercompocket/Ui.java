package de.local.intercompocket;

import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.AttrRes;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;

/** Small shared helpers for the Material 3 screens. */
final class Ui {
    private Ui() {}

    /**
     * Toolbar with a back arrow. Arrow and title are children of the same toolbar row, so they are
     * vertically aligned.
     */
    static MaterialToolbar setupSubScreen(AppCompatActivity activity, @StringRes int title) {
        MaterialToolbar toolbar = activity.findViewById(R.id.toolbar);
        toolbar.setTitle(title);
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back);
        toolbar.setNavigationContentDescription(R.string.nav_back);
        toolbar.setNavigationOnClickListener(
                v -> activity.getOnBackPressedDispatcher().onBackPressed());
        return toolbar;
    }

    /**
     * Keeps scrolling content clear of the navigation bar (edge-to-edge). The app bar handles the
     * status bar itself.
     */
    static void applyBottomInsets(View view) {
        final int left = view.getPaddingLeft();
        final int top = view.getPaddingTop();
        final int right = view.getPaddingRight();
        final int bottom = view.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(
                view,
                (v, insets) -> {
                    Insets bars =
                            insets.getInsets(
                                    WindowInsetsCompat.Type.systemBars()
                                            | WindowInsetsCompat.Type.displayCutout());
                    v.setPadding(left + bars.left, top, right + bars.right, bottom + bars.bottom);
                    return insets;
                });
    }

    /** Lifts a floating button above the navigation bar / gesture area (edge-to-edge). */
    static void applyFabInsets(View fab) {
        final android.view.ViewGroup.MarginLayoutParams lp =
                (android.view.ViewGroup.MarginLayoutParams) fab.getLayoutParams();
        final int right = lp.rightMargin;
        final int bottom = lp.bottomMargin;
        ViewCompat.setOnApplyWindowInsetsListener(
                fab,
                (v, insets) -> {
                    Insets bars =
                            insets.getInsets(
                                    WindowInsetsCompat.Type.systemBars()
                                            | WindowInsetsCompat.Type.displayCutout());
                    android.view.ViewGroup.MarginLayoutParams params =
                            (android.view.ViewGroup.MarginLayoutParams) v.getLayoutParams();
                    params.rightMargin = right + bars.right;
                    params.bottomMargin = bottom + bars.bottom;
                    v.setLayoutParams(params);
                    return insets;
                });
    }

    /** Resolves a color attribute from the current (dynamic) theme. */
    static int color(Context context, @AttrRes int attr) {
        TypedValue value = new TypedValue();
        if (!context.getTheme().resolveAttribute(attr, value, true)) return Color.TRANSPARENT;
        return value.resourceId != 0 ? context.getColor(value.resourceId) : value.data;
    }

    static int primary(Context context) {
        return color(context, androidx.appcompat.R.attr.colorPrimary);
    }

    static int onSurfaceVariant(Context context) {
        return color(context, com.google.android.material.R.attr.colorOnSurfaceVariant);
    }

    static void toast(Context context, @StringRes int message) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
    }

    static void toast(Context context, String message) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
    }

    /** Fills an included item_setting_row. An empty subtitle hides the second line. */
    static void bindRow(View row, @StringRes int title, String subtitle, Runnable action) {
        ((TextView) row.findViewById(R.id.row_title)).setText(title);
        TextView sub = row.findViewById(R.id.row_subtitle);
        sub.setText(subtitle);
        sub.setVisibility(subtitle == null || subtitle.isEmpty() ? View.GONE : View.VISIBLE);
        row.setOnClickListener(v -> action.run());
    }
}
