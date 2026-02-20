@PluginSubGroup(
    description = "Tasks that manage Cloudflare DNS records. Create, retrieve, update, delete, list, batch, and upsert DNS records within Cloudflare zones. Supports common record types such as A, AAAA, CNAME, TXT, MX, and more for full DNS automation.",
    categories = { PluginSubGroup.PluginCategory.CLOUD, PluginSubGroup.PluginCategory.INFRASTRUCTURE }
)
package io.kestra.plugin.cloudflare.dns.records;

import io.kestra.core.models.annotations.PluginSubGroup;