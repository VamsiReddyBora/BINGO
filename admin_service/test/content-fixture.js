import { createHash } from "node:crypto";
import { ContentPublisher } from "../content.js";
const json=value=>new Response(JSON.stringify(value),{headers:{"Content-Type":"application/json"}});
export function contentFixture() {
  let head="head0",failCommit=false;
  const blobs=new Map([["original-version",Buffer.from(JSON.stringify({versionName:"1.0.3",versionCode:73}))]]),trees=new Map([["tree0",new Map([["version.json","original-version"],["Bingo.apk","original-apk"],["admin/index.html","original-admin"]])]]),commits=new Map([[head,{tree:"tree0"}]]),calls=[];
  const transport=async(url,options={})=>{
    const path=new URL(url).pathname.split("/BINGO")[1],body=options.body?JSON.parse(options.body):null;
    calls.push({path,method:options.method||"GET",body});
    if(path==="/git/ref/heads/gh-pages")return json({object:{sha:head}});
    if(path==="/contents/version.json")return json({content:blobs.get(trees.get(commits.get(head).tree).get('version.json')).toString('base64')});
    if(path.startsWith("/git/commits/")&&!body)return json({tree:{sha:commits.get(path.split('/').at(-1)).tree}});
    if(path.startsWith("/git/trees/")&&!body)return json({truncated:false,tree:[...trees.get(path.split('/').at(-1))].map(([path,sha])=>({path,sha}))});
    if(path.startsWith("/git/blobs/")&&!body){const b=blobs.get(path.split('/').at(-1));return json({content:Buffer.from(b).toString('base64'),size:b.length});}
    if(path==="/git/blobs"){
      const bytes=Buffer.from(body.content,body.encoding==='base64'?'base64':'utf8'),sha=createHash('sha1').update(bytes).digest('hex');blobs.set(sha,bytes);return json({sha});
    }
    if(path==="/git/trees"){
      const tree=new Map(trees.get(body.base_tree));for(const f of body.tree)tree.set(f.path,f.sha);
      const sha="tree"+trees.size;trees.set(sha,tree);return json({sha});
    }
    if(path==="/git/commits"){
      const sha="head"+commits.size;commits.set(sha,body);return json({sha});
    }
    if(path==="/git/refs/heads/gh-pages"){
      if(failCommit||commits.get(body.sha).parents[0]!==head)return new Response('',{status:422});
      head=body.sha;return json({object:{sha:head}});
    }
    throw Error("Unexpected content fixture route "+path);
  };
  return {publisher:new ContentPublisher({GITHUB_TOKEN:"fixture-token"},transport),calls,transport,
    input:{target:"ALL",expectedRevision:0,strings:{"home.online.title":"Online Match"}},
    get files(){return trees.get(commits.get(head).tree);},
    manifest(path="content/index.json"){const sha=this.files.get(path);return sha?JSON.parse(blobs.get(sha).toString()):null;},
    set failCommit(value){failCommit=value;}};
}
