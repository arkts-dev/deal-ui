"use strict";
// Actual compiler-issued entry; no mocks of store/accounting policy.
const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('assert');
const root=path.resolve(__dirname,'../../..');
const base=path.resolve(process.argv[2]);
const host=path.resolve(process.argv[3]);
const factories={};
function walk(dir){for(const name of fs.readdirSync(dir)){const file=path.join(dir,name);if(fs.statSync(file).isDirectory())walk(file);else if(name.endsWith('.js'))factories[path.relative(base,file).split(path.sep).join('/')]=new Function('require','module','exports','process','console',fs.readFileSync(file,'utf8'));}}
walk(base);
vm.runInThisContext(fs.readFileSync(path.join(host,'sandbox.js'),'utf8'));
vm.runInThisContext(fs.readFileSync(path.join(root,'runtime-js/session.js'),'utf8'));
vm.runInThisContext(fs.readFileSync(path.join(host,'ui-session.js'),'utf8'));
configureDealCapabilities([{module:'host/work',functions:[{name:'run',parameters:[{name:'value',type:{kind:'int'}}],result:{kind:'int'}}]}]);
const entryId='experience_entry.js';
function mount(){mountDealUi(factories,entryId);return dealUi;}
function snapshot(session){return JSON.parse(session.snapshot());}
function slot(session,text){return snapshot(session).tree.children.find(n=>n.props.some(p=>p.name==='text'&&p.stringValue===text)).props.find(p=>p.name==='onClick').actionSlot;}
const tick=()=>new Promise(resolve=>setImmediate(resolve));
(async()=>{
 const session=mount();
 session.dispatch(slot(session,'Start 1'));session.dispatch(slot(session,'Start 2'));
 assert.throws(()=>session.state(),/Wait for current work/);
 const req=JSON.parse(dealCapabilities.take());assert.equal(req.length,2);
 dealCapabilities.deliver([{id:req[1].id,ok:true,value:2}]);await tick();
 assert.throws(()=>session.state(),/Wait for current work/);
 dealCapabilities.deliver([{id:req[0].id,ok:true,value:1}]);await tick();
 assert.equal(JSON.parse(session.state()).value,21,'Completions must commit in arrival order');assert.equal(snapshot(session).version,5);
 const before=snapshot(session);
 session.dispatch(slot(session,'Bad'));const rejected=snapshot(session);
 assert.deepStrictEqual(rejected.tree,before.tree);assert.equal(rejected.version,before.version);assert.notEqual(rejected.fault,'','Update fault must be published without changing state/version');
 session.dispatch(slot(session,'Start 1'));const failureReq=JSON.parse(dealCapabilities.take())[0];const started=snapshot(session);
 dealCapabilities.deliver([{id:failureReq.id,ok:false,error:{code:'FAILED',message:'Injected effect failure'}}]);await tick();
 assert.equal(snapshot(session).version,started.version+1);assert.equal(snapshot(session).fault,'Injected effect failure');assert.equal(JSON.parse(session.state()).value,21);
 session.dispatch(slot(session,'Start 1'));const late=JSON.parse(dealCapabilities.take())[0];session.dispose();const disposed=snapshot(session);
 dealCapabilities.deliver([{id:late.id,ok:true,value:9}]);await tick();assert.deepStrictEqual(snapshot(session),disposed);assert.equal(JSON.parse(session.state()).value,21);
 session.dispatch(slot(session,'Start 1'));assert.equal(JSON.parse(dealCapabilities.take()).length,0);
 // Reentrant completion is enqueued, not recursively drained. Scheduler also invokes
 // completion before physical exit, so readiness remains false in that interval.
 const {entry,rt}=dealLoad(factories,entryId);let readinessBlocked=false,reentrant,savedCompletion;
 reentrant=createDealUiSession(entry,rt,null,(invoke,complete,fail,exited)=>{
  if (savedCompletion) {
   const version=snapshot(reentrant).version;
   complete(savedCompletion);
   assert.equal(snapshot(reentrant).version,version,'Nested completion must enqueue without recursive drain');
   exited();return;
  }
  assert.equal(snapshot(reentrant).version,2,'Effect must start after commit');
  Promise.resolve(invoke()).then(value=>{savedCompletion=value;complete(value);assert.throws(()=>reentrant.state(),/Wait/);readinessBlocked=true;}).catch(fail).finally(exited);
 });
 reentrant.dispatch(slot(reentrant,'Start 1'));const r=JSON.parse(dealCapabilities.take())[0];dealCapabilities.deliver([{id:r.id,ok:true,value:3}]);await tick();assert(readinessBlocked);assert.equal(JSON.parse(reentrant.state()).value,3);
 reentrant.dispatch(slot(reentrant,'Start 1'));assert.equal(JSON.parse(reentrant.state()).value,33);assert.equal(snapshot(reentrant).version,5);reentrant.dispose();
 const failedEntry={...entry,bridgeEffect0:{$kind:'function',$f:()=>{throw Error('Synchronous adapter failure')}}};
 let syncSession=createDealUiSession(failedEntry,rt,null,(invoke,complete,fail,exited)=>{try{invoke();}catch(error){fail(error);}finally{exited();}});
 syncSession.dispatch(slot(syncSession,'Start 1'));assert.equal(snapshot(syncSession).version,3);assert.equal(snapshot(syncSession).fault,'Error: Synchronous adapter failure');assert.doesNotThrow(()=>syncSession.state());syncSession.dispose();
 assert.throws(()=>entry.bridgeSessionExited.$f(entry.bridgeSessionStatus.$f()),error=>error.code==='UI3005');
 const restored=createDealUiSession(entry,rt,{value:21},()=>{throw Error('Unexpected effect')});assert.equal(JSON.parse(restored.state()).value,21);assert.throws(()=>restored.dispatch(99999),/Invalid or stale/);restored.dispose();
 console.log('Deal UI JS lifecycle: overlap/arrival order, atomic rejection, effect failure/version, disposal/late completion, physical exit and replacement readiness');
})().catch(error=>{console.error(error);process.exitCode=1});
