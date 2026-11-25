# DEAL UI Framework Design

## Grammar

Core DEAL applies.

```antlr
DirectiveName ::=
    'jsonable'
  | 'ui-root' | 'ui-update' | 'ui-effect'

DealuiProgram ::= ImportDeclaration* ViewDeclaration+

ViewDeclaration ::=
    'export'? 'view' Identifier
    '(' ViewParameterList? ')' ':' 'View' ViewBody

ViewParameterList ::= ViewParameter (',' ViewParameter)* ','?
ViewParameter ::= Identifier ':' UiType
UiType ::= Type | 'View' | 'Action'

ViewBody ::= '{' ViewNode* '}'

ViewNode ::=
    UiCall ';'?
  | WhenNode
  | ForEachNode

UiCall ::= QualifiedName '(' NamedArgumentList? ')' ViewBody?
QualifiedName ::= Identifier ('.' Identifier)*

NamedArgumentList ::= NamedArgument (',' NamedArgument)* ','?
NamedArgument ::= Identifier ':' UiArgument
UiArgument ::= UiExpr | ActionLiteral

UiExpr ::= UiLogicalOr
UiLogicalOr ::= UiLogicalAnd ('||' UiLogicalAnd)*
UiLogicalAnd ::= UiEquality ('&&' UiEquality)*
UiEquality ::= UiRelational (('===' | '!==') UiRelational)?
UiRelational ::= UiAdditive (('<' | '<=' | '>' | '>=') UiAdditive)?
UiAdditive ::= UiMultiplicative (('+' | '-') UiMultiplicative)*
UiMultiplicative ::= UiUnary (('*' | '/') UiUnary)*
UiUnary ::= ('!' | '-') UiUnary | UiPrimary

UiPrimary ::=
    UiLiteral
  | UiPath
  | HasExpr
  | '(' UiExpr ')'

UiLiteral ::=
    NullLiteral
  | BooleanLiteral
  | NumberLiteral
  | StringLiteral

UiPath ::= Identifier ('.' Identifier)*
HasExpr ::= 'has' '(' UiPath ')' // view presence operator; lowers to Object.hasOwn(receiver, "field")

ActionLiteral ::= 'action' QualifiedName '{' ActionFieldList? '}'
ActionFieldList ::= ActionField (',' ActionField)* ','?
ActionField ::= Identifier ':' UiExpr

WhenNode ::= 'When' '(' UiExpr ')' ViewBody ('Else' ViewBody)?

ForEachNode ::=
    'ForEach' '('
      UiPath ','
      Identifier ':' Type ','
      'key' ':' UiPath
    ')' ViewBody

UiPackProgram ::= ImportDeclaration* PackVersion? UiPackDeclaration*

PackVersion ::= 'pack' 'version' StringLiteral ';'

UiPackDeclaration ::=
    UiPackClassDeclaration
  | ComponentDeclaration
  | TokenDeclaration

UiPackClassDeclaration ::= 'export' 'class' Identifier '{' UiPackClassField* '}'
UiPackClassField ::= Identifier '?'? ':' UiPackType ('=' UiPackDefault)? ';'?
UiPackType ::= Type | 'Action'
UiPackDefault ::=
    UiLiteral
  | '-' NumberLiteral
  | QualifiedName
  | '[' UiPackDefaultList? ']'
  | '{' UiPackDefaultFields? '}'
UiPackDefaultList ::= UiPackDefault (',' UiPackDefault)* ','?
UiPackDefaultFields ::= UiPackDefaultField (',' UiPackDefaultField)* ','?
UiPackDefaultField ::= Identifier ':' UiPackDefault

ComponentDeclaration ::=
    'export' 'component' Identifier
    '(' 'props' ':' Type ')' ':' 'View'
    ComponentContract? ';'?

ComponentContract ::= '{' ComponentContractItem* '}'

ComponentContractItem ::=
    'children' ChildRequirement? QualifiedName? ';'
  | 'event' Identifier EventPayload? ';'
  | 'accessibility' Identifier ';'
  | 'token' Identifier ';'
  | 'capability' StringLiteral ';'

ChildRequirement ::= 'required' | 'optional'
EventPayload ::= '(' 'payload' ':' Type ')'
TokenDeclaration ::= 'export' 'token' Identifier ':' Type ';'
```

