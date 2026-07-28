@PluginSubGroup(
    title = "KV",
    description = "Tasks that manage Cloudflare Workers KV key-value storage. Bulk read and write keys within a namespace, useful for edge configuration, feature flags, and distributed state management.",
    categories = {
        PluginSubGroup.PluginCategory.CLOUD,
        PluginSubGroup.PluginCategory.INFRASTRUCTURE
    }
)
package io.kestra.plugin.cloudflare.compute.kv;

import io.kestra.core.models.annotations.PluginSubGroup;