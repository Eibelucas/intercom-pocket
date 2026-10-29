package de.local.intercompocket;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.function.DoubleSupplier;

/** Voice level bars drawn with theme colors (primary = active, surface variant = idle). */
public final class LevelMeter extends View {
    private static final int BARS = 12;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density = getResources().getDisplayMetrics().density;
    private DoubleSupplier level = () -> 0;
    private int activeColor;
    private int idleColor;

    public LevelMeter(Context context, AttributeSet attrs) {
        super(context, attrs);
        activeColor = Ui.primary(context);
        idleColor = Ui.color(context, com.google.android.material.R.attr.colorSurfaceVariant);
        // Sibling cards use the same container color; keep idle bars visible on it.
        int container = Ui.color(context, com.google.android.material.R.attr.colorOutlineVariant);
        if (container != 0) idleColor = container;
    }

    void setLevelSource(DoubleSupplier source) {
        this.level = source;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float value = (float) Math.max(0, Math.min(1, level.getAsDouble()));
        float unit = 12 * density;
        float space = 7 * density;
        float total = BARS * unit + (BARS - 1) * space;
        float left = (getWidth() - total) / 2f;
        paint.setColor(value > 0.02f ? activeColor : idleColor);
        for (int i = 0; i < BARS; i++) {
            float wave = (float) (0.4 + 0.6 * Math.sin((i + 1) * 1.9));
            float height = 12 * density + value * 70 * density * wave;
            float x = left + i * (unit + space);
            canvas.drawRoundRect(
                    x,
                    (getHeight() - height) / 2f,
                    x + unit,
                    (getHeight() + height) / 2f,
                    6 * density,
                    6 * density,
                    paint);
        }
    }
}
