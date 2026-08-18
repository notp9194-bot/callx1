package com.callx.app.analytics;

import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.View;
import androidx.annotation.Nullable;

public class BestTimeToPostView extends View {
    private float[][] engagementGrid = new float[7][24];
    private String[] days = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
    private Paint gridPaint, textPaint, starPaint;
    private int bestDay = -1, bestHour = -1;

    public BestTimeToPostView(Context context) { super(context); init(); }
    public BestTimeToPostView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(0xFF888888);
        textPaint.setTextSize(24f);
        starPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        starPaint.setColor(Color.YELLOW);
        starPaint.setStyle(Paint.Style.FILL);
    }

    public void setData(float[][] grid) {
        this.engagementGrid = grid;
        findBestTime();
        invalidate();
    }

    private void findBestTime() {
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
    }

    public String getRecommendationText() {
        if (bestDay == -1) return "Not enough data yet";
        return "Post on " + days[bestDay] + " at " + bestHour + ":00 for best reach";
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float labelWidth = 80f;
        float cellW = (getWidth() - labelWidth) / 24f;
        float cellH = getHeight() / 7f;

        for (int d = 0; d < 7; d++) {
            canvas.drawText(days[d], 10, d * cellH + cellH / 1.5f, textPaint);
            for (int h = 0; h < 24; h++) {
                float score = engagementGrid[d][h];
                gridPaint.setColor(0xFF00FF00);
                gridPaint.setAlpha((int) (Math.min(1.0f, score) * 255));
                
                RectF rect = new RectF(labelWidth + h * cellW + 2, d * cellH + 2, 
                                    labelWidth + (h + 1) * cellW - 2, (d + 1) * cellH - 2);
                canvas.drawRoundRect(rect, 4, 4, gridPaint);

                if (d == bestDay && h == bestHour) {
                    canvas.drawCircle(rect.centerX(), rect.centerY(), cellW / 4, starPaint);
                }
            }
        }
    }
}
