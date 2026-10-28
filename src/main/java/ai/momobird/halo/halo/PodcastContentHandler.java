package ai.momobird.halo.halo;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.theme.ReactivePostContentHandler;

/**
 * Adds the podcast player to the end of a post's content, for the posts MomoBird holds (the same
 * rules as the sync) when the podcast setting is on.
 */
@Component
public class PodcastContentHandler implements ReactivePostContentHandler {

    private final SiteWidget site;

    public PodcastContentHandler(SiteWidget site) {
        this.site = site;
    }

    @Override
    public Mono<PostContentContext> handle(PostContentContext postContent) {
        if (postContent == null || postContent.getPost() == null || postContent.getPost().getMetadata() == null) {
            return Mono.justOrEmpty(postContent);
        }
        return site.podcastHtml(HaloPosts.view(postContent.getPost()))
            .map(html -> {
                String content = postContent.getContent();
                postContent.setContent((content == null ? "" : content) + "\n" + html);
                return postContent;
            })
            .defaultIfEmpty(postContent)
            .onErrorReturn(postContent);
    }
}
