@PluginSubGroup(
    description = "Tasks that manage Cloudflare Workers KV key-value storage. Write, read, delete, and list keys within a namespace. Supports bulk operations for efficient edge configuration, feature flags, and distributed state management.",
    categories = {
        PluginSubGroup.PluginCategory.CLOUD,
        PluginSubGroup.PluginCategory.INFRASTRUCTURE
    }
)
package io.kestra.plugin.cloudflare.compute.kv;

import io.kestra.core.models.annotations.PluginSubGroup;