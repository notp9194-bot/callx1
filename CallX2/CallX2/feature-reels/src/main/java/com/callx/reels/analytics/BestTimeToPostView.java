package com.callx.reels.analytics;

import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.View;
import androidx.annotation.Nullable;

public class BestTimeToPostView extends View {
    private float[][] engagementGrid = new float[7][24];
    private final String[] days = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
    private Paint gridPaint, textPaint, starPaint;
    private int bestDay = -1, bestHour = -1;

    public BestTimeToPostView(Context context) { super(context); init(); }
    public BestTimeToPostView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(0xFFAAAAAA);
        textPaint.setTextSize(24f);
        starPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        starPaint.setColor(Color.YELLOW);
        starPaint.setStyle(Paint.Style.FILL);
    }

    public void setData(float[][] grid) {
        this.engagementGrid = grid;
        float max = -1;
        for (int d = 0; d < 7; d++) {
            for (int h = 0; h < 24; h++) {
                if (engagementGrid[d][h] > max) {
                    max = engagementGrid[d][h];
                    bestDay = d;
                    bestHour = h;
                }
            }
        }
        invalidate();
    }

    public String getRecommendationText() {
        if (bestDay == -1) return "Collecting more data...";
        return "Post on " + days[bestDay] + " at " + (bestHour % 12 == 0 ? 12 : bestHour % 12) + (bestHour < 12 ? " AM" : " PM") + " for best reach";
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float margin = 60f;
        float cellW = (getWidth() - margin) / 24f;
        float cellH = getHeight() / 7f;

        for (int d = 0; d < 7; d++) {
            canvas.drawText(days[d].substring(0, 1), 10, d * cellH + cellH / 1.5f, textPaint);
            for (int h = 0; h < 24; h++) {
                float score = engagementGrid[d][h];
                gridPaint.setColor(0xFF2ECC71);
                gridPaint.setAlpha((int) (Math.min(1.0f, score) * 255));
                
                RectF rect = new RectF(margin + h * cellW + 1, d * cellH + 1, 
                                    margin + (h + 1) * cellW - 1, (d + 1) * cellH - 1);
                canvas.drawRect(rect, gridPaint);

                if (d == bestDay && h == bestHour) {
                    Path star = new Path();
                    float cx = rect.centerX(), cy = rect.centerY(), r = cellW / 3;
                    for (int i = 0; i < 5; i++) {
                        double angle = Math.toRadians(-90 + i * 144);
                        if (i == 0) star.moveTo(cx + (float)Math.cos(angle)*r, cy + (float)Math.sin(angle)*r);
                        else star.lineTo(cx + (float)Math.cos(angle)*r, cy + (float)Math.sin(angle)*r);
                    }
                    star.close();
                    canvas.drawPath(star, starPaint);
                }
            }
        }
    }
}
