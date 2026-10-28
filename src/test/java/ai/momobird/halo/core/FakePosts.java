package ai.momobird.halo.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/** An in-memory Halo for the sync tests. Counts what is read so tests can check streaming. */
public class FakePosts implements PostSource {

    public static final String SITE = "https://blog.example.com";

    public final Map<String, PostView> posts = new LinkedHashMap<>();
    public final Map<String, String> html = new HashMap<>();
    public String externalUrl = SITE;
    public CategoryTree tree = CategoryTree.EMPTY;

    public final AtomicInteger bodiesRead = new AtomicInteger();
    public final AtomicInteger viewsPulled = new AtomicInteger();
    public final AtomicInteger gets = new AtomicInteger();
    public final List<String> bodyNames = new ArrayList<>();

    /** A published, public post with some text. */
    public static PostView published(String name) {
        return PostView.builder()
            .name(name)
            .title("标题 " + name)
            .publish(true)
            .phase("PUBLISHED")
            .publishTime(Instant.parse("2026-10-01T00:00:00Z"))
            .visible("PUBLIC")
            .permalink("/archives/" + name)
            .build();
    }

    public FakePosts add(PostView view) {
        posts.put(view.name(), view);
        html.putIfAbsent(view.name(), "<p>正文 " + view.name() + "</p>");
        return this;
    }

    public FakePosts addPublished(String name) {
        return add(published(name));
    }

    public FakePosts change(String name, java.util.function.UnaryOperator<PostView> edit) {
        posts.put(name, edit.apply(posts.get(name)));
        return this;
    }

    @Override
    public Stream<PostView> all() {
        Iterator<PostView> source = new ArrayList<>(posts.values()).iterator();
        Iterator<PostView> counting = new Iterator<>() {
            @Override
            public boolean hasNext() {
                return source.hasNext();
            }

            @Override
            public PostView next() {
                viewsPulled.incrementAndGet();
                return source.next();
            }
        };
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(counting, Spliterator.ORDERED), false);
    }

    @Override
    public Optional<PostView> get(String name) {
        gets.incrementAndGet();
        return Optional.ofNullable(posts.get(name));
    }

    @Override
    public String releaseHtml(String name) {
        bodiesRead.incrementAndGet();
        synchronized (bodyNames) {
            bodyNames.add(name);
        }
        return html.getOrDefault(name, "");
    }

    @Override
    public CategoryTree categoryTree() {
        return tree;
    }

    @Override
    public String externalUrl() {
        return externalUrl;
    }
}
