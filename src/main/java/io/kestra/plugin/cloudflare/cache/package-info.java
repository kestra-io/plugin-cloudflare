@PluginSubGroup(
    description = "Tasks that manage Cloudflare cache operations. Purge entire zone cache or specific cached files and URLs. Automate cache invalidation workflows securely using Cloudflare API tokens.",
    categories = { PluginSubGroup.PluginCategory.CLOUD, PluginSubGroup.PluginCategory.INFRASTRUCTURE }
)
package io.kestra.plugin.cloudflare.cache;

import io.kestra.core.models.annotations.PluginSubGroup;