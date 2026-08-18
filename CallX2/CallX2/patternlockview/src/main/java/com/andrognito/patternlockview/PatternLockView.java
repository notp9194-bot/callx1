package com.andrognito.patternlockview;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import com.andrognito.patternlockview.listener.PatternLockViewListener;
import com.andrognito.patternlockview.utils.PatternLockUtils;

import java.util.ArrayList;
import java.util.List;

public class PatternLockView extends View {

    public static final int DEFAULT_PATTERN_DOT_COUNT = 3;

    public enum PatternViewMode {
        CORRECT, AUTO_DRAW, WRONG
    }

    public static class Dot {
        private final int row;
        private final int column;
        private static Dot[][] sDots;

        private Dot(int row, int column) {
            this.row = row;
            this.column = column;
        }

        public static synchronized void init(int patternSize) {
            sDots = new Dot[patternSize][patternSize];
            for (int i = 0; i < patternSize; i++)
                for (int j = 0; j < patternSize; j++)
                    sDots[i][j] = new Dot(i, j);
        }

        public static Dot of(int row, int column) {
            return sDots[row][column];
        }

        public int getRow() { return row; }
        public int getColumn() { return column; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Dot)) return false;
            Dot dot = (Dot) o;
            return row == dot.row && column == dot.column;
        }

        @Override
        public int hashCode() { return row * 31 + column; }
    }

    private int mPatternSize = DEFAULT_PATTERN_DOT_COUNT;
    private Dot[][] mDots;
    private boolean[][] mPatternDrawLookup;

    private float mInProgressX = -1;
    private float mInProgressY = -1;

    private final List<Dot> mPattern = new ArrayList<>();
    private final List<PatternLockViewListener> mListeners = new ArrayList<>();

    private Paint mDotPaint;
    private Paint mPathPaint;

    private int mDotColor = Color.WHITE;
    private int mPathColor = Color.WHITE;
    private int mCorrectColor = 0xFF4CAF50;
    private int mWrongColor = 0xFFF44336;

    private float mDotRadius;
    private float mDotRadiusScale = 1.0f;
    private float mPathWidth;

    private boolean mInputEnabled = true;
    private boolean mInStealthMode = false;
    private PatternViewMode mPatternViewMode = PatternViewMode.CORRECT;

    private final Path mCurrentPath = new Path();

    public PatternLockView(Context context) { this(context, null); }
    public PatternLockView(Context context, AttributeSet attrs) { this(context, attrs, 0); }

    public PatternLockView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.PatternLockView);
            mPatternSize = a.getInt(R.styleable.PatternLockView_dotCount, DEFAULT_PATTERN_DOT_COUNT);
            mDotColor = a.getColor(R.styleable.PatternLockView_normalStateColor, Color.WHITE);
            mPathColor = a.getColor(R.styleable.PatternLockView_normalStateColor, Color.WHITE);
            mCorrectColor = a.getColor(R.styleable.PatternLockView_correctStateColor, 0xFF4CAF50);
            mWrongColor = a.getColor(R.styleable.PatternLockView_wrongStateColor, 0xFFF44336);
            a.recycle();
        }
        init();
    }

    private void init() {
        mDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mDotPaint.setStyle(Paint.Style.FILL);

        mPathPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mPathPaint.setStyle(Paint.Style.STROKE);
        mPathPaint.setStrokeCap(Paint.Cap.ROUND);
        mPathPaint.setStrokeJoin(Paint.Join.ROUND);

        setPatternSize(mPatternSize);
    }

    public void setPatternSize(int size) {
        mPatternSize = size;
        Dot.init(size);
        mDots = new Dot[size][size];
        mPatternDrawLookup = new boolean[size][size];
        for (int i = 0; i < size; i++)
            for (int j = 0; j < size; j++)
                mDots[i][j] = Dot.of(i, j);
        resetPattern();
    }

    public void addPatternLockListener(PatternLockViewListener listener) {
        mListeners.add(listener);
    }

    public void removePatternLockListener(PatternLockViewListener listener) {
        mListeners.remove(listener);
    }

    public void setViewMode(PatternViewMode mode) {
        mPatternViewMode = mode;
        invalidate();
    }

    public void setInputEnabled(boolean enabled) { mInputEnabled = enabled; }
    public void setInStealthMode(boolean inStealthMode) { mInStealthMode = inStealthMode; }
    public List<Dot> getPattern() { return mPattern; }
    public int getPatternSize() { return mPatternSize; }

    public void clearPattern() {
        resetPattern();
        invalidate();
    }

    private void resetPattern() {
        mPattern.clear();
        clearPatternDrawLookup();
        mPatternViewMode = PatternViewMode.CORRECT;
    }

    private void clearPatternDrawLookup() {
        for (int i = 0; i < mPatternSize; i++)
            for (int j = 0; j < mPatternSize; j++)
                mPatternDrawLookup[i][j] = false;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        mDotRadius = Math.min(w, h) / (mPatternSize * 4.0f);
        mPathWidth = mDotRadius / 2f;
        mPathPaint.setStrokeWidth(mPathWidth);
    }

    private float getCenterXForColumn(int column) {
        return getPaddingLeft() + column * (getWidth() - getPaddingLeft() - getPaddingRight()) / (float) mPatternSize
                + (getWidth() - getPaddingLeft() - getPaddingRight()) / (float) (mPatternSize * 2);
    }

    private float getCenterYForRow(int row) {
        return getPaddingTop() + row * (getHeight() - getPaddingTop() - getPaddingBottom()) / (float) mPatternSize
                + (getHeight() - getPaddingTop() - getPaddingBottom()) / (float) (mPatternSize * 2);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int activeColor;
        if (mPatternViewMode == PatternViewMode.WRONG) activeColor = mWrongColor;
        else if (mPatternViewMode == PatternViewMode.CORRECT) activeColor = mCorrectColor;
        else activeColor = mDotColor;

        // Draw dots
        for (int i = 0; i < mPatternSize; i++) {
            for (int j = 0; j < mPatternSize; j++) {
                float cx = getCenterXForColumn(j);
                float cy = getCenterYForRow(i);
                boolean activated = mPatternDrawLookup[i][j];
                mDotPaint.setColor(activated ? activeColor : mDotColor);
                mDotPaint.setAlpha(activated ? 255 : 80);
                float radius = mDotRadius * (activated ? 1.3f : 1.0f);
                canvas.drawCircle(cx, cy, radius, mDotPaint);
            }
        }

        if (mInStealthMode) return;

        // Draw path between connected dots
        if (!mPattern.isEmpty()) {
            mPathPaint.setColor(activeColor);
            mCurrentPath.reset();
            boolean first = true;
            for (Dot dot : mPattern) {
                float cx = getCenterXForColumn(dot.getColumn());
                float cy = getCenterYForRow(dot.getRow());
                if (first) { mCurrentPath.moveTo(cx, cy); first = false; }
                else mCurrentPath.lineTo(cx, cy);
            }
            // Line to finger
            if (mInProgressX >= 0) mCurrentPath.lineTo(mInProgressX, mInProgressY);
            canvas.drawPath(mCurrentPath, mPathPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!mInputEnabled) return false;
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                handleActionDown(event);
                return true;
            case MotionEvent.ACTION_UP:
                handleActionUp();
                return true;
            case MotionEvent.ACTION_MOVE:
                handleActionMove(event);
                return true;
            case MotionEvent.ACTION_CANCEL:
                resetPattern();
                notifyPatternCleared();
                invalidate();
                return true;
        }
        return false;
    }

    private void handleActionDown(MotionEvent event) {
        resetPattern();
        float x = event.getX(), y = event.getY();
        Dot hit = detectAndAddHit(x, y);
        if (hit != null) {
            notifyPatternStarted();
            mInProgressX = x;
            mInProgressY = y;
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        }
        invalidate();
    }

    private void handleActionMove(MotionEvent event) {
        float x = event.getX(), y = event.getY();
        Dot dot = detectAndAddHit(x, y);
        if (dot != null) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        mInProgressX = x;
        mInProgressY = y;
        notifyPatternProgress();
        invalidate();
    }

    private void handleActionUp() {
        if (!mPattern.isEmpty()) {
            mInProgressX = -1;
            mInProgressY = -1;
            notifyPatternComplete();
        }
        invalidate();
    }

    private Dot detectAndAddHit(float x, float y) {
        for (int i = 0; i < mPatternSize; i++) {
            for (int j = 0; j < mPatternSize; j++) {
                if (mPatternDrawLookup[i][j]) continue;
                float cx = getCenterXForColumn(j);
                float cy = getCenterYForRow(i);
                float dist = (float) Math.sqrt(Math.pow(x - cx, 2) + Math.pow(y - cy, 2));
                if (dist < mDotRadius * 2) {
                    mPatternDrawLookup[i][j] = true;
                    mPattern.add(mDots[i][j]);
                    return mDots[i][j];
                }
            }
        }
        return null;
    }

    private void notifyPatternStarted() {
        for (PatternLockViewListener l : mListeners) l.onStarted();
    }

    private void notifyPatternProgress() {
        for (PatternLockViewListener l : mListeners) l.onProgress(new ArrayList<>(mPattern));
    }

    private void notifyPatternComplete() {
        for (PatternLockViewListener l : mListeners) l.onComplete(new ArrayList<>(mPattern));
    }

    private void notifyPatternCleared() {
        for (PatternLockViewListener l : mListeners) l.onCleared();
    }
}
