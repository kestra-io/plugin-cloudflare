# How to use the Cloudflare plugin

Manage Cloudflare zones, DNS records, cache, WAF rules, D1 databases, Workers, and Workers KV from Kestra flows.

## Authentication

Set `apiToken` (required) to a Cloudflare API token with the appropriate permissions. Optionally override `baseUrl` (default `https://api.cloudflare.com/client/v4`). Store secrets in [secrets](https://kestra.io/docs/concepts/secret) and set connection properties on each task.

## Tasks

### Zones

`zones.List` lists all zones in the account. The output includes a `zones` list with `id`, `name`, and `status` per zone.

`zones.Get` fetches a single zone — set either `zoneId` or `hostname` (one is required). The output includes `id`, `name`, and `status`.

### Cache

`cache.Purge` purges cached content for a zone — set `zoneId` (required) and at least one of: `purgeAll: true` (clears everything), `files` (list of URLs to purge), or `tags` (list of cache tags). The output includes `requestId`.

### DNS Records

`dns.records.Create` creates a DNS record — set `zoneId`, `recordType` (e.g. `A`, `CNAME`, `TXT`, `MX`), `name`, and `content` (all required). Optionally set `ttl` (default 1) and `proxied` (default `false`).

`dns.records.Get` fetches a record — set `zoneId` and `recordId` (both required).

`dns.records.List` lists records in a zone — set `zoneId` (required). The output includes a `records` list.

`dns.records.Update` updates a record — set `zoneId` and `recordId` (both required); all other fields (`recordType`, `name`, `content`, `ttl`, `proxied`) are optional and only sent if provided. The output includes `id`.

`dns.records.Delete` deletes a record — set `zoneId` and `recordId` (both required). The output includes `deletedId`.

`dns.records.Upsert` creates or updates a record by name and type — set `zoneId`, `recordType`, `name`, and `content` (all required). The output includes `action` (`created` or `updated`).

`dns.records.Batch` performs multiple DNS operations atomically — set `zoneId` (required) and at least one of `posts` (records to create), `patches` (records to update), or `deletes` (record IDs to remove). The output includes `success`.

### Workers KV

`compute.kv.Get` reads one or more KV values — set `accountId`, `namespaceId`, and `keys` (all required). The output includes a `values` map.

`compute.kv.Write` writes key-value pairs — set `accountId`, `namespaceId`, and `keyValues` (required, list of `{key, value}` objects). The output includes `successfulKeyCount` and `unsuccessfulKeys`.

`compute.namespaces.Create` creates a KV namespace — set `accountId` and `title` (both required). The output includes `namespaceId` and `title`.

### D1

`d1.CreateDatabase` creates a D1 database — set `accountId` and `name` (both required). The output includes `databaseId`, `name`, and `version`.

`d1.ListDatabases` lists all D1 databases in an account — set `accountId` (required). Optionally set `nameFilter` (prefix filter) and `perPage` (default 100, max 100); results are auto-paginated. The output includes a `databases` list and `total` count.

`d1.GetDatabase` fetches a single D1 database — set `accountId` and `databaseId` (both required). The output includes `databaseId`, `name`, `version`, `numTables`, and `fileSize`.

`d1.DeleteDatabase` deletes a D1 database — set `accountId` and `databaseId` (both required). The output includes `databaseId`.

`d1.Query` runs a parameterized SQL statement — set `accountId`, `databaseId`, and `sql` (all required). Optionally set `params` (list of bind values) and `fetchType` (default `STORE`; values: `FETCH_ONE`, `FETCH`, `STORE`, `NONE`). Output varies by `fetchType`: `FETCH_ONE` returns `row` and `meta`; `FETCH` returns `rows` and `meta`; `STORE` writes results to Kestra storage and returns `uri`; `NONE` returns only `meta`. All modes include `size` and a `meta` object with execution statistics.

`d1.RawQuery` runs a SQL statement and returns column-oriented results — set `accountId`, `databaseId`, and `sql` (all required). Optionally set `params`. The output includes `columns` (list of column names), `rows` (list of value arrays), `size`, and `meta`.

`d1.Export` exports a database to a SQL dump — set `accountId` and `databaseId` (both required). Optionally set `maxDuration` (ISO-8601 duration, default 5 minutes). The operation polls asynchronously until the export completes. The output includes `uri` (Kestra storage URI to the SQL file) and `filename`.

`d1.Import` imports SQL into a database — set `accountId`, `databaseId`, and exactly one of `from` (Kestra storage URI) or `sql` (inline SQL string). Optionally set `maxDuration` (default 5 minutes). The operation uses a 3-step protocol (init, upload, ingest) and polls until complete. The output includes `filename`, `etag`, `finalBookmark`, `numQueries`, and `meta`.

### Workers

`workers.dynamic.Run` runs inline JavaScript or TypeScript on a gateway Worker (Dynamic Workers) — set `gatewayUrl` and `script` (both required). Optionally set `payload`, `headers` (used for gateway authentication — this task takes no `apiToken`), and `contentType`. The output includes `statusCode`, `headers`, and `body`.

`workers.scripts.Deploy` uploads or replaces a Worker script — set `accountId`, `scriptName`, and `script` (all required). `PUT` is idempotent, so redeploying with the same `scriptName` updates the existing script. The output includes `id` and `etag`.

`workers.scripts.Get` fetches a Worker script's source — set `accountId` and `scriptName` (both required). Both module-format (multipart) and legacy service-worker responses are handled; the output includes `script`, `format`, `entrypoint`, `handlers`, and `etag`.

`workers.scripts.List` lists all Worker scripts on an account — set `accountId` (required); Cloudflare cursor pagination is followed internally.

`workers.scripts.Delete` deletes a Worker script — set `accountId` and `scriptName` (both required). Set `force: true` to delete a script still referenced by routes.

### WAF

`waf.accessrules.Create` creates an access rule — set `mode` (required: `BLOCK`, `CHALLENGE`, `WHITELIST`, `JS_CHALLENGE`, or `MANAGED_CHALLENGE`), `target` (required: `IP`, `IP_RANGE`, `ASN`, or `COUNTRY`), `value` (required), and either `zoneId` or `accountId`. Optionally set `notes`.

`waf.accessrules.List` lists access rules for a zone — set `zoneId` (required). The output includes a `rules` list.

`waf.accessrules.Delete` deletes an access rule — set `ruleId` (required) and either `zoneId` or `accountId` (exactly one). The output includes `ruleId` and `deleted`.