Quoted terminals are contextual.

## Semantics

- Imports lead. One exported `@ui-root` selects state. Named calls resolve exactly.
- Views are pure. Paths start at parameters, items, tokens, actions, or payloads; nullable traversal fails.
- Type projection preserves nullability at every array depth, independently of readonly qualifiers: `(T | null)[]` is not `T[] | null`. View/pack annotations, imported DEAL types, checked contracts and generated signatures retain that distinction. Writable arrays require exact element types; readonly views may widen element nullability without changing storage.
- Event `ActionLiteral` binds declared `payload`.
- Nominal actions are checked statically and dynamically. `When` takes `boolean`; `ForEach` takes `T[]` and a unique `number|string` key.
- `View` is only a view/component result; `Action` is only a forwarded view parameter or event prop. Children follow contracts. A typed child contract accepts that component through direct calls, `When`, `ForEach`, and composed views, and rejects every other child root.
- `UiPackDefault` `QualifiedName` resolves to token.
- `.dealui` has no array literals, indexing, assignment, or arbitrary function calls. Dynamic collection rendering uses `ForEach`; application DEAL prepares transformed or selected data.

```deal
import * as app from "./counter";
import * as ui from "platform/ui";

// @ui-root
export view Counter(state: app.CounterState): View {
  ui.Column(spacing: ui.spaceMd) {
    ui.IntText(value: state.count)
    When(state.loading) {
      ui.Spinner()
    } Else {
      ui.Button(
        text: "Increment",
        accessibilityLabel: "Increment counter",
        onClick: action app.IncrementRequested { }
      )
    }
    ForEach(state.items, item: app.Item, key: item.id) {
      ui.Button(
        text: item.title,
        accessibilityLabel: "Select item",
        onClick: action app.ItemSelected { id: item.id }
      )
    }
  }
}
```

## Handlers

Handlers are `sync (S,A)->S` and `async (S,A)->B`. Every reachable action has one update and at most one effect.

```deal
import * as api from "platform/counter";

export class Item {
  id: number ;
  title: string ;
}

export class ItemSelected {
  id: number ;
}

export class CounterState {
  count: number ;
  loading: boolean ;
  message: string ;
  items: Item[] ;
}

export class IncrementRequested {}

export class IncrementCompleted {
  ok: boolean ;
  value: number ;
  message: string ;
}

// @ui-update
export function selectItem(
  state: CounterState,
  action: ItemSelected
): CounterState {
  return state;
}

// @ui-update
export function beginIncrement(
  state: CounterState,
  action: IncrementRequested
): CounterState {
  return {
    count: state.count,
    loading: true,
    message: "",
    items: state.items
  };
}

// @ui-effect
export async function requestIncrement(
  state: CounterState,
  action: IncrementRequested
): Promise<IncrementCompleted> {
  try {
    let value: number = await api.increment(state.count);
    return { ok: true, value: value, message: "" };
  } catch (e) {
    return { ok: false, value: state.count, message: (e as Error).message };
  }
}

// @ui-update
export function completeIncrement(
  state: CounterState,
  action: IncrementCompleted
): CounterState {
  return {
    count: action.value,
    loading: false,
    message: action.message,
    items: state.items
  };
}
```

Updates return complete candidates without mutation. The state and action parameters of every Deal UI handler are borrowed immutable even though core DEAL remains mutable: direct, indexed, nested, and aliased writes are rejected, as is passing borrowed reference data to a helper that may mutate or escape it. Mutation of fresh local data and read-only helper calls remain valid. Effects start post-commit, await, and return one action; uncaught errors reach the host.

## Components

Pack class defaults below are declarative `.dealui-pack` metadata, not DEAL class field initializers. DEAL v1.6 classes require explicit construction fields; all numeric values use `number` and division is IEEE arithmetic.

