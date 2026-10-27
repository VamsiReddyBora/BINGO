/** Data-only OTA controls; publication continues through the existing passkey-approved API. */
export function createContentPanel({h, api, button, rows, confirmAction, toast}) {
  const root = h("details",{class:"panel content-panel"},h("summary",{},"Silent content updates"));
  root.append(h("p",{class:"small muted"},"Build 74+ · Supported Home text, layout settings and static backgrounds. Native code fixes still need an APK. Published means saved to Pages, not confirmed on a phone."));
  const target = h("select",{"data-content-target":"",name:"contentTarget"},
    h("option",{value:"ALL"},"All players"), ...rows().map(p=>h("option",{value:p.username},"@"+p.username)));
  const info = h("p",{class:"small muted","data-content-status":""},"Select an audience, then load its current content.");
  const form = h("form",{"data-content-form":""});
  const enabled = h("input",{type:"checkbox",checked:true,"data-content-enabled":""});
  const inherit = h("input",{type:"checkbox","data-content-inherit":""});
  const labels = {
    "home.ai.title":"Practice card title", "home.ai.description":"Practice card description",
    "home.online.title":"Online card title", "home.online.description":"Online card description",
    "home.nearby.title":"Nearby card title", "home.nearby.description":"Nearby card description",
    "home.developerNote":"Developer Note button", "home.serverNote":"Broker-warning text"
  };
  const fields = Object.fromEntries(Object.entries(labels).map(([key,label])=>{
    const input=h("input",{name:key,"data-content-field":key,maxLength:key==="home.serverNote"?400:key.endsWith("title")?48:key==="home.developerNote"?60:140,placeholder:"Keep built-in wording"});
    return [key,{input,node:h("label",{},label,input)}];
  }));
  const styleSpecs={verticalPaddingDp:["Card vertical padding",8,20],titleSp:["Card title size",13,18],descriptionSp:["Card description size",10,14],descriptionMaxLines:["Description lines",1,3]};
  const styles=Object.fromEntries(Object.entries(styleSpecs).map(([key,[label,min,max]])=>{
    const input=h("input",{type:"number",name:key,min,max,step:1,placeholder:"Built-in"});return[key,{input,node:h("label",{},label,input)}];
  }));
  const image=h("input",{type:"file",accept:"image/png,image/jpeg,image/webp","data-content-image":""});
  const remove=h("input",{type:"checkbox"});
  const opacity=h("input",{type:"number",min:0,max:.15,step:.01,value:.08});
  const minimum=h("input",{type:"number",min:74,step:1,value:74});
  const maximum=h("input",{type:"number",min:0,step:1,value:0});
  const submit=h("button",{type:"submit",disabled:true,"data-content-publish":""},"Review & publish content");
  const wording=h("details",{"data-content-wording":""},h("summary",{},"Home wording"),
    h("div",{class:"content-fields"},...Object.values(fields).map(f=>f.node)));
  const appearance=h("details",{},h("summary",{},"Home layout and optional background"),h("div",{class:"content-fields"},...Object.values(styles).map(f=>f.node),
    h("label",{},"Optional background (resized to JPEG)",image),h("label",{},"Remove background",remove),
    h("label",{},"Background opacity (0–0.15)",opacity),h("label",{},"Minimum app build",minimum),h("label",{},"Maximum app build (0 = no limit)",maximum)));
  const controls=h("div",{},wording,appearance);
  form.append(h("label",{},"Enable this content",enabled),h("label",{},"Follow public content (selected user only)",inherit),controls,submit);
  root.append(h("label",{},"Audience",target),info,button("Load current content",load),form);
  let loaded=null, sequence=0, pendingImage=null, reading=false;
  function switches(){
    inherit.disabled=target.value==="ALL";
    if(inherit.disabled)inherit.checked=false;
    for(const node of controls.querySelectorAll("input"))node.disabled=!enabled.checked||inherit.checked;
  }
  async function load(){
    const token=++sequence;loaded=null;pendingImage=null;reading=false;image.value="";submit.disabled=true;
    info.textContent="Reading current content…";
    try{
      const answer=await api("content?target="+encodeURIComponent(target.value));if(token!==sequence)return;
      loaded=answer;const p=answer.manifest;enabled.checked=p?.enabled??true;inherit.checked=p?.inheritGlobal??false;
      for(const [key,f]of Object.entries(fields))f.input.value=p?.strings?.[key]||"";
      for(const [key,f]of Object.entries(styles))f.input.value=p?.homeStyle?.[key]??"";
      opacity.value=p?.homeStyle?.backgroundOpacity??.08;minimum.value=p?.minBuild??74;maximum.value=p?.maxBuild??0;remove.checked=false;
      info.textContent=`Pages source revision ${answer.revision}. ${p?.background?"A background is configured.":"No background configured."} Blank text/layout fields preserve built-in defaults.`;
      submit.disabled=!answer.publishConfigured;switches();
    }catch(e){if(token===sequence)info.textContent=e.message;}
  }
  target.addEventListener("change",load);enabled.addEventListener("change",switches);inherit.addEventListener("change",switches);
  root.addEventListener("toggle",()=>{if(root.open&&!loaded&&!reading)load();});
  image.addEventListener("change",async()=>{
    const file=image.files[0],token=sequence;if(!file){pendingImage=null;return;}
    reading=true;submit.disabled=true;
    try{
      if(file.size>8*1024*1024)throw Error("Choose an image smaller than 8 MiB.");
      const bitmap=await createImageBitmap(file),scale=Math.min(1,1280/Math.max(bitmap.width,bitmap.height));
      const canvas=document.createElement("canvas");canvas.width=Math.max(1,Math.round(bitmap.width*scale));canvas.height=Math.max(1,Math.round(bitmap.height*scale));
      const context=canvas.getContext("2d");context.fillStyle="#000000";context.fillRect(0,0,canvas.width,canvas.height);context.drawImage(bitmap,0,0,canvas.width,canvas.height);bitmap.close();
      const data=canvas.toDataURL("image/jpeg",.85);if(data.length>2800000)throw Error("The resized image exceeds 2 MiB. Choose a simpler image.");
      if(token===sequence){pendingImage=data;remove.checked=false;info.textContent="Image prepared; it will be published only after confirmation.";}
    }catch(e){if(token===sequence){image.value="";pendingImage=null;toast(e.message,"error");}}
    finally{if(token===sequence){reading=false;submit.disabled=!loaded?.publishConfigured;}}
  });
  form.addEventListener("submit",async event=>{
    event.preventDefault();if(!loaded||reading||!form.reportValidity())return;
    const token=sequence,audience=target.value,empty=!enabled.checked||inherit.checked;
    const strings=empty?{}:Object.fromEntries(Object.entries(fields).filter(([,f])=>f.input.value.trim()).map(([key,f])=>[key,f.input.value.trim()]));
    const homeStyle={backgroundOpacity:empty ? .08 : Number(opacity.value)};
    if(!empty)for(const[key,f]of Object.entries(styles))if(f.input.value!=='')homeStyle[key]=Number(f.input.value);
    const input={target:audience,expectedRevision:loaded.revision,enabled:enabled.checked,inheritGlobal:inherit.checked,
      strings,homeStyle,minBuild:Number(minimum.value),maxBuild:Number(maximum.value),
      background:empty||remove.checked||pendingImage?null:loaded.manifest?.background??null};
    if(!empty&&pendingImage)input.backgroundBase64=pendingImage;
    if(!await confirmAction("Publish silent content?",`Publish revision ${loaded.revision+1} to ${audience==='ALL'?'all updated players':'@'+audience}? Active matches are not changed.`))return;
    if(token!==sequence)return;submit.disabled=true;
    try{
      const answer=await api("content",{method:"PUT",data:input});if(token!==sequence)return;
      toast(answer.operation?.status==='SYNCED'?"Saved to Pages source. Deployment and phone application are pending.":"Content publication is pending; inspect synchronization jobs.",answer.operation?.status==='SYNCED'?"success":"warning");await load();
    }catch(e){if(token===sequence)toast(e.message,"error");}
    finally{if(token===sequence)submit.disabled=!loaded?.publishConfigured;}
  });
  return root;
}
