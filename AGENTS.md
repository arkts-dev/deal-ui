# Deal UI Compiler Repository Guidance

## Product Boundary

This repository owns the platform-neutral Deal UI transpiler and semantic edit frontend over the
shared DEAL compiler protocol. It must not depend on Android, Compose, DEAL Studio,
streaming-compiler, LLM providers, prompts or concrete generated-app scenarios. Native renderers
consume checked portable output; they do not reproduce compiler rules.

Deal UI never contacts a model or owns generation/repair rounds. It exposes deterministic inspect,
edit, compile and diagnostic facts. Streaming-compiler converts those facts into LLM-facing tools
and manages streaming generation or iterative modernization.

## Canonical Source And Editing

`.dealui` source is authoritative. The checked UI graph is an ephemeral compiler workspace rebuilt
from source, AppInterface and component-pack bytes for every stateless request.

- Document, view and node ids are compiler-owned, opaque and revision-scoped.
- Source comments, section labels and visible text are never semantic identities.
- Edits target compiler-issued view/node ids and carry the exact base source digest.
- Multi-operation edits validate atomically; failure returns the unchanged canonical source.
- Repair workspaces preserve valid payloads as sealed slots. Directed parent/child dependencies form
  a DAG; mutually dependent operations form one strongly connected group.
- Deal UI framework validation attached to a DEAL candidate runs inside the upstream stage/patch
  transaction, so framework diagnostics cannot fall back to client-side broad repair.
- Greenfield and structural edits query the document before `addView`; view removal targets a
  queried view. These are semantic operations, never text insertion performed by a client.
- Deal UI consumes the versioned AppInterface snapshot emitted by the shared compiler core.

Deal UI remains a compact declarative language. Dynamic collections use `ForEach`; do not add array
indexing, array literals, assignment or arbitrary calls as authoring escapes. Borrowed state/action
immutability, typed children, component capabilities and accessibility are compiler checks.

## Generalization

Do not add application-specific components, validators, repairs or branches. Production behavior may
depend only on Deal UI syntax, typed AppInterface facts, pack contracts, capabilities and resource
bounds. Diagnostics own repair scope; clients may not map diagnostic text to custom fixes.
