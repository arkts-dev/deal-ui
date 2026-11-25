"use strict";
// Deal UI checkpoint transport; descriptors/layouts come only from the compiled inventory.
// Unsupported types reject. This does not persist effect closures or resume pending work.
globalThis.createDealUiCheckpoint=function(rt,schema,digest){
 if(!schema || !Array.isArray(schema.elements) || !Array.isArray(schema.records) || !schema.classes)throw Error("Missing compiled checkpoint inventory");
 const classes=schema.classes,elements=schema.elements,records=schema.records;
 function recordType(value){
  const matches=[];
  for(const d of records){try{rt.checkType(d,value);matches.push(d);}catch(e){if(!e.code)throw e;}}
  if(matches.length!==1)throw Error('Record descriptor unavailable or ambiguous: '+matches);
  return matches[0];
 }
 function elementType(array){
  const matches=[];
  for(const d of elements){try{rt.checkArray('['+d+']',array);matches.push(d);}catch(e){if(!e.code)throw e;}}
  if(matches.length!==1)throw Error('Array descriptor unavailable or ambiguous: '+matches);
  return matches[0];
 }
 function fields(value){
  const id=value.$classname,plan=Object.hasOwn(classes,id)?classes[id]:null;
  if(!plan || value.$kind!=='class')throw Error('Unsupported nominal value');
  rt.checkType(id,value);
  return [id,plan];
 }
 function encode(root){
  const ids=new Map();
  function visit(v){
   if(v===null)return ['null'];if(v===undefined)return ['undefined'];
   if(typeof v==='number')return Number.isNaN(v)?['nan']:v===Infinity?['positiveInfinity']:v===-Infinity?['negativeInfinity']:Object.is(v,-0)?['negativeZero']:['number',String(v)];
   if(typeof v==='string')return ['string',v];if(typeof v==='boolean')return ['boolean',v];
   if(typeof v!=='object'||v.$kind==='function')throw Error('Unsupported leaf');
   if(ids.has(v))return ['ref',ids.get(v)];
   const id=ids.size;ids.set(v,id);
   if(Array.isArray(v))return ['array',id,elementType(v),Array.from(v,visit)];
   if(v instanceof Map)return ['record',id,recordType(v),rt.objectKeys(v).map(k=>[k,visit(v.get(k))])];
   const [nominal,plan]=fields(v);
   return ['class',id,nominal,plan.filter(f=>rt.has(v,f.name)).map(f=>[f.name,visit(v[f.name])])];
  }
  return {schemaDigest:digest,value:visit(root)};
 }
 function decode(envelope,descriptor){
  if(!envelope||Object.keys(envelope).length!==2||envelope.schemaDigest!==digest||!Object.hasOwn(envelope,'value'))throw Error('Schema mismatch');
  const nodes=new Map(),definitions=new Map(),active=new Set(),seenWire=new Set();
  const arity={null:1,undefined:1,nan:1,positiveInfinity:1,negativeInfinity:1,negativeZero:1,number:2,string:2,boolean:2,ref:2,array:4,record:4,class:4};
  function scan(e){
   if(!Array.isArray(e)||seenWire.has(e)||active.has(e)||!Object.hasOwn(arity,e[0])||e.length!==arity[e[0]])throw Error('Invalid wire tag/cycle');
   active.add(e);seenWire.add(e);const [tag,id]=e;
   if(tag==='ref'){if(!Number.isSafeInteger(id)||!nodes.has(id))throw Error('Invalid reference');}
   else if(tag==='number'){if(typeof id!=='string'||!Number.isFinite(Number(id))||Object.is(Number(id),-0)||String(Number(id))!==id)throw Error('Invalid decimal');}
   else if(tag==='string'||tag==='boolean'){if(typeof id!==tag)throw Error('Invalid scalar');}
   else if(['array','class','record'].includes(tag)){
    if(!Number.isSafeInteger(id)||id!==nodes.size)throw Error('Invalid identity');
    let node;
    if(tag==='array'){if(!elements.includes(e[2]))throw Error('Unknown element descriptor');node=rt.tagArray([],e[2]);}
    else if(tag==='record'){if(!records.includes(e[2]))throw Error('Unknown Record descriptor');node=rt.makeTable({},e[2]==='record[string]'?'string':'unknown');}
    else {const plan=Object.hasOwn(classes,e[2])?classes[e[2]]:null;if(!plan)throw Error('Unknown nominal identity');node={};rt.setProp(node,'$kind','class');rt.setProp(node,'$classname',e[2]);rt.registerInstancePlan(node,plan);rt.registerClassFields(node,plan.map(f=>f.name));}
    nodes.set(id,node);definitions.set(id,e);
    const entries=e[3];if(!Array.isArray(entries))throw Error('Invalid entries');
    if(tag==='array')for(const child of entries)scan(child);
    else {const names=new Set();for(const pair of entries){if(!Array.isArray(pair)||pair.length!==2||typeof pair[0]!=='string'||names.has(pair[0]))throw Error('Duplicate/invalid field');names.add(pair[0]);scan(pair[1]);}
     if(tag==='class'){let i=0;for(const f of classes[e[2]]){if(entries[i]?.[0]===f.name)i++;else if(!f.optional)throw Error('Missing field');}if(i!==entries.length)throw Error('Extra or unordered field');}
     else {const order=rt.objectKeys(new Map(entries));if(!entries.every((p,i)=>p[0]===order[i]))throw Error('Unordered Record');}
    }
   }
   active.delete(e);
  }
  scan(envelope.value);
  function value(e){switch(e[0]){case 'null':return null;case 'undefined':return undefined;case 'nan':return NaN;case 'positiveInfinity':return Infinity;case 'negativeInfinity':return -Infinity;case 'negativeZero':return -0;case 'number':return Number(e[1]);case 'string':case 'boolean':return e[1];default:return nodes.get(e[1]);}}
  for(const [id,e]of definitions){const node=nodes.get(id);if(e[0]==='array')for(const child of e[3])node.push(value(child));else for(const [k,v]of e[3]){if(e[0]==='record')node.set(k,value(v));else rt.setProp(node,k,value(v));}}
  for(const [id,e]of definitions){const node=nodes.get(id);if(e[0]==='class')rt.checkType(e[2],node);else if(e[0]==='array')rt.checkArray('['+e[2]+']',node);else rt.checkType(e[2],node);}
  return rt.checkType(descriptor,value(envelope.value));
 }
 return {encode,decode,elementType};
};
