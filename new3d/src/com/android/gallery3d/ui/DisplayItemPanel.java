package com.android.gallery3d.ui;

import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;

import java.util.ArrayList;

public class DisplayItemPanel extends GLView {

    private static final int TRANSITION_DURATION = 1000;

    // The state of the display item.
    // The state of those items to be removed
    private static final int STATE_REMOVED = 1;

    // The state of the items that is just added to this panel
    private static final int STATE_NEWBIE = 2;

    // The state of the items that changed position
    private static final int STATE_MOVED = 3;

    private static final long START_ANIMATION = -1;
    private static final long NO_ANIMATION = -2;

    private ArrayList<DisplayItem> mItems = new ArrayList<DisplayItem>();

    private long mAnimationStartTime = NO_ANIMATION;
    private boolean mPrepareTransition = false;
    private final Interpolator mInterpolator = new DecelerateInterpolator(4);

    /**
     * Puts the item center at the given position and angle.
     */
    public void putDisplayItem(
            DisplayItem item, float x, float y, float theata) {
        if (item.mPanel != this && item.mPanel != null) {
            throw new IllegalArgumentException();
        }
        item.mTarget.set(x, y, theata);

        if (item.mPanel != this) {
            item.mPanel = this;
            item.mState = STATE_NEWBIE;
            mItems.add(item);
            item.mCurrent.set(x, y, theata);
        } else {
            item.mState = STATE_MOVED;
            if (mPrepareTransition) {
                item.mSource.set(item.mCurrent);
            } else {
                item.mCurrent.set(x, y, theata);
            }
        }
        if (!mPrepareTransition) invalidate();
    }

    public void removeDisplayItem(DisplayItem item) {
        if (item.mPanel != this) throw new IllegalArgumentException();
        mItems.remove(item);
        item.mPanel = null;
    }

    public void prepareTransition() {
        mPrepareTransition = true;
        for (DisplayItem item : mItems) {
            item.mState = STATE_REMOVED;
        }
    }

    public void startTransition() {
        mPrepareTransition = false;
        mAnimationStartTime = START_ANIMATION;
        invalidate();
    }

    private void onTransitionComplete() {
        ArrayList<DisplayItem> list = new ArrayList<DisplayItem>();
        for (DisplayItem item: mItems) {
            if (item.mState == STATE_REMOVED) {
                item.mPanel = null;
            } else {
                list.add(item);
            }
        }
        mItems = list;
    }

    @Override
    protected void render(GLCanvas canvas) {
        canvas.translate(-mScrollX, 0, 0);
        if (mAnimationStartTime == NO_ANIMATION) {
            for (DisplayItem item: mItems) {
                renderItem(canvas, item);
            }
        } else {
            long now = canvas.currentAnimationTimeMillis();
            if (mAnimationStartTime == START_ANIMATION) {
                mAnimationStartTime = now;
            }
            float timeRatio = Util.clamp((float)
                    (now - mAnimationStartTime) / TRANSITION_DURATION,  0, 1);
            float interpolate = mInterpolator.getInterpolation(timeRatio);
            for (DisplayItem item: mItems) {
                renderItem(canvas, item, interpolate);
            }
            if (timeRatio == 1.0f) {
                onTransitionComplete();
                mAnimationStartTime = NO_ANIMATION;
            } else {
                invalidate();
            }
        }
        canvas.translate(mScrollX, 0, 0);
    }

    private void renderItem(GLCanvas canvas, DisplayItem item) {
        canvas.save(GLCanvas.SAVE_FLAG_ALPHA | GLCanvas.SAVE_FLAG_MATRIX);
        item.mCurrent.apply(canvas);
        item.render(canvas);
        canvas.restore();
    }

    private void renderItem(
            GLCanvas canvas, DisplayItem item, float interpolate) {
        canvas.save(GLCanvas.SAVE_FLAG_ALPHA | GLCanvas.SAVE_FLAG_MATRIX);
        switch (item.mState) {
            case STATE_MOVED:
                item.updateCurrentPosition(interpolate);
                break;
            case STATE_NEWBIE:
                canvas.multiplyAlpha(interpolate);
                break;
            case STATE_REMOVED:
                canvas.multiplyAlpha(1.0f - interpolate);
                break;
        }
        item.mCurrent.apply(canvas);
        item.render(canvas);
        canvas.restore();
    }
}