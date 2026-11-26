"use strict";
// Deal UI JS runtime. Host supplies module loading and effect scheduling only.
globalThis.createDealUiSession = function(entry, rt, restoredState, scheduleEffect, checkpointSchema) {
  const call = (name, ...args) => {
    const fn = entry[name];
    if (!fn || fn.$kind !== 'function') throw Error('Missing checked UI export: ' + name);
    return fn.$f(...args);
  };
  let state = call('bridgeInitialState');
  const schemaDigest=call('bridgeSchemaDigest');
  const codec = createDealUiCheckpoint(rt,checkpointSchema,schemaDigest);
  if (restoredState !== null) state = codec.decode(restoredState,entry.bridgeInitialState.$sig.substring(4));
  let store = call('initialStore');
  let current = call('initial', state, store);
  store = current.store;
  let status = call('bridgeSessionStatus');
  let nextBinding = 1000000;
  let bindings = new Map();
  const effects = new Set();
  function finishEffect(effect) {
    if (effect.finished) return;
    effect.finished = true; effects.delete(effect);
    status = call('bridgeSessionExited', status);
  }
  function suppressEffect(effect, interrupt) {
    effect.suppressed = true;
    if (!effect.started) { if (effect.timer !== null) clearTimeout(effect.timer); finishEffect(effect); }
    else if (interrupt && typeof effect.cancel === 'function') effect.cancel();
  }
  function applyEffectCommand(transition, action) {
    const command = call('effectCommand', state, action);
    if (!['none','start','cancel'].includes(command.operation) || !['none','replace','interrupt'].includes(command.cancellationMode)
        || !Number.isSafeInteger(command.delayMillis) || command.delayMillis < 0
        || ((command.operation === 'cancel' || command.cancellationMode !== 'none') && !command.key)) throw Error('Invalid effect command');
    const cancel = () => { for (const effect of [...effects]) if (effect.key === command.key) suppressEffect(effect, command.cancellationMode === 'interrupt'); };
    if (command.operation === 'cancel' || command.operation === 'none' && command.key) { cancel(); return; }
    if (command.operation === 'none' || transition.effect.effectId < 0) return;
    if (command.key && command.cancellationMode !== 'none') cancel();
    const effect = {key: command.key, started: false, suppressed: false, finished: false, timer: null, cancel: null};
    effects.add(effect); status = call('bridgeSessionStarted', status);
    const snapshot = state;
    const start = () => {
      if (effect.suppressed || store.lifecycle.disposed) { finishEffect(effect); return; }
      effect.started = true;
      try {
        effect.cancel = scheduleEffect(
          () => call('bridgeEffect' + transition.effect.effectId, snapshot, action),
          result => { if (!effect.suppressed && !store.lifecycle.disposed) enqueue(result, true); },
          error => { if (!effect.suppressed && !store.lifecycle.disposed) status = call('bridgeSessionFailed', status, rt.reifyError(error).message || String(error), true); },
          () => finishEffect(effect));
      } catch (error) {
        try { if (!effect.suppressed && !store.lifecycle.disposed) status = call('bridgeSessionFailed', status, rt.reifyError(error).message || String(error), true); }
        finally { finishEffect(effect); }
      }
    };
    try { if (command.delayMillis > 0) effect.timer = setTimeout(start, command.delayMillis); else start(); }
    catch (error) { finishEffect(effect); throw error; }
  }
  function publishedTree() {
    const next = new Map();
    function visit(node) {
      return {...node, props: node.props.map(prop => {
        const {actionBindings, ...fields} = prop;
        const display = {...fields, actionBindings: {}};
        if (prop.kind === 'action' && actionBindings && actionBindings.size) {
          if (nextBinding >= 2147483647) throw Error('Action binding identity exhausted');
          const handle = nextBinding++;
          next.set(handle, {slot: prop.actionSlot, values: actionBindings});
          return {...display, actionSlot: handle};
        }
        return display;
      }), children: node.children.map(visit)};
    }
    const tree = visit(current.tree); bindings = next; return tree;
  }
  let displayTree = publishedTree();
  function visibleSlot(node, slot) {
    return node.props.some(prop => prop.kind === 'action' && prop.actionSlot === slot) || node.children.some(child => visibleSlot(child, slot));
  }
  function enqueue(action, completion = false) {
    const queued = call(completion ? 'complete' : 'enqueue', store, action);
    store = queued.store;
    if (queued.accepted && queued.startDrain) drain();
  }
  function drain() {
    try {
      while (!store.lifecycle.disposed) {
        const next = call('dequeue', store);
        store = next.store;
        if (!next.present) break;
        try {
          const candidate = call('nextState', state, next.action);
          const transition = call('transitionFromCandidate', candidate, current.tree, store, next.action);
          state = candidate; current = transition; store = transition.store;
          displayTree = publishedTree();
          status = call('bridgeSessionCommitted', status);
          applyEffectCommand(transition, next.action);
        } catch (error) { store = call('reject', store); status = call('bridgeSessionFailed', status, String(error), false); }
      }
    } finally { store = call('finish', store); }
  }
  return Object.freeze({
    snapshot: () => JSON.stringify({tree: displayTree, version: status.version, fault: status.fault}),
    state: () => { if (!call('bridgeReplacementReady', status)) throw Error('Wait for current work before replacing the experience'); return JSON.stringify(state); },
    checkpoint: () => call('bridgeReplacementReady', status) ? JSON.stringify(codec.encode(state)) : null,
    dispatch: (slot, payload = null) => {
      if (!Number.isInteger(slot) || slot < 0 || !visibleSlot(displayTree, slot)) throw Error('Invalid or stale action slot');
      // Payload type comes from the checked action signature, never from the caller.
      const binding = bindings.get(slot);
      const factory = binding ? binding.slot : slot;
      const values = binding ? binding.values : new Map();
      const declared = call('payloadType_' + factory);
      let value = payload;
      if (declared === 'number') { if (typeof payload !== 'string') throw Error('Expected a numeric payload'); value = call('bridgePayloadNumber', payload); }
      else if (declared === 'boolean') { if (payload !== 'true' && payload !== 'false') throw Error('Expected a boolean payload'); value = payload === 'true'; }
      else if (declared === 'string') { if (typeof payload !== 'string') throw Error('Expected a string payload'); }
      else if (declared === 'json') { if (typeof payload !== 'string') throw Error('Expected structured JSON payload'); value = call('decodePayload_' + factory, payload); }
      else if (payload !== null) throw Error('This action takes no payload');
      enqueue(payload === null && declared === 'none' ? call('action_' + factory, values) : call('action_' + factory, values, value));
      return '';
    },
    dispose: () => { store = call('dispose', store); for (const effect of [...effects]) suppressEffect(effect, true); return ''; }
  });
};
