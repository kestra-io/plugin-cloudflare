@PluginSubGroup(
    title = "WAF Access Rules",
    description = "Tasks that manage Cloudflare security and firewall configurations, including IP Access Rules and related protections. Automate blocking, challenging, or allowing traffic at the account or zone level to enhance application security and threat response.",
    categories = {
        PluginSubGroup.PluginCategory.CLOUD,
        PluginSubGroup.PluginCategory.INFRASTRUCTURE
    }
)
package io.kestra.plugin.cloudflare.waf.accessrules;

import io.kestra.core.models.annotations.PluginSubGroup;