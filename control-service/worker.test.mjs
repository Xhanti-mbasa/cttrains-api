import {test} from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync} from 'node:fs';
import worker,{digest,validatePolicy} from './worker.mjs';
function env() {
 const sqlite=new DatabaseSync(':memory:');sqlite.exec(readFileSync(new URL('./schema.sql',import.meta.url),'utf8'));
 let pending=Promise.resolve();
 const wrap=(sql,args=[])=>({bind(...v){return wrap(sql,v)},async first(){return sqlite.prepare(sql).get(...args)||null},async all(){return {results:sqlite.prepare(sql).all(...args)}},async run(){return {meta:{changes:Number(sqlite.prepare(sql).run(...args).changes)}}},sql,args});
 return {PEPPER:'test-secret',ALLOW_MAINTENANCE:'true',DB:{prepare:wrap,async batch(rows){const previous=pending;let release;pending=new Promise(resolve=>{release=resolve});await previous;sqlite.exec('BEGIN');try{const r=[];for(const row of rows)r.push(await row.run());sqlite.exec('COMMIT');return r}catch(e){sqlite.exec('ROLLBACK');throw e}finally{release()}}}};
}
const req=(path,v,token)=>new Request('https://test'+path,{method:v===undefined?'GET':'POST',headers:{'CF-Connecting-IP':'test',...(token?{Authorization:'Bearer '+token}:{})},body:v===undefined?undefined:JSON.stringify(v)});
async function call(e,path,v,token){const r=await worker.fetch(req(path,v,token),e);return {status:r.status,body:await r.json()};}
async function login(e){const code='a'.repeat(48);e.OWNER_DIGEST=await digest(e,code);return (await call(e,'/redeem',{code})).body.token;}
test('invitation is globally single use; testers cannot issue codes; owner revocation is enforced',async()=>{
 const e=env(),owner=await login(e);const codes=(await call(e,'/owner/codes',{},owner)).body.codes;assert.equal(codes.length,16);assert.equal(new Set(codes).size,16);
 const first=await call(e,'/redeem',{code:codes[0]});assert.equal(first.status,200);assert.equal((await call(e,'/redeem',{code:codes[0]})).status,401);
 assert.equal((await call(e,'/owner/codes',{},first.body.token)).status,403);
 await call(e,'/owner/revoke',{id:first.body.id},owner);assert.equal((await call(e,'/session',undefined,first.body.token)).status,401);
});
test('rotation revokes unused codes, access enumeration omits secrets',async()=>{
 const e=env(),o=await login(e),old=(await call(e,'/owner/codes',{},o)).body.codes;
 await call(e,'/owner/codes',{replace:true},o);assert.equal((await call(e,'/redeem',{code:old[0]})).status,401);
 const list=await call(e,'/owner/access',{},o);assert.ok(!JSON.stringify(list).includes(old[0]));
});
test('policy expires, cannot permanently disable app, rejects malformed build targets',async()=>{
 const e=env(),o=await login(e),now=Math.floor(Date.now()/1000),p={kind:'security',minCode:1,maxCode:99999,message:'Security update for test builds',expires:now+3600,releaseTag:'',maintenance:false};
 assert.equal((await call(e,'/owner/policy',p,o)).status,200);assert.equal((await call(e,'/policy')).body.kind,'security');
 assert.throws(()=>validatePolicy({...p,expires:now+90000},now));assert.throws(()=>validatePolicy({...p,minCode:-1},now));assert.throws(()=>validatePolicy({...p,releaseTag:'https://evil.com'},now));
 e.ALLOW_MAINTENANCE='false';assert.equal((await call(e,'/owner/policy',{...p,maintenance:true},o)).status,403);
 await call(e,'/owner/policy',{clear:true},o);assert.deepEqual((await call(e,'/policy')).body,{});
});
test('redemption is rate limited and invalid sessions rejected',async()=>{
 const e=env();for(let i=0;i<8;i++)assert.equal((await call(e,'/redeem',{code:'b'.repeat(48)})).status,401);
 assert.equal((await call(e,'/redeem',{code:'b'.repeat(48)})).status,429);
 assert.equal((await call(e,'/owner/codes',{},'bad')).status,401);
});

test('rotated master invalidates the old master without reviving invitations',async()=>{
 const e=env(),o=await login(e),v=await call(e,'/owner/master',{},o);assert.equal(v.status,200);
 assert.equal((await call(e,'/redeem',{code:'a'.repeat(48)})).status,401);
 assert.equal((await call(e,'/redeem',{code:v.body.codes[0]})).body.role,'owner');
});

test('two simultaneous redemptions create exactly one session',async()=>{
 const e=env(),o=await login(e),code=(await call(e,'/owner/codes',{},o)).body.codes[0];
 const results=await Promise.all([call(e,'/redeem',{code}),call(e,'/redeem',{code})]);
 assert.deepEqual(results.map(r=>r.status).sort(),[200,401]);
});
