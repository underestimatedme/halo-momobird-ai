package ai.momobird.halo.core;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Builder;

/**
 * What the sync needs to know about one Halo post, copied out of Halo's {@code Post} so the rules
 * and the mapping can be tested without Halo. Every field may be missing, as in Halo's own data.
 *
 * @param name           {@code metadata.name}, the post's permanent id
 * @param title          {@code spec.title}
 * @param publish        {@code spec.publish}: the author wants it published
 * @param phase          {@code status.phase}: DRAFT, PENDING_APPROVAL, PUBLISHED or FAILED
 * @param publishTime    {@code spec.publishTime}
 * @param visible        {@code spec.visible}: PUBLIC, INTERNAL or PRIVATE; missing means PUBLIC
 * @param deleted        in the trash: {@code spec.deleted}, or being deleted
 * @param annotations    {@code metadata.annotations}
 * @param permalink      {@code status.permalink}: a path, as Halo builds it
 * @param categoryNames  {@code spec.categories}: category names, in the author's order
 * @param lastModified   {@code status.lastModifyTime}: when the released content was last edited
 */
@Builder(toBuilder = true)
public record PostView(
    String name,
    String title,
    boolean publish,
    String phase,
    Instant publishTime,
    String visible,
    boolean deleted,
    Map<String, String> annotations,
    String permalink,
    List<String> categoryNames,
    Instant lastModified) {

    public PostView {
        Map<String, String> copy = new HashMap<>();
        if (annotations != null) {
            annotations.forEach((key, value) -> {
                if (key != null && value != null) {
                    copy.put(key, value);
                }
            });
        }
        annotations = Map.copyOf(copy);
        categoryNames = categoryNames == null ? List.of() : List.copyOf(categoryNames);
    }
}
