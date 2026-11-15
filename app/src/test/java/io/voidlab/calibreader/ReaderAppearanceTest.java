package io.voidlab.calibreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Focused tests for the reader colour mode. The stylesheet and script are kept
 * free of Android calls so they can be checked here; the menu wiring itself
 * needs a device/emulator.
 */
public class ReaderAppearanceTest {

    @Test
    public void invertedCssMakesWhiteTextOnBlack() {
        String css = ReaderAppearance.invertedCss();
        assertTrue("page must be inverted", css.contains("filter:invert(1)"));
        assertTrue("hue must be preserved", css.contains("hue-rotate(180deg)"));
        assertTrue("root background must be white so the invert filter turns it black",
                css.contains("background:#fff"));
        assertFalse("a black root background would invert to white",
                css.contains("background:#000"));
    }

    @Test
    public void mediaCompensationIsAppliedOnce() {
        String css = ReaderAppearance.invertedCss();
        assertTrue("images must be restored", css.contains("img,video,canvas"));
        assertFalse("picture wraps img, so selecting it would invert the image twice",
                css.contains("picture"));
        assertTrue("nested svg must not be compensated twice",
                css.contains("svg:not(svg svg)"));
    }

    @Test
    public void invertedScriptAddsTheStylesheet() {
        String script = ReaderAppearance.injectionScript(true);
        assertTrue(script.contains("calibreader-invert"));
        assertTrue(script.contains("createElement('style')"));
        assertTrue(script.contains(ReaderAppearance.invertedCss()));
        assertFalse("the style must not be removed in inverted mode",
                script.contains("removeChild"));
    }

    @Test
    public void normalScriptRemovesTheStylesheet() {
        String script = ReaderAppearance.injectionScript(false);
        assertTrue(script.contains("calibreader-invert"));
        assertTrue(script.contains("removeChild"));
        assertFalse("normal mode must not inject any filter",
                script.contains("filter:invert"));
    }

    @Test
    public void scriptsAreIdempotent() {
        String script = ReaderAppearance.injectionScript(true);
        assertTrue("an existing style must be reused", script.contains("if(!s)"));
        assertEquals(script, ReaderAppearance.injectionScript(true));
    }
}
