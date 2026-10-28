package ai.momobird.halo.halo;

import org.springframework.stereotype.Component;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.model.IModel;
import org.thymeleaf.processor.element.IElementModelStructureHandler;
import reactor.core.publisher.Mono;
import run.halo.app.theme.dialect.TemplateHeadProcessor;

/**
 * Puts the MomoBird Q&A widget script into the head of every theme page, when it is switched on
 * and MomoBird says it is available. Halo waits for head processors while rendering, so this only
 * reads cached state (see {@link SiteWidget}).
 */
@Component
public class WidgetHeadProcessor implements TemplateHeadProcessor {

    private final SiteWidget site;

    public WidgetHeadProcessor(SiteWidget site) {
        this.site = site;
    }

    @Override
    public Mono<Void> process(ITemplateContext context, IModel model, IElementModelStructureHandler structureHandler) {
        return site.headSnippet()
            .doOnNext(html -> model.add(context.getModelFactory().createText(html)))
            .then();
    }
}
