package ai.momobird.halo.core;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers, per post, the annotations that decide what MomoBird gets: the exclude mark and the
 * restricted-reading switch. Halo publishes no post event when only annotations change, so a
 * watcher on posts asks {@link #changed} on every update it sees, and only a real change of these
 * marks sends the post to the sync engine again.
 *
 * <p>The baseline is what the engine itself last read ({@link #record}: every listed or re-read
 * post), or what the watcher last saw. A post the engine has not read since the plugin started is
 * only worth a look when it carries one of the marks (it may have just been added); otherwise its
 * first sighting is just remembered, and the startup and daily full syncs cover the rest.
 *
 * <p>Thread safe: the engine records on its own thread while Halo's controller thread asks.
 */
public final class AnnotationWatch {

    /** The annotations the sync reads; any other annotation changing is noise. */
    static final List<String> KEYS = List.of(SyncRules.EXCLUDE_ANNOTATION, HiddenContent.RESTRICT_ENABLED);

    private final Map<String, String> seen = new ConcurrentHashMap<>();

    /** The engine read this post: it acted on these annotations. */
    public void record(String name, Map<String, String> annotations) {
        if (name != null) {
            seen.put(name, signature(annotations));
        }
    }

    /** A watcher saw the post. True when the engine should read it again. */
    public boolean changed(String name, Map<String, String> annotations) {
        if (name == null) {
            return false;
        }
        String now = signature(annotations);
        String before = seen.put(name, now);
        return before == null ? !now.isEmpty() : !before.equals(now);
    }

    /** The post is gone. */
    public void forget(String name) {
        if (name != null) {
            seen.remove(name);
        }
    }

    public void clear() {
        seen.clear();
    }

    int size() {
        return seen.size();
    }

    /** The sync-relevant annotations in a fixed order; empty text when the post has none of them. */
    static String signature(Map<String, String> annotations) {
        if (annotations == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String key : KEYS) {
            String value = annotations.get(key);
            if (value != null) {
                out.append(key).append('=').append(value).append('\n');
            }
        }
        return out.toString();
    }
}
