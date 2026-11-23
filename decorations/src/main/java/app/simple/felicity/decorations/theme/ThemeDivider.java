package app.simple.felicity.decorations.theme;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.simple.felicity.shared.utils.ColorUtils;
import app.simple.felicity.theme.interfaces.ThemeChangedListener;
import app.simple.felicity.theme.managers.ThemeManager;
import app.simple.felicity.theme.models.Theme;

public class ThemeDivider extends View implements ThemeChangedListener {
    
    public ThemeDivider(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    
    public ThemeDivider(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }
    
    public ThemeDivider(Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }
    
    private void init() {
        setBackgroundColor(Color.WHITE);
        setTint(false);
        setTranslationZ(1F);
        setFocusable(View.NOT_FOCUSABLE);
    }
    
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!isInEditMode()) {
            ThemeManager.INSTANCE.addListener(this);
            skMenuDivider = isBetweenMenuRows();
            if (skMenuDivider) {
                // Fork (白い熊 音楽 UI): between the groups of a menu the divider is a clear 1 dp
                // line in the opaque darker accent, not the faint hairline of a list.
                android.view.ViewGroup.LayoutParams params = getLayoutParams();
                int oneDp = Math.max(1, Math.round(getResources().getDisplayMetrics().density));
                if (params != null && params.height < oneDp) {
                    params.height = oneDp;
                    setLayoutParams(params);
                }
                setTint(false);
            }
        }
    }
    
    /** Fork: true while this divider separates groups of a menu sheet. */
    private boolean skMenuDivider = false;
    
    private boolean isBetweenMenuRows() {
        if (!app.simple.felicity.decorations.shiroikuma.SkPills.enabled()) {
            return false;
        }
        if (!(getParent() instanceof android.widget.LinearLayout parent)) {
            return false;
        }
        if (parent.getOrientation() != android.widget.LinearLayout.VERTICAL) {
            return false;
        }
        for (int i = 0; i < parent.getChildCount(); i++) {
            if (parent.getChildAt(i) instanceof app.simple.felicity.decorations.ripple.DynamicRippleTextView) {
                return true;
            }
        }
        return false;
    }
    
    @Override
    public void onThemeChanged(@NonNull Theme theme, boolean animate) {
        setTint(animate);
    }
    
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        ThemeManager.INSTANCE.removeListener(this);
    }
    
    private void setTint(boolean animate) {
        if (skMenuDivider && app.simple.felicity.decorations.shiroikuma.SkPills.enabled()) {
            setBackgroundTintList(ColorStateList.valueOf(app.simple.felicity.decorations.shiroikuma.SkPills.color(
                    app.simple.felicity.preferences.ShiroikumaPreferences.MENU_DIVIDER)));
            return;
        }
        
        if (isInEditMode()) {
            setBackgroundTintList(ColorStateList.valueOf(Color.LTGRAY));
        } else {
            if (animate) {
                ColorUtils.INSTANCE.animateColorChange(
                        this,
                        ThemeManager.INSTANCE.getTheme().getViewGroupTheme().getDividerColor());
            } else {
                setBackgroundTintList(ColorStateList
                        .valueOf(ThemeManager.INSTANCE.getTheme().getViewGroupTheme().getDividerColor()));
            }
        }
    }
}
