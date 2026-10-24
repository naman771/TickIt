"use strict";
(()=>{
  const root=document.getElementById('entryWorkspace');let stream=null,busy=false,version=0,cameraVersion=0;
  const esc=v=>String(v??"").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"})[c]);
  const labels={ADMITTED:'Entry confirmed',ALREADY_USED:'Already checked in',INVALID:'Invalid ticket for this event',INACTIVE:'Ticket cancelled or refunded',TOO_EARLY:'Entry has not opened yet',ENDED:'Event entry has closed'};
  async function request(path,options={}){
    const token=await TickItAuth.token();const res=await fetch((window.TICKIT_API_BASE||'')+path,{...options,headers:{Authorization:`Bearer ${token}`,...(options.body instanceof FormData?{}:{'Content-Type':'application/json'})}});
    let data;try{data=await res.json();}catch{}if(!res.ok)throw new Error(data?.error||'Entry service unavailable. Do not admit until validation succeeds.');return data;
  }
  function stop(){cameraVersion++;stream?.getTracks().forEach(t=>t.stop());stream=null;}
  async function load(){
    const run=++version;stop();busy=false;
    if(!TickItAuth.user){root.innerHTML='<section class="panel"><h1>Event entry.</h1><p>Sign in with the account your organizer authorized.</p><button class="primary" data-entry-signin>Sign in</button></section>';return;}
    document.getElementById('entryAccount').innerHTML='<button data-entry-signout>Sign out</button>';
    try{
      const events=await request('/api/entry/events');if(run!==version)return;
      const selected=location.hash.match(/^#event\/(\d+)$/)?.[1];const e=events.find(e=>String(e.id)===selected)||events[0];
      root.innerHTML=`<section class="panel"><div class="eyebrow">AUTHORIZED ENTRY</div><h1>Welcome them in.</h1><label>Event<select id="entryEvent">${events.map(x=>`<option value="${Number(x.id)}" ${x.id===e?.id?'selected':''}>${esc(x.name)} · ${esc(x.status)}</option>`).join('')}</select></label>${e?`<p>One scan admits one guest. Validation requires an internet connection. Entry opens 6 hours before the event.</p><div class="top-actions"><button class="primary" data-camera-start>Use camera</button><button data-camera-stop>Stop camera</button></div><video id="entryVideo" autoplay muted playsinline></video><button data-camera-scan hidden>Scan current frame</button><label>Or upload a ticket QR image<input id="entryFile" type="file" accept="image/png,image/jpeg"></label><form id="entryCode"><label>Or enter the ticket credential<input name="credential" type="password" autocomplete="off" spellcheck="false" maxlength="100" required placeholder="tickit:v1:…"></label><button class="primary">Validate and admit</button></form><div class="scan-result" id="scanResult" role="status" aria-live="polite">Ready to scan.</div>`:'<p>No events assigned. Ask the organizer to grant check-in access to your verified account.</p>'}</section>`;
      if(!e)return;
      document.getElementById('entryEvent').addEventListener('change',ev=>{location.hash='event/'+ev.target.value;});
      root.querySelector('#entryCode').addEventListener('submit',ev=>{ev.preventDefault();const code=new FormData(ev.target).get('credential');ev.target.reset();submit(e.id,{method:'POST',body:JSON.stringify({credential:code})});});
      root.querySelector('#entryFile').addEventListener('change',ev=>{const file=ev.target.files[0];ev.target.value='';if(file)upload(e.id,file);});
      root.querySelector('[data-camera-start]').addEventListener('click',async()=>{
        try{stop();const cameraRun=cameraVersion;const camera=await navigator.mediaDevices.getUserMedia({video:{facingMode:{ideal:'environment'},width:{ideal:1280},height:{ideal:720}},audio:false});if(run!==version||cameraRun!==cameraVersion){camera.getTracks().forEach(t=>t.stop());return;}stream=camera;root.querySelector('video').srcObject=stream;root.querySelector('[data-camera-scan]').hidden=false;}catch{show('Camera unavailable. Allow camera access or upload a QR image.',false);}
      });
      root.querySelector('[data-camera-stop]').addEventListener('click',()=>{stop();root.querySelector('[data-camera-scan]').hidden=true;});
      root.querySelector('[data-camera-scan]').addEventListener('click',()=>{
        const video=root.querySelector('video');if(!video.videoWidth)return;const canvas=document.createElement('canvas');const scale=Math.min(1,1280/video.videoWidth);canvas.width=Math.round(video.videoWidth*scale);canvas.height=Math.round(video.videoHeight*scale);canvas.getContext('2d').drawImage(video,0,0,canvas.width,canvas.height);canvas.toBlob(blob=>{if(blob&&run===version)upload(e.id,blob);},'image/jpeg',0.9);
      });
    }catch(error){if(run===version)root.textContent=error.message;}
  }
  function show(text,good){const box=document.getElementById('scanResult');if(box){box.textContent=text;box.classList.toggle('admitted',good);}}
  function upload(event,file){if(file.size>2*1024*1024){show('Choose an image up to 2 MB.',false);return;}const form=new FormData();form.append('file',file,'ticket.jpg');submit(event,{method:'POST',body:form},'/image');}
  async function submit(event,options,suffix=''){
    if(busy)return;busy=true;const run=version;show('Checking ticket…',false);
    try{const result=await request(`/api/organizer/events/${event}/check-in${suffix}`,options);if(run!==version)return;show((labels[result.outcome]||result.outcome)+(result.label?' · '+result.label:'')+(result.checkedInAt?' · '+new Date(result.checkedInAt).toLocaleTimeString():''),result.outcome==='ADMITTED');}catch(error){if(run===version)show(error.message,false);}finally{if(run===version)busy=false;}
  }
  document.addEventListener('click',e=>{if(e.target.closest('[data-entry-signin]'))TickItAuth.open('sign-in');if(e.target.closest('[data-entry-signout]'))TickItAuth.signOut();});
  TickItAuth.onChange(load);window.addEventListener('hashchange',load);window.addEventListener('pagehide',stop);window.addEventListener('tickit:auth-error',e=>{root.textContent=e.detail;});TickItAuth.init().then(load).catch(error=>{root.textContent=error.message;});
})();
