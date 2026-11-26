"use strict";
const fs=require('fs'),vm=require('vm'),assert=require('node:assert/strict');
const file=process.argv[2],entry=require(file),rt=require(file.replace('entry.js','deal/runtime.js')),schema=require(file.replace('entry.js','checkpoint-schema.js'));
vm.runInThisContext(fs.readFileSync('runtime-js/checkpoint.js','utf8'));
vm.runInThisContext(fs.readFileSync('runtime-js/session.js','utf8'));
(async()=>{
 let exited,settled;const ready=new Promise(resolve=>settled=resolve);
 const s=createDealUiSession(entry,rt,null,(invoke,complete,fail,exit)=>{
   exited=exit;Promise.resolve().then(invoke).then(complete).catch(error=>{fail(error);settled();});
 },schema);
 s.dispatch(0);await ready;assert.equal(s.checkpoint(),null,'failure completion does not imply physical exit');exited();
 assert.equal(JSON.parse(s.state()).n,99);assert.equal(JSON.parse(s.snapshot()).fault,'');assert.equal(JSON.parse(s.snapshot()).version,3);s.dispose();
 let callback;const late=createDealUiSession(entry,rt,null,(invoke,complete,fail,exit)=>{callback={fail,exit};},schema);
 late.dispatch(0);late.dispose();callback.fail(Error('late'));callback.exit();assert.equal(JSON.parse(late.state()).n,1);
 const broken={...entry,bridgeEffectFailure0:{$kind:'function',$f:()=>{throw Error('mapper failure');}}};
 const sync=createDealUiSession(broken,rt,null,(invoke,complete,fail,exit)=>{try{throw Error('effect failure');}catch(error){fail(error);}finally{exit();}},schema);
 sync.dispatch(0);assert.equal(JSON.parse(sync.state()).n,1);assert.match(JSON.parse(sync.snapshot()).fault,/mapper failure/);sync.dispose();
 console.log('UiJsFailureActionTest: generated mapper completion, physical exit, disposal and mapper failure');
})().catch(error=>{console.error(error);process.exitCode=1;});
