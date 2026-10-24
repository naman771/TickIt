"use strict";
// Private QR credentials stay in authenticated responses and local image blobs, never navigation URLs.
(() => {
  let generation = 0, imageUrl = null, ticketData = null, dialog = null;
  const windows = new Set();
  const escape = value => String(value ?? "").replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"})[c]);
  function clear() {
    generation++;
    if (dialog) { dialog.close(); dialog.remove(); dialog = null; }
    if (imageUrl) URL.revokeObjectURL(imageUrl);
    imageUrl = null; ticketData = null;
  }
  async function response(path) {
    const res = await fetch(path, {headers:{Authorization:`Bearer ${await TickItAuth.token()}`},cache:"no-store"});
    if (!res.ok) { let data; try {data=await res.json();} catch {} throw new Error(data?.error || "Your ticket could not be loaded."); }
    return res;
  }
  async function open(id) {
    clear(); const version=generation, user=TickItAuth.user?.id;
    if (!user) return;
    dialog=document.createElement("dialog");dialog.className="admission-dialog";
    dialog.innerHTML='<button type="button" class="quiet-button ticket-close" data-ticket-close aria-label="Close ticket">Close ×</button><div class="admission-content" aria-live="polite">Loading your ticket…</div>';
    document.body.append(dialog); dialog.showModal();
    dialog.addEventListener("cancel",clear);
    try {
      const data=await (await response(`/api/tickets/${encodeURIComponent(id)}`)).json();
      if(version!==generation || TickItAuth.user?.id!==user)return;
      const {ticket:t,event:e}=data;
      let blob=null;
      if(t.status==="VALID")blob=await (await response(`/api/tickets/${encodeURIComponent(id)}/qr`)).blob();
      if(version!==generation || TickItAuth.user?.id!==user)return;
      ticketData=data;imageUrl=blob?URL.createObjectURL(blob):null;
      dialog.querySelector(".admission-content").innerHTML=`<div class="eyebrow">YOUR ADMISSION / ${escape(t.status)}</div><h2>${escape(e.name)}</h2><p>${escape(e.venueName)} · ${escape(e.city)}</p><p>${escape(new Date(e.startInstant).toLocaleString("en-IN",{timeZone:e.timezone}))} (${escape(e.timezone)})</p><strong>${escape(t.label)} · Guest ${Number(t.ordinal)}</strong>${imageUrl?`<img class="admission-qr" src="${imageUrl}" alt="Your single-use admission QR code"><p>One guest · One entry. Keep this QR private.</p><div class="admission-actions"><a class="secondary-button" href="${imageUrl}" download="tickit-${escape(t.id)}.png">Download QR</a><button class="primary-button" data-ticket-print>Print / save PDF</button></div>`:`<p class="ticket-unavailable">${t.status==="USED"?"This admission has already been checked in.":"This admission is inactive. Check your event and refund status."}</p>`}<p class="ticket-reference">Ticket ${escape(t.id)}<br>Order ${escape(t.orderId)}</p><p class="ticket-reference">Entry opens 6 hours before the event. Bring age identification if the event requires it.</p>`;
    } catch(error) {
      if(version===generation && dialog)dialog.querySelector(".admission-content").textContent=error.message;
    }
  }
  document.addEventListener("click",event=>{
    const button=event.target.closest("[data-ticket-id]");
    if(button){event.preventDefault();open(button.dataset.ticketId);}
    if(event.target.closest("[data-ticket-close]"))clear();
    if(event.target.closest("[data-ticket-print]") && ticketData && imageUrl){
      const pop=window.open("","_blank"); if(!pop)return;
      windows.add(pop); const {ticket:t,event:e}=ticketData;
      pop.document.write(`<!doctype html><html lang="en"><head><meta charset="utf-8"><title>TickIt admission</title><style>body{font:16px system-ui;max-width:650px;margin:40px auto;padding:20px;color:#162e26}img{display:block;width:300px;height:300px;margin:20px 0}small{overflow-wrap:anywhere}button{padding:12px}@media print{button{display:none}}</style></head><body><h1>tickit.</h1><h2>${escape(e.name)}</h2><p>${escape(e.venueName)} · ${escape(e.city)}</p><p>${escape(new Date(e.startInstant).toLocaleString("en-IN",{timeZone:e.timezone}))} (${escape(e.timezone)})</p><h3>${escape(t.label)} · Guest ${Number(t.ordinal)}</h3><img src="${imageUrl}" alt="Single-use admission QR"><p>One guest · One entry. Keep this ticket private.</p><small>Ticket ${escape(t.id)}<br>Order ${escape(t.orderId)}<br>Current event and payment status is checked at entry.</small><p><button onclick="window.print()">Print / save PDF</button></p></body></html>`);
      pop.document.close();pop.opener=null;
    }
  });
  let user = TickItAuth.user?.id;
  TickItAuth.onChange(next=>{if(user!==next?.id){clear();for(const win of windows)if(!win.closed)win.close();windows.clear();user=next?.id;}});
  window.addEventListener("popstate",clear);window.addEventListener("hashchange",clear);
})();
