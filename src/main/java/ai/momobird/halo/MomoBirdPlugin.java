package ai.momobird.halo;

import org.springframework.stereotype.Component;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

/**
 * Plugin main class. Later tasks register the sync engine, the front-end
 * injection and the admin endpoints as ordinary Spring components.
 */
@Component
public class MomoBirdPlugin extends BasePlugin {

    public MomoBirdPlugin(PluginContext pluginContext) {
        super(pluginContext);
    }
}
