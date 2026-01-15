# hive-hot

Hot-reload module for Clojure - figwheel/shadow-cljs for backend.

## Status

**v0.1.0** - Core registry + reload!

## Overview

`hive-hot` provides unified hot-reload for Clojure backend development, built on [tonsky/clj-reload](https://github.com/tonsky/clj-reload):

- **Built on clj-reload** - Leverages battle-tested reload mechanics
- **Component registry** - Named components with lifecycle callbacks
- **Event-driven listeners** - Integrates with [hive-events](https://github.com/hive-agi/hive-events)
- **MCP-accessible** - Coordinator/lings can trigger reloads externally
- **Observable** - Channel notifications on reload status

## Installation

```clojure
;; deps.edn
{:deps {io.github.hive-agi/hive-hot {:git/tag "v0.1.0" :git/sha "..."}}}
```

## Quick Start

```clojure
(require '[hive-hot.core :as hot])

;; Register a component for hot-reload
(hot/reg-hot :my-service
  {:ns 'my.service.core
   :on-reload (fn [] (println "Service reloaded!"))
   :on-error (fn [e] (println "Reload failed:" e))})

;; Trigger reload (also reloads dependents)
(hot/reload!)

;; Refresh all namespaces
(hot/reload-all!)
```

## Usage Examples

### Event Listeners

Subscribe to reload events for logging, metrics, or integration:

```clojure
;; Add a listener for reload events
(hot/add-listener! :my-logger
  (fn [event]
    (case (:type event)
      :reload-start   (println "Reload starting...")
      :reload-success (println "Reloaded" (count (:loaded event)) "namespaces in" (:ms event) "ms")
      :reload-error   (println "Reload failed:" (:failed event))
      :component-callback (println "Component" (:component event) "callback:" (:callback event))
      nil)))

;; Remove when done
(hot/remove-listener! :my-logger)
```

### Component Lifecycle

Register components that need callbacks after reload:

```clojure
;; HTTP server that needs restart notification
(hot/reg-hot :http-server
  {:ns 'my.server.core
   :on-reload (fn []
                (println "Server code changed!")
                ;; Could restart server here if needed
                )
   :on-error (fn [e]
               (println "Server reload failed:" (.getMessage e)))})

;; Database connection pool
(hot/reg-hot :db-pool
  {:ns 'my.db.pool
   :on-reload #(println "DB pool namespace reloaded")})

;; List registered components
(hot/list-components)
;; => (:http-server :db-pool)

;; Get component details
(hot/get-component :http-server)
;; => {:ns my.server.core, :on-reload #fn, :on-error #fn, :status :idle, :last-reload #inst...}
```

### Status Introspection

Check the current state of the hot-reload system:

```clojure
(hot/status)
;; => {:initialized? true
;;     :components {:http-server {...} :db-pool {...}}
;;     :listener-count 1}
```

### Development Workflow

Use `with-reload` for interactive development:

```clojure
;; Make a change and reload in one step
(hot/with-reload
  (spit "src/my/service.clj"
        (slurp "src/my/service.clj")))

;; Or just reload after external edits
(hot/reload!)
```

### Initialization Options

Configure source directories and exclusions:

```clojure
;; Custom initialization
(hot/init! {:dirs ["src" "dev"]
            :no-reload '#{user}           ; Never reload these
            :no-unload '#{my.stateful}})  ; Reload but don't unload

;; Find namespaces by pattern
(hot/find-namespaces #".*-test")
;; => (my.service-test my.db-test ...)
```

## API Reference

### Initialization

| Function | Signature | Description |
|----------|-----------|-------------|
| `init!` | `([] [opts])` | Initialize clj-reload. Options: `:dirs`, `:no-reload`, `:no-unload` |

### Component Registry

| Function | Signature | Description |
|----------|-----------|-------------|
| `reg-hot` | `[id opts]` | Register component. Opts: `:ns` (required), `:on-reload`, `:on-error` |
| `unreg-hot` | `[id]` | Unregister component |
| `get-component` | `[id]` | Get component map by ID |
| `list-components` | `[]` | List all registered component IDs |

### Reload Operations

| Function | Signature | Description |
|----------|-----------|-------------|
| `reload!` | `([] [opts])` | Reload changed namespaces. Opts: `:throw`, `:only` |
| `reload-all!` | `[]` | Force reload all namespaces |

### Event Listeners

| Function | Signature | Description |
|----------|-----------|-------------|
| `add-listener!` | `[id fn]` | Add reload event listener |
| `remove-listener!` | `[id]` | Remove listener by ID |

### Introspection

| Function | Signature | Description |
|----------|-----------|-------------|
| `status` | `[]` | Get system status map |
| `reset!` | `[]` | Clear all registrations (for tests) |
| `find-namespaces` | `[pattern]` | Find namespaces matching regex |

### Macros

| Macro | Description |
|-------|-------------|
| `with-reload` | Execute body, then call `reload!` |

## Architecture

```
File Change -> Event -> Dependency Graph -> Ordered Reload -> Notify
     |           |           |                |            |
  watcher   :hot/file   tools.namespace   require      channel
            -changed                      :reload      publish
```

## Development

```bash
# Start REPL with dev dependencies
clj -M:dev:nrepl

# Run tests
clj -X:test

# REPL workflow
(require '[hive-hot.core :as hot])
(hot/init! {:dirs ["src" "test"]})
(hot/reload!)
```

## Roadmap

- [x] **v0.1.0** - Core registry + reload! + reload-all!
- [ ] **v0.2.0** - File watcher integration
- [ ] **v0.3.0** - MCP tools (hot_reload, hot_status)
- [ ] **v0.4.0** - hive-events effects integration

## Part of hive-agi

- [hive-mcp](https://github.com/hive-agi/hive-mcp) - Core MCP server
- [hive-events](https://github.com/hive-agi/hive-events) - Event system
- **hive-hot** - Hot-reload module (this repo)

## License

MIT
