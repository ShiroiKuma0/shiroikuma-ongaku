package app.simple.felicity.decorations.corners;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import com.google.android.material.shape.CornerFamily;
import com.google.android.material.shape.MaterialShapeDrawable;
import com.google.android.material.shape.ShapeAppearanceModel;

import app.simple.felicity.decoration.R;
import app.simple.felicity.preferences.AccessibilityPreferences;
import app.simple.felicity.preferences.AppearancePreferences;
import app.simple.felicity.preferences.ShiroikumaPreferences;
import app.simple.felicity.theme.managers.ThemeManager;

/**
 * @noinspection resource
 */
public class LayoutBackground {

    private static final float strokeWidth = 1F;

    /**
     * Fork (白い熊 音楽 UI): border on every dynamic-corner surface. Width and color come
     * from {@link ShiroikumaPreferences}; width 0 disables the border entirely. The stock
     * accessibility highlight stroke remains as the fallback behaviour.
     */
    private static void applyStroke(View view, MaterialShapeDrawable drawable) {
        float borderWidthDp = ShiroikumaPreferences.INSTANCE.getBorderWidth();
        if (ShiroikumaPreferences.INSTANCE.isEnabled() && borderWidthDp > 0F) {
            float borderWidthPx = borderWidthDp * view.getResources().getDisplayMetrics().density;
            drawable.setStroke(borderWidthPx, ShiroikumaPreferences.INSTANCE.getEffectiveColor(ShiroikumaPreferences.BORDER));
        } else if (AccessibilityPreferences.INSTANCE.isHighlightStroke()) {
            drawable.setStroke(strokeWidth, ThemeManager.INSTANCE.getAccent().getPrimaryAccentColor());
        }
    }
    
    public static void setBackground(Context context, ViewGroup viewGroup, AttributeSet attrs, float radius) {
        TypedArray theme = context.getTheme().obtainStyledAttributes(attrs, R.styleable.DynamicCornerLayout, 0, 0);
        boolean roundTopCorners = theme.getBoolean(R.styleable.DynamicCornerLayout_roundTopCorners, false);
        boolean roundBottomCorners = theme.getBoolean(R.styleable.DynamicCornerLayout_roundBottomCorners, false);
        
        ShapeAppearanceModel shapeAppearanceModel;
        
        if (roundBottomCorners && roundTopCorners) {
            shapeAppearanceModel = new ShapeAppearanceModel()
                    .toBuilder()
                    .setAllCorners(CornerFamily.ROUNDED, radius)
                    .build();
        } else if (roundTopCorners) {
            shapeAppearanceModel = new ShapeAppearanceModel()
                    .toBuilder()
                    .setTopLeftCorner(CornerFamily.ROUNDED, radius)
                    .setTopRightCorner(CornerFamily.ROUNDED, radius)
                    .build();
        } else if (roundBottomCorners) {
            shapeAppearanceModel = new ShapeAppearanceModel()
                    .toBuilder()
                    .setBottomLeftCorner(CornerFamily.ROUNDED, radius)
                    .setBottomRightCorner(CornerFamily.ROUNDED, radius)
                    .build();
        } else {
            shapeAppearanceModel = new ShapeAppearanceModel()
                    .toBuilder()
                    .setAllCorners(CornerFamily.ROUNDED, radius)
                    .build();
        }
        
        MaterialShapeDrawable materialShapeDrawable = new MaterialShapeDrawable(shapeAppearanceModel);
        applyStroke(viewGroup, materialShapeDrawable);
        viewGroup.setBackground(materialShapeDrawable);
        theme.recycle();
    }

