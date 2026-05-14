package com.andrognito.patternlockview.listener;

import com.andrognito.patternlockview.PatternLockView;
import java.util.List;

public interface PatternLockViewListener {
    void onStarted();
    void onProgress(List<PatternLockView.Dot> progressPattern);
    void onComplete(List<PatternLockView.Dot> pattern);
    void onCleared();
}
