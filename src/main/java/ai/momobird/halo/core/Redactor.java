package ai.momobird.halo.core;

import java.util.regex.Pattern;

/**
 * Removes the connection key, and anything shaped like one, from text that comes from outside
 * (exception messages, response bodies) before it reaches a log line, an exception message or an
 * admin-page response.
 */
public final class Redactor {

    // At least one character after the prefix: a bare "mbk_" (as in "应以 mbk_ 开头") is an
    // explanation, not a key, and stays readable.
    private static final Pattern KEY_SHAPE = Pattern.compile("mbk_[A-Za-z0-9_-]+");
    private static final String MASK = "***";

    private Redactor() {
    }

    public static String clean(String text, String key) {
        if (text == null) {
            return "";
        }
        String clean = text;
        if (key != null && !key.isBlank()) {
            clean = clean.replace(key, MASK);
        }
        return KEY_SHAPE.matcher(clean).replaceAll(MASK);
    }
}
