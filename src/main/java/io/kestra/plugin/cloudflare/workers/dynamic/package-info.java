@PluginSubGroup(
    title = "Dynamic Workers",
    description = "POST inline JavaScript or TypeScript to a user-deployed Cloudflare gateway Worker URL that executes the code on the edge.",
    categories = {
        PluginSubGroup.PluginCategory.CLOUD,
        PluginSubGroup.PluginCategory.INFRASTRUCTURE
    }
)
package io.kestra.plugin.cloudflare.workers.dynamic;

import io.kestra.core.models.annotations.PluginSubGroup;
