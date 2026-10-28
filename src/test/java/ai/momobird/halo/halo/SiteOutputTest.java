package ai.momobird.halo.halo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.momobird.halo.core.FakeConnect;
import ai.momobird.halo.core.PostId;
import ai.momobird.halo.core.Settings;
import ai.momobird.halo.core.WidgetState;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.model.IModel;
import org.thymeleaf.model.IModelFactory;
import org.thymeleaf.model.IText;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.Metadata;
import run.halo.app.theme.ReactivePostContentHandler.PostContentContext;

class SiteOutputTest {

    private static final String KEY = "mbk_HALOLOCALTESTKEYHALOLOCALTESTKEYHALOLOCALTE";
    private static final String SOURCE = "0192a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b";
    private static final Instant NOW = Instant.parse("2026-10-28T08:00:00Z");

    private final FakeConnect connect = new FakeConnect();
    private final Deque<Runnable> queued = new ArrayDeque<>();
    private final WidgetState state = new WidgetState(s -> connect, queued::add);
    private Settings settings = Settings.of(null, KEY, true, "right", true, false);
    private final SiteWidget site = new SiteWidget(() -> Mono.fromSupplier(() -> settings), state,
        Clock.fixed(NOW, ZoneOffset.UTC));

    SiteOutputTest() {
        connect.sourceId = SOURCE;
        connect.publicId = "pub_site";
        connect.widgetReason = "ok";
    }

    private void warm() {
        site.headSnippet().block();      // queues the first refresh
        queued.forEach(Runnable::run);   // the background thread's turn
        queued.clear();
    }

    private static Post post(String name, boolean publish, boolean deleted, Post.VisibleEnum visible,
                             Map<String, String> annotations) {
        Post post = new Post();
        Metadata metadata = new Metadata();
        metadata.setName(name);
        metadata.setAnnotations(annotations);
        post.setMetadata(metadata);
        Post.PostSpec spec = new Post.PostSpec();
        spec.setTitle("标题");
        spec.setPublish(publish);
        spec.setDeleted(deleted);
        spec.setVisible(visible);
        spec.setPublishTime(Instant.parse("2026-10-01T00:00:00Z"));
        post.setSpec(spec);
        Post.PostStatus status = new Post.PostStatus();
        status.setPhase(publish ? "PUBLISHED" : "DRAFT");
        post.setStatus(status);
        return post;
    }

    private static Post publicPost(String name) {
        return post(name, true, false, Post.VisibleEnum.PUBLIC, Map.of());
    }

    private String handle(Post post, String content) {
        PostContentContext context = PostContentContext.builder().post(post).content(content).build();
        return new PodcastContentHandler(site).handle(context).block().getContent();
    }

    // ---------------------------------------------------------------- head

    @Test
    void headShowsNothingUntilTheFirstRefreshCompletes() {
        assertThat(site.headSnippet().block()).isNull();
        assertThat(queued).hasSize(1);
        queued.poll().run();
        assertThat(site.headSnippet().block()).contains("data-project=\"pub_site\"");
    }

    @Test
    void headIsOffWhenTheSettingIsOff() {
        warm();
        settings = Settings.of(null, KEY, false, "right", true, false);
        assertThat(site.headSnippet().block()).isNull();
    }

    @Test
    void headFollowsThePositionSetting() {
        warm();
        settings = Settings.of(null, KEY, true, "left", true, false);
        assertThat(site.headSnippet().block()).contains("data-position=\"left\"");
    }

    @Test
    void theHeadProcessorAddsTheScriptToTheModel() {
        warm();
        ITemplateContext context = mock(ITemplateContext.class);
        IModelFactory factory = mock(IModelFactory.class);
        IText text = mock(IText.class);
        IModel model = mock(IModel.class);
        when(context.getModelFactory()).thenReturn(factory);
        when(factory.createText(anyString())).thenReturn(text);

        new WidgetHeadProcessor(site).process(context, model, null).block();

        verify(factory).createText(site.headSnippet().block());
        verify(model).add(text);
    }

