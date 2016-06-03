/*
 * Copyright 2013 The Android Open Source Project
 * Copyright 2016 Jake Wharton
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jakewharton.behavior.drawer;

import android.os.Build;
import android.os.SystemClock;
import android.support.design.widget.CoordinatorLayout;
import android.support.v4.view.GravityCompat;
import android.support.v4.view.MotionEventCompat;
import android.support.v4.view.ViewCompat;
import android.support.v4.widget.ViewDragHelper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;

import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;

final class BehaviorDelegate extends ViewDragHelper.Callback {
  private static final int PEEK_DELAY = 160; // ms
  private static final int MIN_FLING_VELOCITY = 400; // dips per second
  private static final int FLAG_IS_OPENED = 0x1;
  private static final int FLAG_IS_OPENING = 0x2;
  private static final int FLAG_IS_CLOSING = 0x4;
  private static final int DEFAULT_SCRIM_COLOR = 0x99000000;

  private final CoordinatorLayout parent;
  private final View child;
  private final boolean isLeft;
  private final ContentScrimDrawer scrimDrawer;
  private final ViewDragHelper dragger;

  private float initialMotionX;
  private float initialMotionY;
  private boolean childrenCanceledTouch;
  private int openState;
  private boolean isPeeking;
  private float onScreen;
  private int drawerState;

  private int scrimColor = DEFAULT_SCRIM_COLOR;

  private final Runnable peekRunnable = new Runnable() {
    @Override public void run() {
      peekDrawer();
    }
  };
  private final Runnable draggerSettle = new Runnable() {
    @Override public void run() {
      if (dragger.continueSettling(true)) {
        ViewCompat.postOnAnimation(parent, this);
      }
    }
  };

  BehaviorDelegate(CoordinatorLayout parent, View child, int gravity) {
    this.parent = parent;
    this.child = child;

    int absGravity =
        GravityCompat.getAbsoluteGravity(gravity, ViewCompat.getLayoutDirection(parent));
    this.isLeft = absGravity == Gravity.LEFT;

    float density = parent.getResources().getDisplayMetrics().density;
    float minVel = MIN_FLING_VELOCITY * density;

    dragger = ViewDragHelper.create(parent, this);
    dragger.setEdgeTrackingEnabled(isLeft ? ViewDragHelper.EDGE_LEFT : ViewDragHelper.EDGE_RIGHT);
    dragger.setMinVelocity(minVel);

    scrimDrawer = Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2
        ? new ContentScrimDrawer.JellyBeanMr2(parent)
        : new ContentScrimDrawer.Base(parent, child);
  }

  private boolean isContentView(View child) {
    return child != this.child;
  }

  private boolean isDrawerView(View child) {
    return child == this.child;
  }

  private void removeCallbacks() {
    parent.removeCallbacks(peekRunnable);
  }

  private void peekDrawer() {
    int peekDistance = dragger.getEdgeSize();
    int childLeft;
    if (isLeft) {
      childLeft = -child.getWidth() + peekDistance;
    } else {
      childLeft = parent.getWidth() - peekDistance;
    }
    // Only peek if it would mean making the drawer more visible and the drawer isn't locked
    if ((isLeft && child.getLeft() < childLeft) //
        || (!isLeft && child.getLeft() > childLeft)) {
      dragger.smoothSlideViewTo(child, childLeft, child.getTop());
      ViewCompat.postOnAnimation(parent, draggerSettle);
      isPeeking = true;

      cancelChildViewTouch();
    }
  }

  private void cancelChildViewTouch() {
    // Cancel child touches
    if (!childrenCanceledTouch) {
      final long now = SystemClock.uptimeMillis();
      final MotionEvent cancelEvent = MotionEvent.obtain(now, now,
          MotionEvent.ACTION_CANCEL, 0.0f, 0.0f, 0);
      final int childCount = parent.getChildCount();
      for (int i = 0; i < childCount; i++) {
        parent.getChildAt(i).dispatchTouchEvent(cancelEvent);
      }
      cancelEvent.recycle();
      childrenCanceledTouch = true;
    }
  }

  boolean onInterceptTouchEvent(MotionEvent ev) {
    boolean interceptForDrag = dragger.shouldInterceptTouchEvent(ev);
    boolean interceptForTap = false;
    switch (MotionEventCompat.getActionMasked(ev)) {
      case MotionEvent.ACTION_DOWN: {
        float x = ev.getX();
        float y = ev.getY();
        initialMotionX = x;
        initialMotionY = y;
        if (onScreen > 0) {
          View child = dragger.findTopChildUnder((int) x, (int) y);
          if (child != null && isContentView(child)) {
            interceptForTap = true;
          }
        }
        childrenCanceledTouch = false;
        break;
      }

      case MotionEvent.ACTION_MOVE: {
        // If we cross the touch slop, don't perform the delayed peek for an edge touch.
        if (dragger.checkTouchSlop(ViewDragHelper.DIRECTION_ALL)) {
          removeCallbacks();
        }
        break;
      }

      case MotionEvent.ACTION_CANCEL:
      case MotionEvent.ACTION_UP: {
        closeDrawers(true);
        childrenCanceledTouch = false;
        break;
      }
    }

    return interceptForDrag || interceptForTap || isPeeking || childrenCanceledTouch;
  }

  boolean onTouchEvent(MotionEvent ev) {
    dragger.processTouchEvent(ev);

    switch (MotionEventCompat.getActionMasked(ev)) {
      case MotionEvent.ACTION_DOWN: {
        initialMotionX = ev.getX();
        initialMotionY = ev.getY();
        childrenCanceledTouch = false;
        break;
      }

      case MotionEvent.ACTION_UP: {
        float x = ev.getX();
        float y = ev.getY();
        boolean peekingOnly = true;
        View touchedView = dragger.findTopChildUnder((int) x, (int) y);
        if (touchedView != null && isContentView(child)) {
          final float dx = x - initialMotionX;
          final float dy = y - initialMotionY;
          final int slop = dragger.getTouchSlop();
          if (dx * dx + dy * dy < slop * slop) {
            // Taps close a dimmed open drawer but only if it isn't locked open.
            if ((openState & FLAG_IS_OPENED) == FLAG_IS_OPENED) { // TODO isDrawerOpen method?
              peekingOnly = false;
            }
          }
        }
        closeDrawers(peekingOnly);
        break;
      }

      case MotionEvent.ACTION_CANCEL: {
        closeDrawers(true);
        childrenCanceledTouch = false;
        break;
      }
    }

    return true;
  }

  private void closeDrawers(boolean peekingOnly) {
    if (peekingOnly && !isPeeking) {
      return;
    }

    boolean needsSettle;
    if (isLeft) {
      needsSettle = dragger.smoothSlideViewTo(child, -child.getWidth(), child.getTop());
    } else {
      needsSettle = dragger.smoothSlideViewTo(child, parent.getWidth(), child.getTop());
    }
    isPeeking = false;

    removeCallbacks();

    if (needsSettle) {
      ViewCompat.postOnAnimation(parent, draggerSettle);
    }
  }

  @Override public void onViewCaptured(View capturedChild, int activePointerId) {
    isPeeking = false;
  }

  @Override public void onViewReleased(View releasedChild, float xvel, float yvel) {
    // Offset is how open the drawer is, therefore left/right values
    // are reversed from one another.
    float offset = onScreen;
    int childWidth = releasedChild.getWidth();

    int left;
    if (isLeft) {
      left = xvel > 0 || xvel == 0 && offset > 0.5f ? 0 : -childWidth;
    } else {
      int width = parent.getWidth();
      left = xvel < 0 || xvel == 0 && offset > 0.5f ? width - childWidth : width;
    }

    dragger.settleCapturedViewAt(left, releasedChild.getTop());
    ViewCompat.postOnAnimation(parent, draggerSettle);
  }

  @Override public void onViewDragStateChanged(int state) {
    updateDrawerState(state, dragger.getCapturedView());
  }

  private void updateDrawerState(int activeState, View activeDrawer) {
    final int state = dragger.getViewDragState();

    if (activeDrawer != null && activeState == ViewDragHelper.STATE_IDLE) {
      if (onScreen == 0) {
        dispatchOnDrawerClosed(activeDrawer);
      } else if (onScreen == 1) {
        dispatchOnDrawerOpened(activeDrawer);
      }
    }

    if (state != drawerState) {
      drawerState = state;
    }
  }

  private void dispatchOnDrawerClosed(View drawerView) {
    if ((openState & FLAG_IS_OPENED) == FLAG_IS_OPENED) {
      openState = 0;

      updateChildrenImportantForAccessibility(drawerView, false);

      // Only send WINDOW_STATE_CHANGE if the host has window focus. This
      // may change if support for multiple foreground windows (e.g. IME)
      // improves.
      if (parent.hasWindowFocus()) {
        final View rootView = parent.getRootView();
        if (rootView != null) {
          rootView.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        }
      }
    }
  }

  private void dispatchOnDrawerOpened(View drawerView) {
    if ((openState & FLAG_IS_OPENED) == 0) {
      openState = FLAG_IS_OPENED;

      updateChildrenImportantForAccessibility(drawerView, true);

      // Only send WINDOW_STATE_CHANGE if the host has window focus.
      if (parent.hasWindowFocus()) {
        parent.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
      }

      drawerView.requestFocus();
    }
  }

  private void updateChildrenImportantForAccessibility(View drawerView, boolean isDrawerOpen) {
    final int childCount = parent.getChildCount();
    for (int i = 0; i < childCount; i++) {
      final View child = parent.getChildAt(i);
      if (!isDrawerOpen && child != this.child
          || isDrawerOpen && child == drawerView) {
        // Drawer is closed and this is a content view or this is an
        // open drawer view, so it should be visible.
        ViewCompat.setImportantForAccessibility(child,
            ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_YES);
      } else {
        ViewCompat.setImportantForAccessibility(child,
            ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
      }
    }
  }

  @Override
  public void onViewPositionChanged(View changedView, int left, int top, int dx, int dy) {
    int childWidth = changedView.getWidth();

    // This reverses the positioning shown in onLayout.
    float offset;
    if (isLeft) {
      int edge = childWidth + left;
      offset = (float) edge / childWidth;
      scrimDrawer.setBounds(edge, 0, parent.getWidth(), parent.getHeight());
    } else {
      int edge = parent.getWidth() - left;
      offset = (float) edge / childWidth;
      scrimDrawer.setBounds(0, 0, left, parent.getHeight());
    }

    int baseAlpha = (scrimColor & 0xff000000) >>> 24;
    int imag = (int) (baseAlpha * offset);
    int color = imag << 24 | (scrimColor & 0xffffff);
    scrimDrawer.setColor(color);

    setDrawerViewOffset(offset);
    boolean gone = offset == 0;
    changedView.setVisibility(gone ? INVISIBLE : VISIBLE);
    scrimDrawer.setVisible(!gone);
    parent.invalidate();
  }

  private void setDrawerViewOffset(float slideOffset) {
    if (slideOffset == onScreen) {
      return;
    }

    onScreen = slideOffset;
  }

  @Override public void onEdgeTouched(int edgeFlags, int pointerId) {
    parent.postDelayed(peekRunnable, PEEK_DELAY);
  }

  @Override public boolean tryCaptureView(View child, int pointerId) {
    return isDrawerView(child);
  }

  @Override public void onEdgeDragStarted(int edgeFlags, int pointerId) {
    if (((edgeFlags & ViewDragHelper.EDGE_LEFT) == ViewDragHelper.EDGE_LEFT && isLeft)
        || ((edgeFlags & ViewDragHelper.EDGE_RIGHT) == ViewDragHelper.EDGE_RIGHT && !isLeft)) {
      dragger.captureChildView(child, pointerId);
    }
  }

  @Override public int getViewHorizontalDragRange(View child) {
    return isDrawerView(child) ? child.getWidth() : 0;
  }

  @Override public int clampViewPositionHorizontal(View child, int left, int dx) {
    if (isLeft) {
      return Math.max(-child.getWidth(), Math.min(left, 0));
    } else {
      int width = parent.getWidth();
      return Math.max(width - child.getWidth(), Math.min(left, width));
    }
  }

  @Override public int clampViewPositionVertical(View child, int top, int dy) {
    return child.getTop();
  }

  boolean onLayoutChild() {
    int width = parent.getMeasuredWidth();
    int height = parent.getMeasuredHeight();
    int childWidth = child.getMeasuredWidth();
    int childHeight = child.getMeasuredHeight();

    int childLeft;
    float newOffset;
    if (isLeft) {
      childLeft = -childWidth + (int) (childWidth * onScreen);
      newOffset = (float) (childWidth + childLeft) / childWidth;
    } else {
      childLeft = width - (int) (childWidth * onScreen);
      newOffset = (float) (width - childLeft) / childWidth;
    }

    boolean changeOffset = newOffset != onScreen;

    CoordinatorLayout.LayoutParams lp = (CoordinatorLayout.LayoutParams) child.getLayoutParams();
    int vgrav = lp.gravity & Gravity.VERTICAL_GRAVITY_MASK;

    switch (vgrav) {
      default:
      case Gravity.TOP: {
        child.layout(childLeft, lp.topMargin, childLeft + childWidth,
            lp.topMargin + childHeight);
        break;
      }

      case Gravity.BOTTOM: {
        child.layout(childLeft,
            height - lp.bottomMargin - child.getMeasuredHeight(),
            childLeft + childWidth,
            height - lp.bottomMargin);
        break;
      }

      case Gravity.CENTER_VERTICAL: {
        int childTop = (height - childHeight) / 2;

        // Offset for margins. If things don't fit right because of
        // bad measurement before, oh well.
        if (childTop < lp.topMargin) {
          childTop = lp.topMargin;
        } else if (childTop + childHeight > height - lp.bottomMargin) {
          childTop = height - lp.bottomMargin - childHeight;
        }
        child.layout(childLeft, childTop, childLeft + childWidth,
            childTop + childHeight);
        break;
      }
    }

    if (changeOffset) {
      setDrawerViewOffset(newOffset);
    }

    int newVisibility = onScreen > 0 ? VISIBLE : INVISIBLE;
    if (child.getVisibility() != newVisibility) {
      child.setVisibility(newVisibility);
    }
    return true;
  }
}
