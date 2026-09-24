package cn.sidekey.menu;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

/** 名称由本地动作表映射；仅加载随 APK 打包的 Apple SF Symbols 路径。 */
final class SfSymbols {
    private SfSymbols() { }

    static Drawable drawable(Context context, String name, int color) {
        int id = context.getResources().getIdentifier("sf_" + name.replace('.', '_'), "drawable", context.getPackageName());
        if (id == 0) id = context.getResources().getIdentifier("sf_app_fill", "drawable", context.getPackageName());
        Drawable result = context.getResources().getDrawable(id, context.getTheme()).mutate();
        result.setTint(color);
        return result;
    }

    static void apply(ImageView image, String name, int color) {
        String key = name + ":" + color;
        if (key.equals(image.getTag())) return;
        image.setImageDrawable(drawable(image.getContext(), name, color)); image.setTag(key);
    }

    static String action(String type) {
        switch (type) {
            case "torch": return "flashlight.on.fill";
            case "mijia": case "home": return "house.fill";
            case "surfing": return "network";
            case "back": return "arrow.uturn.backward";
            case "recents": return "rectangle.stack.fill";
            case "notifications": return "bell.fill";
            case "quick_settings": return "slider.horizontal.3";
            case "screenshot": return "viewfinder";
            case "screen_off": return "lock.fill";
            case "play_pause": return "play.fill";
            case "next": return "forward.fill";
            case "previous": return "backward.fill";
            case "volume_up": case "volume_down": return "speaker.wave.2.fill";
            case "mute": return "speaker.slash.fill";
            case "camera": return "camera.fill";
            case "keycode": return "keyboard";
            case "shell": return "terminal";
            default: return "app.fill";
        }
    }
}
