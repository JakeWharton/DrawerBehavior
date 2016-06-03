/*
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

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.support.design.widget.CoordinatorLayout;
import android.support.design.widget.CoordinatorLayout.LayoutParams;
import android.view.View;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

interface ContentScrimDrawer {
  void setColor(int color);
  void setBounds(int left, int top, int right, int bottom);
  void setVisible(boolean gone);

  @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
  final class JellyBeanMr2 extends ColorDrawable implements ContentScrimDrawer {
    private final View parent;
    private boolean visible;

    JellyBeanMr2(CoordinatorLayout parent) {
      this.parent = parent;
    }

    @Override public void setVisible(boolean visible) {
      if (this.visible != visible) {
        if (visible) {
          parent.getOverlay().add(this);
        } else {
          parent.getOverlay().remove(this);
        }
        this.visible = visible;
      }
    }
  }

  @SuppressLint("ViewConstructor") // Created only programmatically.
  final class Base extends View implements ContentScrimDrawer {
    private final Paint colorPaint = new Paint();
    private int left;
    private int top;
    private int right;
    private int bottom;
    private boolean visible;

    Base(CoordinatorLayout parent, View child) {
      super(parent.getContext());
      // Draw at the same level of the child.
      parent.addView(this, parent.indexOfChild(child),
          new LayoutParams(MATCH_PARENT, MATCH_PARENT));
    }

    @Override protected void onDraw(Canvas canvas) {
      canvas.drawRect(left, top, right, bottom, colorPaint);
    }

    @Override public void setColor(int color) {
      colorPaint.setColor(color);
      invalidate(left, top, right, bottom);
    }

    @Override public void setBounds(int left, int top, int right, int bottom) {
      this.left = left;
      this.top = top;
      this.right = right;
      this.bottom = bottom;
      invalidate(left, top, right, bottom);
    }

    @Override public void setVisible(boolean visible) {
      if (this.visible != visible) {
        setVisibility(visible ? VISIBLE : INVISIBLE);
        this.visible = visible;
      }
    }
  }
}
