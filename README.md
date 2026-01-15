# hive-hot

Hot-reload module for Clojure - figwheel/shadow-cljs for backend.

## Status

**v0.1.0-SNAPSHOT** - Core registry + reload!

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
   :deps ['my.service.db 'my.service.cache]
   :on-reload (fn [] (println "Service reloaded!"))
   :on-error (fn [e] (println "Reload failed:" e))})

;; Trigger reload (also reloads dependents)
(hot/reload! :my-service)

;; Refresh all changed namespaces
(hot/refresh!)
```

## Architecture

```
File Change → Event → Dependency Graph → Ordered Reload → Notify
     ↓           ↓           ↓                ↓            ↓
  watcher   :hot/file   tools.namespace   require      channel
            -changed                      :reload      publish
```

## Roadmap

- [x] **v0.1.0** - Core registry + reload! + refresh!
- [ ] **v0.2.0** - File watcher integration
- [ ] **v0.3.0** - MCP tools (hot_reload, hot_status)
- [ ] **v0.4.0** - hive-events effects integration

## Part of hive-agi

- [hive-mcp](https://github.com/hive-agi/hive-mcp) - Core MCP server
- [hive-events](https://github.com/hive-agi/hive-events) - Event system
- **hive-hot** - Hot-reload module (this repo)

## License

MIT
