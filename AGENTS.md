# Kestra Cloudflare Plugin

## What

description = 'Plugin cloudflare for Kestra Exposes 16 plugin components (tasks, triggers, and/or conditions).

## Why

Enables Kestra workflows to interact with cloudflare, allowing orchestration of cloudflare-based operations as part of data pipelines and automation workflows.

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

### Important Commands

```bash
# Build the plugin
./gradlew shadowJar

# Run tests
./gradlew test

# Build without tests
./gradlew shadowJar -x test
```

### Configuration

All tasks and triggers accept standard Kestra plugin properties. Credentials should use
`{{ secret('SECRET_NAME') }}` — never hardcode real values.

## Agents

**IMPORTANT:** This is a Kestra plugin repository (prefixed by `plugin-`, `storage-`, or `secret-`). You **MUST** delegate all coding tasks to the `kestra-plugin-developer` agent. Do NOT implement code changes directly — always use this agent.