    public static void setBackground(Context context, View viewGroup, AttributeSet attrs, float factor) {
        TypedArray theme = context.getTheme().obtainStyledAttributes(attrs, R.styleable.DynamicCornerLayout, 0, 0);
        boolean roundTopCorners = theme.getBoolean(R.styleable.DynamicCornerLayout_roundTopCorners, false);
        boolean roundBottomCorners = theme.getBoolean(R.styleable.DynamicCornerLayout_roundBottomCorners, false);
        
        ShapeAppearanceModel shapeAppearanceModel;
        
        if (roundBottomCorners && roundTopCorners) {
            shapeAppearanceModel = new ShapeAppearanceModel()
                    .toBuilder()
                    .setAllCorners(CornerFamily.ROUNDED, AppearancePreferences.INSTANCE.getCornerRadius() / factor)
                    .build();
        } else if (roundTopCorners) {
            shapeAppearanceModel = new ShapeAppearanceModel()
                    .toBuilder()
                    .setTopLeftCorner(CornerFamily.ROUNDED, AppearancePreferences.INSTANCE.getCornerRadius() / factor)
                    .setTopRightCorner(CornerFamily.ROUNDED, AppearancePreferences.INSTANCE.getCornerRadius() / factor)
                    .build();
        } else if (roundBottomCorners) {
            shapeAppearanceModel = new ShapeAppearanceModel()
                    .toBuilder()
                    .setBottomLeftCorner(CornerFamily.ROUNDED, AppearancePreferences.INSTANCE.getCornerRadius() / factor)
                    .setBottomRightCorner(CornerFamily.ROUNDED, AppearancePreferences.INSTANCE.getCornerRadius() / factor)
                    .build();
        } else {
            shapeAppearanceModel = new ShapeAppearanceModel()
                    .toBuilder()
                    .setAllCorners(CornerFamily.ROUNDED, AppearancePreferences.INSTANCE.getCornerRadius() / factor)
                    .build();
        }
        
        MaterialShapeDrawable materialShapeDrawable = new MaterialShapeDrawable(shapeAppearanceModel);
        applyStroke(viewGroup, materialShapeDrawable);
        viewGroup.setBackground(materialShapeDrawable);
        theme.recycle();
    }

    public static void setBackground(Context context, View view, AttributeSet attrs) {
        TypedArray theme = context.getTheme().obtainStyledAttributes(attrs, R.styleable.DynamicCornerLayout, 0, 0);
        boolean roundTopCorners = theme.getBoolean(R.styleable.DynamicCornerLayout_roundTopCorners, false);
        boolean roundBottomCorners = theme.getBoolean(R.styleable.DynamicCornerLayout_roundBottomCorners, false);
        
        ShapeAppearanceModel shapeAppearanceModel;
        
        if (roundBottomCorners && roundTopCorners) {
            shapeAppearanceModel = new ShapeAppearanceModel()
                    .toBuilder()
                    .setAllCorners(CornerFamily.ROUNDED, AppearancePreferences.INSTANCE.getCornerRadius())
                    .build();
        } else if (roundTopCorners) {
            shapeAppearanceModel = new ShapeAppearanceModel()
                    .toBuilder()
                    .setTopLeftCorner(CornerFamily.ROUNDED, AppearancePreferences.INSTANCE.getCornerRadius())
                    .setTopRightCorner(CornerFamily.ROUNDED, AppearancePreferences.INSTANCE.getCornerRadius())
                    .build();
        } else if (roundBottomCorners) {
            shapeAppearanceModel = new ShapeAppearanceModel()
                    .toBuilder()
                    .setBottomLeftCorner(CornerFamily.ROUNDED, AppearancePreferences.INSTANCE.getCornerRadius())
                    .setBottomRightCorner(CornerFamily.ROUNDED, AppearancePreferences.INSTANCE.getCornerRadius())
                    .build();
        } else {
            shapeAppearanceModel = new ShapeAppearanceModel()
                    .toBuilder()
                    .setAllCorners(CornerFamily.ROUNDED, AppearancePreferences.INSTANCE.getCornerRadius())
                    .build();
        }
        
        MaterialShapeDrawable materialShapeDrawable = new MaterialShapeDrawable(shapeAppearanceModel);
        applyStroke(view, materialShapeDrawable);
        view.setBackground(materialShapeDrawable);
        theme.recycle();
    }

    public static void setBackground(View view) {
        ShapeAppearanceModel shapeAppearanceModel;

        shapeAppearanceModel = new ShapeAppearanceModel()
                .toBuilder()
                .setAllCorners(CornerFamily.ROUNDED, AppearancePreferences.INSTANCE.getCornerRadius())
                .build();

        MaterialShapeDrawable materialShapeDrawable = new MaterialShapeDrawable(shapeAppearanceModel);
        applyStroke(view, materialShapeDrawable);
        view.setBackground(materialShapeDrawable);
    }
}
