# Kestra Cloudflare Plugin

## What

- Provides plugin components under `io.kestra.plugin.cloudflare`.
- Includes classes such as `CloudflareEnvelope`, `List`, `Get`, `Purge`.

## Why

- This plugin integrates Kestra with Cloudflare IP Access Rules.
- It provides tasks that manage Cloudflare IP Access Rules.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin`:

- `cloudflare`

Infrastructure dependencies (Docker Compose services):

- `app`

### Key Plugin Classes

- `io.kestra.plugin.cloudflare.cache.Purge`
- `io.kestra.plugin.cloudflare.compute.kv.Get`
- `io.kestra.plugin.cloudflare.compute.kv.Write`
- `io.kestra.plugin.cloudflare.compute.namespaces.Create`
- `io.kestra.plugin.cloudflare.dns.records.Batch`
- `io.kestra.plugin.cloudflare.dns.records.Create`
- `io.kestra.plugin.cloudflare.dns.records.Delete`
- `io.kestra.plugin.cloudflare.dns.records.Get`
- `io.kestra.plugin.cloudflare.dns.records.List`
- `io.kestra.plugin.cloudflare.dns.records.Update`
- `io.kestra.plugin.cloudflare.dns.records.Upsert`
- `io.kestra.plugin.cloudflare.waf.accessrules.Create`
- `io.kestra.plugin.cloudflare.waf.accessrules.Delete`
- `io.kestra.plugin.cloudflare.waf.accessrules.List`
- `io.kestra.plugin.cloudflare.zones.Get`
- `io.kestra.plugin.cloudflare.zones.List`

### Project Structure

```
plugin-cloudflare/
├── src/main/java/io/kestra/plugin/cloudflare/zones/
├── src/test/java/io/kestra/plugin/cloudflare/zones/
├── build.gradle
└── README.md
```

## Local rules

- Base the wording on the implemented packages and classes, not on template README text.

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
