# Changelog

All notable changes to hive-hot will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.9] - 2026-09-01

### Added

- **Scoped reload**: `reload-scoped!` loads the changes under a set of source
  roots, drags in the namespaces that depend on them, and DECLINES every other
  change; declined files stay pending for the reload that owns their root.
  Reports `:skipped`, `:dragged`, `:unchanged?` and `:multi-file` (a loaded
  namespace found in more than one file).
- **Per-file baseline**: the registry remembers, per file, the version the
  image holds, so a change one reload declined is still visible to the next;
  `reload!` without `:only` now goes through the same engine.
- `init!` accepts `:since` (epoch ms): pass the JVM start time so edits made
  before the first init are changes, not the baseline.
- `extend-init!` / `ensure-init!`: add dirs (and `:no-reload` / `:no-unload`)
  to an initialized registry without resetting its baseline; the file watcher,
  when running, is restarted over the union. `init-with-watcher!` extends
  rather than resets an initialized registry.
- `scope-plan`: the effect-free preview of a scoped reload.
- `status` reports `:dirs`, `:since` and `:pending` when initialized.

### Fixed

- A reload on a registry nobody initialized adopts clj-reload's classpath
  config instead of resetting it to `["src"]`.
- A scan-time parse failure is folded into the result (`:failed`, `:error`)
  instead of escaping `reload!`.

## [0.1.0] - 2025-01-14

### Added

- **Component Registry** - Named components with lifecycle callbacks
  - `reg-hot` - Register component with namespace and callbacks
  - `unreg-hot` - Unregister component
  - `get-component` - Retrieve component by ID
  - `list-components` - List all registered component IDs

- **Core Reload Functions**
  - `reload!` - Reload changed namespaces and dependents
  - `reload-all!` - Force reload all namespaces
  - `init!` - Initialize with source directories

- **Event Listener System**
  - `add-listener!` - Subscribe to reload events
  - `remove-listener!` - Unsubscribe listener
  - Events: `:reload-start`, `:reload-success`, `:reload-error`, `:component-callback`

- **Status & Introspection**
  - `status` - Get initialization state, components, listener count
  - `reset!` - Clear all registrations (for testing)

- **Convenience Utilities**
  - `with-reload` macro - Execute code then trigger reload
  - `find-namespaces` - Find namespaces matching pattern

### Technical Details

- Built on [clj-reload](https://github.com/tonsky/clj-reload) 0.7.1
- Clojure 1.12.0
- core.async 1.6.681 for channel-based notifications

[0.1.0]: https://github.com/hive-agi/hive-hot/releases/tag/v0.1.0