    @Test
    void theHeadProcessorAddsNothingWhenHidden() {
        ITemplateContext context = mock(ITemplateContext.class);
        IModel model = mock(IModel.class);

        new WidgetHeadProcessor(site).process(context, model, null).block();

        verify(model, never()).add(any());
    }

    // ------------------------------------------------------------- podcast

    @Test
    void podcastMarkupOnlyForSyncablePosts() {
        warm();
        String id = PostId.of("hello");

        String done = handle(publicPost("hello"), "<p>正文</p>");
        assertThat(done).startsWith("<p>正文</p>")
            .contains("<div class=\"momobird-podcast\" data-source=\"" + SOURCE + "\" data-post=\"" + id
                + "\" data-api=\"https://api.valley.atlaspaces.com\"></div>")
            .contains("<script defer src=\"https://momobird.atlaspaces.com/widget/podcast.v1.js\"></script>");

        List<Post> notSyncable = List.of(
            post("draft", false, false, Post.VisibleEnum.PUBLIC, Map.of()),
            post("trash", true, true, Post.VisibleEnum.PUBLIC, Map.of()),
            post("private", true, false, Post.VisibleEnum.PRIVATE, Map.of()),
            post("internal", true, false, Post.VisibleEnum.INTERNAL, Map.of()),
            post("excluded", true, false, Post.VisibleEnum.PUBLIC, Map.of("momobird.ai/exclude", "true")));
        for (Post post : notSyncable) {
            assertThat(handle(post, "<p>正文</p>")).as(post.getMetadata().getName()).isEqualTo("<p>正文</p>");
        }
    }

    @Test
    void podcastNeedsTheSettingAndTheSourceId() {
        // no state yet: no source id
        assertThat(handle(publicPost("hello"), "<p>x</p>")).isEqualTo("<p>x</p>");

        warm();
        settings = Settings.of(null, KEY, true, "right", false, false);
        assertThat(handle(publicPost("hello"), "<p>x</p>")).isEqualTo("<p>x</p>");
    }

    @Test
    void podcastWorksWithTheAssistantSwitchedOff() {
        settings = Settings.of(null, KEY, false, "right", true, false);
        handle(publicPost("hello"), "<p>x</p>");   // triggers the refresh for the source id
        queued.forEach(Runnable::run);
        queued.clear();
        assertThat(handle(publicPost("hello"), "<p>x</p>")).contains("momobird-podcast");
    }

    @Test
    void transcriptAttributeFollowsTheSetting() {
        warm();
        settings = Settings.of(null, KEY, true, "right", true, true);
        assertThat(handle(publicPost("hello"), "")).contains("data-transcript=\"1\"></div>");
    }

    @Test
    void aBadSourceIdProducesNoMarkup() {
        connect.sourceId = "\"><script>alert(1)</script>";
        warm();
        assertThat(handle(publicPost("hello"), "<p>x</p>")).isEqualTo("<p>x</p>");
    }

    @Test
    void attributeValuesAreEscaped() {
        // a validated base URL cannot hold quotes, but the escaping still applies
        String html = ai.momobird.halo.core.PodcastMarkup.placeholder(SOURCE, "123", "https://x/\"&<'", false);
        assertThat(html).contains("data-api=\"https://x/&quot;&amp;&lt;&#39;\"");
    }

    @Test
    void aMissingPostOrContentIsHandled() {
        warm();
        PostContentContext context = PostContentContext.builder().content("<p>x</p>").build();
        assertThat(new PodcastContentHandler(site).handle(context).block().getContent()).isEqualTo("<p>x</p>");
        assertThat(handle(publicPost("hello"), null)).contains("momobird-podcast");
    }

    // ------------------------------------------------------------- secrets

    @Test
    void noKeyOrMbkStringInAnyRenderedOutput() {
        connect.publicId = "pub_site";
        warm();
        settings = Settings.of(null, KEY, true, "left", true, true);
        String head = site.headSnippet().block();
        String body = handle(publicPost("hello"), "<p>正文</p>");
        for (String output : new String[] {head, body}) {
            assertThat(output).isNotNull().doesNotContain(KEY).doesNotContain("mbk_");
        }
    }
}
