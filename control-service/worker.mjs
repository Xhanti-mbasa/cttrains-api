// No bearer credentials, redemption codes, IPs or personal trip data are logged.
const enc = new TextEncoder();
const json = (value, status=200) => Response.json(value, {status, headers:{'Cache-Control':'no-store','X-Content-Type-Options':'nosniff'}});
const fail = (message, status=400) => { throw Object.assign(new Error(message), {status}); };
export const randomCode = () => Array.from(crypto.getRandomValues(new Uint8Array(24)), x=>x.toString(16).padStart(2,'0')).join('');
async function key(env) { return crypto.subtle.importKey('raw',enc.encode(env.PEPPER),{name:'HMAC',hash:'SHA-256'},false,['sign','verify']); }
export async function digest(env,value) { return Array.from(new Uint8Array(await crypto.subtle.sign('HMAC',await key(env),enc.encode(value))), x=>x.toString(16).padStart(2,'0')).join(''); }
async function owner(env,code) {
    const saved=await env.DB.prepare('SELECT digest FROM owner_config WHERE id=1').first();
    const verifier=saved?.digest || env.OWNER_DIGEST;
    if (!/^[a-f0-9]{64}$/.test(verifier || '')) return false;
    const bytes = Uint8Array.from(verifier.match(/../g), x=>parseInt(x,16));
    return crypto.subtle.verify('HMAC',await key(env),bytes,enc.encode(code));
}
async function body(request) {
    const reader=request.body?.getReader(); if(!reader) return {};
    let length=0; const chunks=[];
    while(true) { const {done,value}=await reader.read(); if(done) break; length+=value.length; if(length>8192) { await reader.cancel();fail('Request too large',413); } chunks.push(value); }
    const bytes=new Uint8Array(length);let offset=0;for(const c of chunks){bytes.set(c,offset);offset+=c.length;}
    try { return JSON.parse(new TextDecoder().decode(bytes)); } catch { fail('Invalid JSON'); }
}
async function rate(request,env,now) {
    const bucket=Math.floor(now/60); const id=await digest(env,`rate:${request.headers.get('CF-Connecting-IP') || 'unknown'}:${bucket}`);
    const r=await env.DB.prepare('INSERT INTO limits(key,count,expires) VALUES(?,1,?) ON CONFLICT(key) DO UPDATE SET count=count+1 RETURNING count').bind(id,now+120).first();
    if(r.count>8) fail('Too many attempts. Wait a minute.',429);
    await env.DB.prepare('DELETE FROM limits WHERE expires < ?').bind(now).run();
}
async function session(request,env,now,role) {
    const token=request.headers.get('Authorization')?.replace(/^Bearer /,'') || '';
    if(!/^[a-f0-9]{48}$/.test(token)) fail('Developer access required',401);
    const row=await env.DB.prepare('SELECT id,role,expires FROM sessions WHERE digest=? AND revoked=0 AND expires>?').bind(await digest(env,`session:${token}`),now).first();
    if(!row) fail('Session expired or revoked. Ask the owner for a new code.',401);
    if(role && row.role!==role) fail('Owner access required',403);
    return row;
}
async function audit(env,who,action,now) { await env.DB.batch([
    env.DB.prepare('INSERT INTO audit(at,actor,action) VALUES(?,?,?)').bind(now,who,action),
    env.DB.prepare('DELETE FROM audit WHERE id NOT IN (SELECT id FROM audit ORDER BY id DESC LIMIT 500)')
]); }
export function validatePolicy(v,now) {
    if(!v || !['normal','security'].includes(v.kind)) fail('Choose normal or security');
    if(!Number.isSafeInteger(v.minCode) || !Number.isSafeInteger(v.maxCode) || v.minCode<1 || v.maxCode<v.minCode) fail('Invalid affected build range');
    if(typeof v.message!=='string' || v.message.length>500) fail('Message must be at most 500 characters');
    if(!Number.isSafeInteger(v.expires) || v.expires<=now || v.expires>now+86400) fail('Policy must expire within 24 hours');
    if(v.releaseTag!=='' && !/^android-v\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?$/.test(v.releaseTag)) fail('Choose a published Android release tag');
    if(typeof v.maintenance!=='boolean') fail('Invalid maintenance setting');
    if(v.maintenance && v.message.trim().length<12) fail('Maintenance needs a clear explanation');
    return {id:crypto.randomUUID(),kind:v.kind,minCode:v.minCode,maxCode:v.maxCode,message:v.message.trim(),expires:v.expires,releaseTag:v.releaseTag,maintenance:v.maintenance};
}
export default {
 async fetch(request,env) {
  try {
    const path=new URL(request.url).pathname, now=Math.floor(Date.now()/1000);
    if(request.method==='GET' && path==='/policy') {
        const row=await env.DB.prepare('SELECT value FROM policy WHERE id=1').first();
        const p=row ? JSON.parse(row.value) : null;
        return json(p && p.expires>now ? p : {});
    }
    if(request.method==='POST' && path==='/redeem') {
        await rate(request,env,now);const v=await body(request);
        const code=String(v.code || '').trim().toLowerCase().replace(/-/g,'');
        if(!/^[a-f0-9]{48}$/.test(code)) fail('Invalid or used code',401);
        const isOwner=await owner(env,code), token=randomCode(), hash=await digest(env,`session:${token}`), id=crypto.randomUUID();
        const expires=now+(isOwner ? 3600 : 7*86400);
        if(isOwner) await env.DB.prepare('INSERT INTO sessions(digest,id,role,expires) VALUES(?,?,?,?)').bind(hash,id,'owner',expires).run();
        else {
            // One transaction: the code is burned and exactly one session can be inserted, even under concurrent redemption.
            const d=await digest(env,`invite:${code}`);
            const r=await env.DB.batch([
                env.DB.prepare('UPDATE invitations SET used=? WHERE digest=? AND used IS NULL AND revoked=0 AND expires>?').bind(hash,d,now),
                env.DB.prepare("INSERT INTO sessions(digest,id,role,expires) SELECT ?,?,'tester',? FROM invitations WHERE digest=? AND used=?").bind(hash,id,expires,d,hash)
            ]);
            if(r[1].meta.changes!==1) fail('Invalid, expired or used code',401);
        }
        await audit(env,id,'redeem '+(isOwner ? 'owner' : 'tester'),now);
        return json({token,id,role:isOwner?'owner':'tester',expires});
    }
    if(request.method==='GET' && path==='/session') return json(await session(request,env,now));
    if(request.method!=='POST') fail('Not found',404);
    const who=await session(request,env,now,'owner');const v=await body(request);
    if(path==='/owner/codes') {
        const codes=Array.from({length:16},randomCode);
        const statements=[];
        if(v.replace===true) statements.push(env.DB.prepare('UPDATE invitations SET revoked=1 WHERE used IS NULL'));
        for(const code of codes) statements.push(env.DB.prepare('INSERT INTO invitations(digest,id,expires) VALUES(?,?,?)').bind(await digest(env,`invite:${code}`),crypto.randomUUID(),now+30*86400));
        await env.DB.batch(statements);await audit(env,who.id,'issue 16 invitations',now);
        return json({codes,expires:now+30*86400});
    }
    if(path==='/owner/master') {
        const master=randomCode();const d=await digest(env,master);
        await env.DB.batch([
            env.DB.prepare('INSERT INTO owner_config(id,digest) VALUES(1,?) ON CONFLICT(id) DO UPDATE SET digest=excluded.digest').bind(d),
            env.DB.prepare("UPDATE sessions SET revoked=1 WHERE role='owner' AND id<>?").bind(who.id)
        ]);
        await audit(env,who.id,'rotate owner credential',now);
        return json({codes:[master]});
    }
    if(path==='/owner/access') {
        const access=await env.DB.prepare('SELECT id,role,expires,revoked FROM sessions ORDER BY expires DESC LIMIT 100').all();
        const invitations=await env.DB.prepare('SELECT id,expires,(used IS NOT NULL) AS used,revoked FROM invitations ORDER BY expires DESC LIMIT 100').all();
        const log=await env.DB.prepare('SELECT at,actor,action FROM audit ORDER BY id DESC LIMIT 50').all();
        return json({sessions:access.results,invitations:invitations.results,audit:log.results});
    }
    if(path==='/owner/revoke') {
        if(v.all===true) await env.DB.batch([env.DB.prepare("UPDATE sessions SET revoked=1 WHERE role='tester'"),env.DB.prepare('UPDATE invitations SET revoked=1 WHERE used IS NULL')]);
        else { if(typeof v.id!=='string' || v.id.length>50) fail('Choose an access ID');await env.DB.batch([env.DB.prepare('UPDATE sessions SET revoked=1 WHERE id=?').bind(v.id),env.DB.prepare('UPDATE invitations SET revoked=1 WHERE id=?').bind(v.id)]); }
        await audit(env,who.id,'revoke access',now);return json({ok:true});
    }
    if(path==='/owner/policy') {
        if(v.clear===true) { await env.DB.prepare('DELETE FROM policy').run();await audit(env,who.id,'clear policy',now);return json({ok:true}); }
        const p=validatePolicy(v,now);
        // Emergency notices may target only an existing published release in our own GitHub repository.
        if(p.releaseTag) {
            const r=await fetch(`https://api.github.com/repos/Xhanti-mbasa/cttrains-api/releases/tags/${p.releaseTag}`,{headers:{'User-Agent':'CT-Trains-Control','Accept':'application/vnd.github+json'}});
            if(!r.ok) fail('Release is not accessible on GitHub');const release=await r.json();
            if(release.draft || !release.assets?.some(a=>a.name==='update.json')) fail('Release has no update manifest');
        }
        if(p.maintenance && env.ALLOW_MAINTENANCE!=='true') fail('Maintenance is retired on this service',403);
        await env.DB.prepare('INSERT INTO policy(id,value) VALUES(1,?) ON CONFLICT(id) DO UPDATE SET value=excluded.value').bind(JSON.stringify(p)).run();
        await audit(env,who.id,'publish '+p.kind+(p.maintenance?' maintenance':''),now);return json(p);
    }
    fail('Not found',404);
  } catch(e) { return json({error:e.status ? e.message : 'Service unavailable. Try again later.'},e.status || 503); }
 }
};
