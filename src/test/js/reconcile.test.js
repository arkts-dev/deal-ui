'use strict';
// Differential oracle is intentionally the simple pre-index algorithm, not a
// second production path. Invoke compiled DEAL through its checked ABI.
const assert = require('node:assert/strict');
const {performance} = require('node:perf_hooks');
const app = require(require('node:path').resolve(process.argv[2]));
const node = (key, children=[], component='Text', structural='row', kind='string') => ({component,structural,keyKind:kind,keyNumber:kind==='number'?key:0,keyString:kind==='string'?key:'',props:[],children});
const root = children => node('',children,'Column','root','none');
const same = (a,b) => a.structural===b.structural && a.keyKind===b.keyKind && (a.keyKind==='number'?a.keyNumber===b.keyNumber:a.keyKind==='string'?a.keyString===b.keyString:true);
function oracle(previous,next) {
  const patches=[];
  function unique(nodes) {for(let i=0;i<nodes.length;i++){for(let j=i+1;j<nodes.length;j++)if(same(nodes[i],nodes[j]))throw Error('UI3003');unique(nodes[i].children);}}
  function add(kind,n,p,rootParent,index){patches.push({kind,structural:n.structural,keyKind:n.keyKind,keyNumber:n.keyNumber,keyString:n.keyString,parentStructural:p.structural,parentKeyKind:p.keyKind,parentKeyNumber:p.keyNumber,parentKeyString:p.keyString,rootParent,index,node:n});}
  function children(old,next,parent){for(const a of old)if(!next.some(b=>same(a,b)&&a.component===b.component))add('dispose',a,parent,false,0);next.forEach((n,i)=>{let retained=false;for(const a of old)if(same(a,n)&&a.component===n.component){retained=true;add('update',n,parent,false,i);children(a.children,n.children,n);}if(!retained)add('create',n,parent,false,i);});}
  unique(next.children);
  const p=root([]);p.structural='';
  if(!previous)add('create',next,p,true,0);
  else if(!same(previous,next)||previous.component!==next.component){add('dispose',previous,p,true,0);add('create',next,p,true,0);}
  else{add('update',next,p,true,0);children(previous.children,next.children,next);}
  return {patches};
}
function run(previous,next){return JSON.parse(app.run.$f(JSON.stringify({previous,next})));}
function check(previous,next){assert.deepEqual(run(previous,next),oracle(previous,next));}
check(null,root([node('a')]));
check(root([node('a'),node('b'),node('c')]),root([node('c'),node('b',[],'Button'),node('d')]));
check(root([node('a',[node('nested')])]),root([node('a',[node('new')])]));
check(root([]),node('different',[],'Text','other'));
check(root([node('__proto__'),node('constructor'),node('a\u0000b'),node('😀')]),root([node('😀'),node('a\u0000b'),node('__proto__')]));
check(root([node('x',[],'Text','a'),node('x',[],'Text','b'),node(1,[],'Text','row','number')]),root([node('x',[],'Text','b'),node('1'),node(1,[],'Text','row','number')]));
// Previous duplicate nodes retain old traversal behavior; only next is rejected.
check(root([node('a'),node('a')]),root([node('a')]));
for(const next of [root([node('a'),node('a')]),root([node('p',[node('a'),node('a')])])])assert.throws(()=>run(null,next),error=>error.name==='UI3003');
assert.throws(()=>app.numericKeys.$f('zero'),error=>error.name==='UI3003');
assert.equal(JSON.parse(app.numericKeys.$f('nan')).patches.length,5);
assert.equal(JSON.parse(app.numericKeys.$f('infinity')).patches.length,3);
assert.throws(()=>app.run.$f('{"previous":null,"next":{"component":42}}'),error=>error.code==='E8001'||error.name==='E8001','typed ingress remains checked');
let seed=12345;function rand(n){seed=(1664525*seed+1013904223)>>>0;return seed%n;}
for(let trial=0;trial<100;trial++){
 const old=[],next=[];
 for(let i=0;i<12;i++){if(rand(3))old.push(node('k'+i,[node('nested'+rand(3))]));if(rand(3))next.push(node('k'+i,[node('nested'+rand(3))],rand(4)?'Text':'Button'));}
 for(let i=next.length-1;i>0;i--){const j=rand(i+1);[next[i],next[j]]=[next[j],next[i]];}
 check(root(old),root(next));
}
const samples=[];
for(const size of [32,128,256]){
 const previous=root(Array.from({length:size},(_,i)=>node('k'+i)));
 const next=root([...previous.children].reverse());
 const input=JSON.stringify({previous,next});
 app.run.$f(input);
 const times=[];
 for(let i=0;i<5;i++){const start=performance.now();app.run.$f(input);times.push(performance.now()-start);}
 times.sort((a,b)=>a-b);samples.push({siblings:size,medianMs:times[2]});
}
console.log('reconcile: indexed matching, ordered differential patches, nested duplicates, numeric equality and checked ingress passed');
console.log(JSON.stringify({samples}));
