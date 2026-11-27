/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.launcher3.shortcuts;

import android.content.Context;
import android.content.pm.ShortcutInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.core.graphics.ColorUtils;

import com.android.launcher3.BubbleTextView;
import com.android.launcher3.Launcher;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.model.data.WorkspaceItemInfo;
import com.android.launcher3.popup.PopupContainerWithArrow;
import com.android.launcher3.views.BubbleTextHolder;

import app.lawnchair.theme.color.tokens.ColorTokens;
import app.lawnchair.util.DrawableUtilsKt;

/**
 * A {@link android.widget.FrameLayout} that contains an icon and a {@link BubbleTextView} for text.
 * This lets us animate the child BubbleTextView's background (transparent ripple) separately from
 * the {@link DeepShortcutView} background color.
 */
public class DeepShortcutView extends FrameLayout implements BubbleTextHolder {

    private static final Point sTempPoint = new Point();

    private final Drawable mTransparentDrawable = new ColorDrawable(Color.TRANSPARENT);

    private BubbleTextView mBubbleText;
    private View mIconView;

    private WorkspaceItemInfo mInfo;
    private ShortcutInfo mDetail;

    public DeepShortcutView(Context context) {
        this(context, null, 0);
    }

    public DeepShortcutView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DeepShortcutView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mBubbleText = findViewById(R.id.bubble_text);
        mBubbleText.setHideBadge(true);
        mIconView = findViewById(R.id.icon);
        tryUpdateTextBackground();
    }

    @Override
    public void setBackground(Drawable background) {
        super.setBackground(background);
        tryUpdateTextBackground();
    }

    @Override
    public void setBackgroundResource(int resid) {
        super.setBackgroundResource(resid);
        tryUpdateTextBackground();
    }

    /**
     * Updates the text background to match the shape of this background (when applicable).
     */
    private void tryUpdateTextBackground() {
        if (!(getBackground() instanceof GradientDrawable) || mBubbleText == null) {
            return;
        }
        GradientDrawable background = (GradientDrawable) getBackground();

        int color = ColorTokens.PopupColorTertiary.resolveColor(getContext());
        GradientDrawable backgroundMask = new GradientDrawable();
        backgroundMask.setColor(color);
        backgroundMask.setShape(GradientDrawable.RECTANGLE);
        if (DrawableUtilsKt.getCornerRadiiCompat(background) != null) {
            backgroundMask.setCornerRadii(background.getCornerRadii());
        } else {
            backgroundMask.setCornerRadius(background.getCornerRadius());
        }

        RippleDrawable drawable = new RippleDrawable(ColorStateList.valueOf(color),
                mTransparentDrawable, backgroundMask);
        mBubbleText.setBackground(drawable);
    }

    @Override
    public BubbleTextView getBubbleText() {
        return mBubbleText;
    }

    public void setWillDrawIcon(boolean willDraw) {
        mIconView.setVisibility(willDraw ? View.VISIBLE : View.INVISIBLE);
    }

    public boolean willDrawIcon() {
        return mIconView.getVisibility() == View.VISIBLE;
    }

    /**
     * Returns the position of the center of the icon relative to the container.
     */
    public Point getIconCenter() {
        sTempPoint.y = sTempPoint.x = getMeasuredHeight() / 2;
        if (Utilities.isRtl(getResources())) {
            sTempPoint.x = getMeasuredWidth() - sTempPoint.x;
        }
        return sTempPoint;
    }

    /** package private **/
    public void applyShortcutInfo(WorkspaceItemInfo info, ShortcutInfo detail,
            PopupContainerWithArrow container) {
        mInfo = info;
        mDetail = detail;
        mBubbleText.applyFromWorkspaceItem(info);
        mIconView.setBackground(guardLowContrastShortcutIcon(getContext(), mBubbleText.getIcon()));

        // Use the long label as long as it exists and fits.
        CharSequence longLabel = mDetail.getLongLabel();
        int availableWidth = mBubbleText.getWidth() - mBubbleText.getTotalPaddingLeft()
                - mBubbleText.getTotalPaddingRight();
        boolean usingLongLabel = !TextUtils.isEmpty(longLabel)
                && mBubbleText.getPaint().measureText(longLabel.toString()) <= availableWidth;
        mBubbleText.setText(usingLongLabel ? longLabel : mDetail.getShortLabel());

        // TODO: Add the click handler to this view directly and not the child view.
        mBubbleText.setOnClickListener(container.getItemClickListener());
        mBubbleText.setOnLongClickListener(container.getItemDragHandler());
        mBubbleText.setOnTouchListener(container.getItemDragHandler());
    }

    private static final int ICON_CONTRAST_SAMPLE_SIZE = 16;

    @VisibleForTesting
    public static final int LOW_CONTRAST_NONE = 0;

    @VisibleForTesting
    public static final int LOW_CONTRAST_WHITE_GLYPH = 1;

    @VisibleForTesting
    public static final int LOW_CONTRAST_WHITE_SOLID = 2;

    /**
     * Classifies whether a sampled shortcut icon is effectively white-on-white or white-monochrome
     * so light popup surfaces can add a subtle tonal container backing without touching colorful icons.
     */
    @VisibleForTesting
    public static int classifyLowContrastIcon(@Nullable Bitmap bitmap) {
        if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
            return LOW_CONTRAST_NONE;
        }
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int totalPixels = width * height;
        int opaquePixels = 0;
        int nearWhitePixels = 0;
        int contrastingPixels = 0;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = bitmap.getPixel(x, y);
                if (Color.alpha(pixel) < 128) {
                    continue;
                }
                opaquePixels++;
                int r = Color.red(pixel);
                int g = Color.green(pixel);
                int b = Color.blue(pixel);
                int maxChannel = Math.max(r, Math.max(g, b));
                int minChannel = Math.min(r, Math.min(g, b));
                int chroma = maxChannel - minChannel;
                double luminance = ColorUtils.calculateLuminance(pixel);
                if (luminance > 0.85 && chroma <= 28) {
                    nearWhitePixels++;
                }
                if (luminance < 0.72 || chroma > 28) {
                    contrastingPixels++;
                }
            }
        }
        if (opaquePixels == 0) {
            return LOW_CONTRAST_NONE;
        }
        float whiteRatio = nearWhitePixels / (float) opaquePixels;
        float contrastingRatio = contrastingPixels / (float) opaquePixels;
        if (whiteRatio < 0.90f || contrastingRatio >= 0.05f) {
            return LOW_CONTRAST_NONE;
        }
        float opaqueRatio = opaquePixels / (float) totalPixels;
        return opaqueRatio < 0.55f ? LOW_CONTRAST_WHITE_GLYPH : LOW_CONTRAST_WHITE_SOLID;
    }

    @Nullable
    @VisibleForTesting
    static Drawable guardLowContrastShortcutIcon(Context context, @Nullable Drawable icon) {
        if (icon == null) {
            return null;
        }
        @ColorInt int popupBg = ColorTokens.PopupColorPrimary.resolveColor(context);
        if (ColorUtils.calculateLuminance(popupBg) <= 0.5) {
            return icon;
        }
        Bitmap sample = Bitmap.createBitmap(
                ICON_CONTRAST_SAMPLE_SIZE,
                ICON_CONTRAST_SAMPLE_SIZE,
                Bitmap.Config.ARGB_8888);
        int classification;
        try {
            Rect savedBounds = icon.copyBounds();
            Canvas canvas = new Canvas(sample);
            icon.setBounds(0, 0, ICON_CONTRAST_SAMPLE_SIZE, ICON_CONTRAST_SAMPLE_SIZE);
            icon.draw(canvas);
            icon.setBounds(savedBounds);
            classification = classifyLowContrastIcon(sample);
        } finally {
            sample.recycle();
        }
        if (classification == LOW_CONTRAST_NONE) {
            return icon;
        }

        @ColorInt int onSurface = ColorTokens.TextColorPrimary.resolveColor(context);
        float density = context.getResources().getDisplayMetrics().density;
        GradientDrawable backing = new GradientDrawable();
        backing.setShape(GradientDrawable.OVAL);
        if (classification == LOW_CONTRAST_WHITE_GLYPH) {
            backing.setColor(ColorUtils.blendARGB(popupBg, onSurface, 0.72f));
            LayerDrawable layered = new LayerDrawable(new Drawable[] { backing, icon });
            int insetPx = Math.max(1, Math.round(3f * density));
            layered.setLayerInset(1, insetPx, insetPx, insetPx, insetPx);
            return layered;
        } else {
            backing.setColor(ColorUtils.setAlphaComponent(onSurface, 18));
            backing.setStroke(
                    Math.max(1, Math.round(1f * density)),
                    ColorUtils.setAlphaComponent(onSurface, 64));
            return new LayerDrawable(new Drawable[] { icon, backing });
        }
    }

    /**
     * Returns the shortcut info that is suitable to be added on the homescreen
     */
    public WorkspaceItemInfo getFinalInfo() {
        final WorkspaceItemInfo badged = new WorkspaceItemInfo(mInfo);
        // Queue an update task on the worker thread. This ensures that the badged
        // shortcut eventually gets its icon updated.
        Launcher.getLauncher(getContext()).getModel()
                .updateAndBindWorkspaceItem(badged, mDetail);
        return badged;
    }

    public View getIconView() {
        return mIconView;
    }

    public ShortcutInfo getDetail() {
        return mDetail;
    }
}
