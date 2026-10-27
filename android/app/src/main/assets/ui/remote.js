'use strict';
const catalog=[['Netflix','netflix'],['HBO Max','max'],['Disney+','disney'],['YouTube','youtube'],['Prime Video','prime'],['Spotify','spotify']];
const $=s=>document.querySelector(s);let ready=false,noticeTimer;
function send(action,data={}){const payload={action,...data};if(window.AndroidRemote){window.AndroidRemote.postMessage(JSON.stringify(payload));}else{if(action==='settings'||action==='key'||action==='shortcut'||action==='launch')$('#preview-help').showModal();else toast('Esta función se usa desde el APK Android.');}}
function toast(text){$('#toast').textContent=text;$('#toast').hidden=false;clearTimeout(noticeTimer);noticeTimer=setTimeout(()=>$('#toast').hidden=true,4500);}
function logoFor(name){let n=name.toLowerCase();return catalog.find(([label])=>n.includes(label.toLowerCase())||(label==='HBO Max'&&(n.includes('hbo')||n==='max')))?.[1];}
function appButton(name,logo,id){const b=document.createElement('button');b.className='shortcut';b.setAttribute('aria-label','Abrir '+name+' en el televisor');if(logo){const img=document.createElement('img');img.src='logos/'+logo+'.jpg';img.alt='';b.append(img);}else{const fallback=document.createElement('span');fallback.className='generic-app';fallback.textContent=name.slice(0,1).toUpperCase();b.append(fallback);}const text=document.createElement('span');text.textContent=name;b.append(text);let timer,long=false;b.addEventListener('pointerdown',()=>{long=false;if(logo)timer=setTimeout(()=>{long=true;send('variants',{name:catalog.find(c=>c[1]===logo)[0]});},650)});for(const event of ['pointerup','pointercancel','pointerleave'])b.addEventListener(event,()=>clearTimeout(timer));b.addEventListener('contextmenu',e=>e.preventDefault());b.addEventListener('click',()=>{if(!long)send(id?'launch':'shortcut',id?{id}:{name})});return b;}
for(const [name,logo]of catalog){$('#shortcuts').append(appButton(name,logo));$('#app-favorites').append(appButton(name,logo));}
for(const digit of ['1','2','3','4','5','6','7','8','9','RETURN','0','ENTER']){const b=document.createElement('button');b.textContent=digit==='RETURN'?'←':digit==='ENTER'?'OK':digit;b.setAttribute('aria-label',digit==='RETURN'?'Volver':digit==='ENTER'?'Aceptar':'Número '+digit);b.dataset.key=digit;$('#number-pad').append(b);}
document.querySelectorAll('[data-key]').forEach(b=>b.addEventListener('click',()=>send('key',{key:b.dataset.key})));
document.querySelectorAll('[data-action]').forEach(b=>b.addEventListener('click',()=>send(b.dataset.action)));
document.querySelectorAll('[data-page]').forEach(b=>b.addEventListener('click',()=>{document.querySelectorAll('.page').forEach(p=>p.classList.toggle('active',p.id===b.dataset.page));document.querySelectorAll('[data-page]').forEach(t=>{t.classList.toggle('selected',t===b);if(t===b)t.setAttribute('aria-current','page');else t.removeAttribute('aria-current');});$('main').scrollTop=0;if(b.dataset.page==='applications'&&ready)send('apps');}));
window.remoteState=s=>{ready=!!s.ready;$('.connection').classList.toggle('connected',ready);$('.connection').classList.toggle('pending',!!s.pending);$('#connection-label').textContent=ready?'Samsung TU7000 · Conectado':s.pending?'Aceptá el permiso en el TV':'Conectar televisor';$('.connection').title=s.message;$('.connection').setAttribute('aria-label',s.message+'. Tocar para configurar.');if(!ready&&!s.pending)$('#apps-message').textContent='Conectá el televisor para consultar sus apps.';};
window.remoteApps=data=>{const list=Array.isArray(data.apps)?data.apps:[];$('#app-count').textContent=list.length;$('#installed').replaceChildren();$('#apps-message').textContent=list.length?'':'Conectá el televisor para consultar sus apps.';for(const app of list){const b=appButton(app.name,logoFor(app.name),app.id);b.className='installed-app';$('#installed').append(b);}};
window.appsNotice=data=>$('#apps-message').textContent=data.message;
$('#close-preview').addEventListener('click',()=>$('#preview-help').close());
document.addEventListener('keydown',e=>{if(e.target.closest('button,input,select,dialog')||e.ctrlKey||e.metaKey||e.altKey)return;const key={ArrowUp:'UP',ArrowDown:'DOWN',ArrowLeft:'LEFT',ArrowRight:'RIGHT',Enter:'ENTER',Escape:'RETURN'}[e.key];if(key){e.preventDefault();send('key',{key});}});

// Fuente: alternativa explícita por pulsación larga, sin macros a ciegas.
const sourceButton=document.querySelector('[data-key="SOURCE"]');
let sourceTimer,sourceHeld=false;
sourceButton.addEventListener('pointerdown',()=>{sourceHeld=false;sourceTimer=setTimeout(()=>{sourceHeld=true;send('sources');},650)});
for(const event of ['pointerup','pointerleave','pointercancel'])sourceButton.addEventListener(event,()=>clearTimeout(sourceTimer));
sourceButton.addEventListener('click',e=>{if(sourceHeld){e.stopImmediatePropagation();sourceHeld=false;}},true);
sourceButton.addEventListener('contextmenu',e=>e.preventDefault());