```dealui-pack
export class Space {}
export class TextStyle {}

export class TextProps {
  value: string = "";
  style?: TextStyle;
}

export class IntTextProps {
  value: number = 0;
  style?: TextStyle;
}

export class ButtonProps {
  text: string = "";
  onClick?: Action;
  accessibilityLabel?: string;
}

export class ColumnProps {
  spacing?: Space;
}

export class EmptyProps {}

export component Text(props: TextProps): View;
export component IntText(props: IntTextProps): View;
export component Spinner(props: EmptyProps): View;

export component Button(props: ButtonProps): View {
  event onClick;
  accessibility accessibilityLabel;
}

export component Column(props: ColumnProps): View {
  children optional;
}

export component NavigationBar(props: EmptyProps): View {
  children required NavigationItem;
}

export token spaceMd: Space;
```

## JavaScript checkpoints

`UiCheckpointCompiler` compiles JavaScript through the canonical DEAL orchestrator and emits `checkpoint-schema.js` from that same checked IR: nominal field layouts and a closed inventory of persistent array element descriptors. `runtime-js/checkpoint.js` uses existing checked runtime mechanisms to encode/reconstruct those carriers; the DEAL runtime is unchanged. Empty arrays are identified by checked descriptors, not element guessing or private metadata. Descriptors outside the inventory and unsupported leaves reject.

The session and disposable checker share this codec. Restoration allocates a fresh graph, validates identities, references, fields and types before returning it, and preserves aliases/cycles, optional absence, UTF-16 and binary64 special values. Hosts load the inventory alongside compiled modules and supply it to the session; missing inventories are not a compatibility lane. This transport does not establish authoritative-only persistence, pending-work invalidation or permission/currentness policy.

## Portable renderer bridge

The compiler's portable renderer target produces `UiPortableBridge`. It preserves closed typed action dispatch, store policy, effects, reconciliation patches, state snapshots, and the checked component tree without depending on Swing or AWT. Every generated bridge exposes compiler-owned `checkedMetadata()` containing the root state, reachable input and completion actions, used components and capabilities, and imported pack versions and SHA-256 digests. An Android or other native host uses this metadata instead of scanning generated source.

The compiler also registers the framework-owned `host/storage` external module.
Its declaration has the closed asynchronous surface `load(key)`,
`save(key, value)`, and `remove(key)`. Applications call it from the existing
`@ui-effect` mechanism; there is no second callback or effect scheduler. Android
implements the module generically using application-private preferences. The
host sees only string keys and values, while typed state encoding/decoding and
all decisions about when to save or restore remain in Deal.

```bash
deal-ui build application.dealui --renderer portable
```

The portable bridge does not expose renderer objects to `.dealui`, and native event ingress constructs actions only through generated action slots. Platform renderers may retain presentation state, but application state changes continue to pass through DEAL `@ui-update` handlers.

Compiler hosts may request a non-runnable progressive-preview check that permits temporarily unreachable update handlers. The full DEAL source is still parsed and checked, and reachable actions are reported by `checkedMetadata()`. Runnable/final compilation always restores strict reachability; hosts must not rewrite DEAL source to approximate this mode.

## Runtime

- Host supplies initial state; runtime validates it before first render.
- One runtime-owned store exists per mounted root.
- Actions are nominal DEAL class values and are runtime-validated.
- One FIFO action queue exists per store.
- Nested dispatch appends to the queue.
- Update receives current committed state.
- Update returns the complete candidate state.
- Candidate is validated, then atomically committed.
- Failure retains old state and starts no effect.
- Effect starts after commit with post-commit state.
- Effect returns one completion action; expected failures are failure actions.
- Effects may overlap; completions enqueue in arrival order.
- Disposed stores discard completions.
- Every commit eventually produces equivalent root-view output.
- Static identity is structural; repeated identity uses stable keys.
- Lost identity disposes retained resources.
- Renderer application is serialized in its host execution domain.
