package com.callx.app.analytics;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ReelTimeSeriesChartView extends View {
    private List<Float> dataPoints = new ArrayList<>();
    private List<String> labels = new ArrayList<>();
    private Paint linePaint, fillPaint, pointPaint, axisPaint, textPaint;
    private Path linePath, fillPath;
    private float animationProgress = 0f;
    private int lineColor = 0xFFFF3B5C;

    public ReelTimeSeriesChartView(Context context) { super(context); init(); }
    public ReelTimeSeriesChartView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(5f);
        linePaint.setStrokeCap(Paint.Cap.ROUND);

        fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setStyle(Paint.Style.FILL);

        pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pointPaint.setStyle(Paint.Style.FILL);

        axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        axisPaint.setColor(0xFF888888);
        axisPaint.setStrokeWidth(2f);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(0xFF888888);
        textPaint.setTextSize(24f);

        linePath = new Path();
        fillPath = new Path();
    }

    public void setData(List<Float> values, List<String> labels) {
        this.dataPoints = values;
        this.labels = labels;
        startAnimation();
    }

    public void setLineColor(int color) {
        this.lineColor = color;
        invalidate();
    }

    private void startAnimation() {
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(1000);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(animation -> {
            animationProgress = (float) animation.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (dataPoints == null || dataPoints.isEmpty()) return;

        float width = getWidth();
        float height = getHeight();
        float padding = 60f;
        float chartWidth = width - 2 * padding;
        float chartHeight = height - 2 * padding;

        float maxVal = Collections.max(dataPoints);
        float minVal = Collections.min(dataPoints);
        if (maxVal == minVal) maxVal = minVal + 1;

        linePaint.setColor(lineColor);
        fillPaint.setColor(lineColor);
        fillPaint.setAlpha(40);
        pointPaint.setColor(lineColor);

        linePath.reset();
        fillPath.reset();

        float stepX = chartWidth / (dataPoints.size() - 1);
        for (int i = 0; i < dataPoints.size(); i++) {
            float x = padding + i * stepX;
            float y = padding + chartHeight - (chartHeight * (dataPoints.get(i) / maxVal) * animationProgress);

            if (i == 0) {
                linePath.moveTo(x, y);
                fillPath.moveTo(x, padding + chartHeight);
                fillPath.lineTo(x, y);
            } else {
                float prevX = padding + (i - 1) * stepX;
                float prevY = padding + chartHeight - (chartHeight * (dataPoints.get(i - 1) / maxVal) * animationProgress);
                float cx = (prevX + x) / 2;
                linePath.cubicTo(cx, prevY, cx, y, x, y);
                fillPath.cubicTo(cx, prevY, cx, y, x, y);
            }

            if (i == dataPoints.size() - 1) {
                fillPath.lineTo(x, padding + chartHeight);
                fillPath.close();
            }
        }

        canvas.drawPath(fillPath, fillPaint);
        canvas.drawPath(linePath, linePaint);

        // Draw min/max/current labels
        textPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("Max: " + maxVal, padding, padding / 2, textPaint);
        textPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("Current: " + dataPoints.get(dataPoints.size()-1), width - padding, padding / 2, textPaint);

        // Draw points
        for (int i = 0; i < dataPoints.size(); i++) {
            float x = padding + i * stepX;
            float y = padding + chartHeight - (chartHeight * (dataPoints.get(i) / maxVal) * animationProgress);
            canvas.drawCircle(x, y, 8f, pointPaint);
        }
    }
}
