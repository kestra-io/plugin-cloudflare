@PluginSubGroup(
    title = "Cloudflare Workers KV Namespaces",
    description = "Tasks that manage Cloudflare Workers KV namespaces. Create, list, and delete namespaces to organize and isolate key-value storage within your Cloudflare account.",
    categories = {
        PluginSubGroup.PluginCategory.CLOUD,
        PluginSubGroup.PluginCategory.INFRASTRUCTURE
    }
)
package io.kestra.plugin.cloudflare.compute.namespaces;

import io.kestra.core.models.annotations.PluginSubGroup;