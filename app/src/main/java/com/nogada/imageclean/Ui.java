package com.nogada.imageclean;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;

final class Ui {
    static Drawable background(Context context, int fill, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(context.getColor(fill));
        shape.setCornerRadius(radius * context.getResources().getDisplayMetrics().density);
        return shape;
    }

    static Drawable ripple(Context context, int fill, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(context.getColor(R.color.line)), background(context, fill, radius), null);
    }

    static final class Icon extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String name;

        Icon(String name, int color) {
            this.name = name;
            paint.setColor(color);
            paint.setStrokeWidth(1.8f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStyle(Paint.Style.STROKE);
        }

        @Override public void draw(Canvas canvas) {
            canvas.save();
            canvas.translate(getBounds().left, getBounds().top);
            canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
            if ("plus".equals(name)) {
                canvas.drawLine(5, 12, 19, 12, paint);
                canvas.drawLine(12, 5, 12, 19, paint);
            } else if ("more".equals(name)) {
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(12, 5, 1.6f, paint);
                canvas.drawCircle(12, 12, 1.6f, paint);
                canvas.drawCircle(12, 19, 1.6f, paint);
                paint.setStyle(Paint.Style.STROKE);
            } else if ("settings".equals(name)) {
                canvas.drawLine(4, 7, 20, 7, paint);
                canvas.drawLine(4, 17, 20, 17, paint);
                canvas.drawCircle(9, 7, 3, paint);
                canvas.drawCircle(15, 17, 3, paint);
            } else if ("chevron".equals(name)) {
                Path path = new Path();
                path.moveTo(9, 6);
                path.lineTo(15, 12);
                path.lineTo(9, 18);
                canvas.drawPath(path, paint);
            } else {
                canvas.drawRoundRect(3, 3, 21, 21, 4, 4, paint);
                canvas.drawCircle(9, 9, 1.8f, paint);
                Path path = new Path();
                path.moveTo(4, 18);
                path.lineTo(10, 12);
                path.lineTo(14, 16);
                path.lineTo(17, 13);
                path.lineTo(21, 17);
                canvas.drawPath(path, paint);
            }
            canvas.restore();
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
