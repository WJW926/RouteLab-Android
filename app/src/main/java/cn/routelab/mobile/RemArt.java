package cn.routelab.mobile;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

final class RemArt {
    static final int BLUE_EDGE = 0xff9cbbef;
    static final int PINK = 0xffc23869;

    private RemArt() { }

    static TextView wordmark(Context context, String label, int color, int size) {
        TextView title = new TextView(context) {
            private final float unit = getResources().getDisplayMetrics().density;
            private final Drawable flower = context.getDrawable(R.drawable.ic_rem_wordmark_flower);
            private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path underline = new Path();

            @Override protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                if (getLayout() == null || getLineCount() == 0) return;
                float start = getLayout().getLineLeft(0);
                float end = getLayout().getLineRight(0);
                float baseline = getBaseline();
                float length = end - start;

                ink.setColor(0xff6798e4);
                ink.setStyle(Paint.Style.STROKE);
                ink.setStrokeWidth(1.25f * unit);
                ink.setStrokeCap(Paint.Cap.ROUND);
                underline.rewind();
                underline.moveTo(start + length * .34f, baseline + 7 * unit);
                underline.cubicTo(start + length * .58f, baseline + 2 * unit,
                        end - 3 * unit, baseline + 15 * unit, end + 13 * unit, baseline + 1 * unit);
                canvas.drawPath(underline, ink);

                int flowerLeft = Math.round(end + 1 * unit);
                int flowerTop = Math.round(baseline - 24 * unit);
                int flowerSize = Math.round(27 * unit);
                flower.setBounds(flowerLeft, flowerTop, flowerLeft + flowerSize, flowerTop + flowerSize);
                flower.draw(canvas);
            }
        };
        float unit = context.getResources().getDisplayMetrics().density;
        title.setText(label);
        title.setTextSize(size);
        title.setTextColor(color);
        title.setTypeface(AppSettings.FontStyle.SERIF.load(context), Typeface.BOLD);
        title.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        title.setMaxLines(1);
        title.setIncludeFontPadding(false);
        title.setPadding(0, 0, Math.round(29 * unit), Math.round(8 * unit));
        title.setAutoSizeTextTypeUniformWithConfiguration(13, size, 1, TypedValue.COMPLEX_UNIT_SP);
        return title;
    }

    static View routeSurface(Context context) {
        return new View(context) {
            private final float unit = getResources().getDisplayMetrics().density;
            private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint trim = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path outline = new Path();
            private final Path top = new Path();
            private final Path lace = new Path();
            private final Path dots = new Path();

            @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
                if (width <= 0 || height <= 0) return;
                float topRadius = 25 * unit;
                outline.rewind();
                outline.addRoundRect(new RectF(0, unit, width, height),
                        new float[]{topRadius, topRadius, topRadius, topRadius,
                                0, 0, 0, 0}, Path.Direction.CW);
                fill.setShader(new LinearGradient(0, 0, width, height,
                        0xffedf3fe, 0xffe7effd, Shader.TileMode.CLAMP));

                makeTopEdge(top, width, topRadius, unit);
                Path edge = new Path();
                makeTopEdge(edge, width, topRadius, 6 * unit);
                PathMeasure measure = new PathMeasure(edge, false);
                float length = measure.getLength();
                int count = Math.max(1, Math.round(length / (15 * unit)));
                float step = length / count;
                float[] point = new float[2];
                float[] tangent = new float[2];
                lace.rewind();
                dots.rewind();
                measure.getPosTan(0, point, null);
                lace.moveTo(point[0], point[1]);
                for (int i = 0; i < count; i++) {
                    measure.getPosTan((i + .5f) * step, point, tangent);
                    float x = point[0] - 5.5f * unit * tangent[1];
                    float y = point[1] + 5.5f * unit * tangent[0];
                    dots.addCircle(point[0], point[1], .85f * unit, Path.Direction.CW);
                    measure.getPosTan((i + 1) * step, point, null);
                    lace.quadTo(x, y, point[0], point[1]);
                }
            }

            private void makeTopEdge(Path edge, int width, float center, float inset) {
                float radius = center - inset;
                float right = width - center;
                edge.rewind();
                edge.arcTo(inset, inset, center + radius, center + radius, 180, 90, true);
                edge.lineTo(right, inset);
                edge.arcTo(right - radius, inset, width - inset, center + radius, 270, 90, false);
            }

            @Override protected void onDraw(Canvas canvas) {
                canvas.drawPath(outline, fill);
                trim.setStyle(Paint.Style.STROKE);
                int save = canvas.save();
                canvas.clipPath(outline);
                trim.setColor(0xfff7f9ff);
                trim.setStrokeWidth(16 * unit);
                canvas.drawPath(top, trim);
                canvas.restoreToCount(save);
                trim.setColor(BLUE_EDGE);
                trim.setStrokeWidth(unit);
                canvas.drawPath(top, trim);
                canvas.drawPath(lace, trim);
                trim.setStyle(Paint.Style.FILL);
                canvas.drawPath(dots, trim);
            }
        };
    }

    static ImageView illustration(Context context, int image, ImageView.ScaleType scaleType) {
        ImageView artwork = new ImageView(context);
        artwork.setImageResource(image);
        artwork.setScaleType(scaleType);
        artwork.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return artwork;
    }

    static ImageView framedIllustration(Context context, int image, RectF frame, boolean alignEnd) {
        ImageView artwork = illustration(context, image, ImageView.ScaleType.MATRIX);
        artwork.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int width = right - left;
            int height = bottom - top;
            if (width <= 0 || height <= 0) return;
            float imageWidth = artwork.getDrawable().getIntrinsicWidth();
            float imageHeight = artwork.getDrawable().getIntrinsicHeight();
            float frameWidth = imageWidth * frame.width();
            float frameHeight = imageHeight * frame.height();
            float scale = Math.min(width / frameWidth, height / frameHeight);
            float x = (alignEnd ? width - frameWidth * scale : 0) - imageWidth * frame.left * scale;
            float y = height - imageHeight * frame.bottom * scale;
            Matrix matrix = new Matrix();
            matrix.setScale(scale, scale);
            matrix.postTranslate(x, y);
            artwork.setImageMatrix(matrix);
        });
        return artwork;
    }

    static ImageView avatar(Context context) {
        ImageView avatar = new ImageView(context);
        avatar.setImageResource(R.drawable.rem_avatar);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0xffeaf0ff);
        avatar.setBackground(circle);
        avatar.setClipToOutline(true);
        avatar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return avatar;
    }

    static View lace(Context context, boolean bow) {
        return new View(context) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path ribbon = new Path();

            @Override protected void onDraw(Canvas canvas) {
                float unit = getResources().getDisplayMetrics().density;
                float step = 16 * unit;
                float middle = getHeight() / 2f;
                paint.setColor(BLUE_EDGE);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(unit);
                for (float left = 0; left < getWidth(); left += step) {
                    canvas.drawArc(left, middle - 4 * unit, left + step, middle + 4 * unit,
                            0, 180, false, paint);
                }
                paint.setStyle(Paint.Style.FILL);
                for (float x = step / 2; x < getWidth(); x += step) {
                    canvas.drawCircle(x, middle + unit, unit, paint);
                }
                if (bow) {
                    float center = getWidth() / 2f;
                    paint.setColor(0xffe79bbc);
                    ribbon.rewind();
                    ribbon.moveTo(center, middle);
                    ribbon.cubicTo(center - 12 * unit, middle - 11 * unit,
                            center - 16 * unit, middle + 4 * unit, center, middle);
                    ribbon.cubicTo(center + 16 * unit, middle + 4 * unit,
                            center + 12 * unit, middle - 11 * unit, center, middle);
                    ribbon.close();
                    canvas.drawPath(ribbon, paint);
                    canvas.drawCircle(center, middle, 2 * unit, paint);
                }
            }
        };
    }
}
