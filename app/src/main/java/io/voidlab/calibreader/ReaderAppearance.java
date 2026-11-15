package io.voidlab.calibreader;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persisted reader colour mode and the CSS/JavaScript used to apply it.
 *
 * <p>The reader itself is the server's web application, so the native shell
 * cannot restyle it directly. Inverted mode injects a small stylesheet that
 * inverts the page and compensates the hue, which turns the usual dark text on
 * a white page into white text on a black background. Images, video and other
 * replaced content are inverted a second time so covers and illustrations keep
 * their original colours.
 */
public final class ReaderAppearance {

    private static final String PREFS = "calibreader";
    private static final String KEY_INVERT = "invert_colors";
    private static final String STYLE_ID = "calibreader-invert";

    /**
     * The first rule inverts the whole document; the second restores images
     * and other media that would otherwise look like a photographic negative.
     *
     * <p>The root background is set to white, not black: the invert filter
     * applies to it too, so white turns into the black backdrop a transparent
     * or default document should show. Media is restored exactly once: the
     * {@code picture} wrapper is left out so its {@code img} child is not
     * inverted twice, and {@code svg:not(svg svg)} skips nested svg content.
     */
    private static final String INVERT_CSS =
            "html{background:#fff !important;"
                    + "filter:invert(1) hue-rotate(180deg) !important;}"
                    + "img,video,canvas,svg:not(svg svg){"
                    + "filter:invert(1) hue-rotate(180deg) !important;}";

    private final SharedPreferences prefs;

    public ReaderAppearance(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Never inverted by default: a fresh install keeps the normal appearance. */
    public boolean isInverted() {
        return prefs.getBoolean(KEY_INVERT, false);
    }

    public void setInverted(boolean inverted) {
        prefs.edit().putBoolean(KEY_INVERT, inverted).apply();
    }

    /** CSS injected for the inverted mode; package-visible for the unit test. */
    static String invertedCss() {
        return INVERT_CSS;
    }

    /**
     * JavaScript that adds the inverted stylesheet, replaces it when the mode
     * is already active, or removes it again for the normal appearance. It is
     * deliberately idempotent because it runs after every page load.
     */
    static String injectionScript(boolean inverted) {
        String remove = "var s=document.getElementById('" + STYLE_ID + "');"
                + "if(s&&s.parentNode){s.parentNode.removeChild(s);}";
        if (!inverted) {
            return "(function(){" + remove + "})();";
        }
        return "(function(){var d=document;"
                + "var s=d.getElementById('" + STYLE_ID + "');"
                + "if(!s){s=d.createElement('style');"
                + "s.id='" + STYLE_ID + "';"
                + "(d.head||d.documentElement).appendChild(s);}"
                + "s.textContent='" + INVERT_CSS + "';})();";
    }
}
