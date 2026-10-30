#!/usr/bin/env node
/** Explicit operator command. No cron, timer, login hook or background cleanup. */
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import tls from "node:tls";
import { Readable, Writable } from "node:stream";
import { LegacyStore } from "../admin_service/legacy.js";
import { brokerTcp } from "../admin_service/mqtt.js";
import { CLEANUP_FILTERS, firebaseCleanupView, planRetainedCleanup, cleanupSummary, executeRetainedCleanup } from "../admin_service/retained-cleanup.js";
const args=process.argv.slice(2);
if(args.includes("--help")) {
  console.log("Preview: node tools/cleanup-retained.mjs --dry-run\nClean:   node tools/cleanup-retained.mjs --apply\nOptional: --config /private/maintenance.json\nRuns only when invoked. Saves private local backups and verified Firebase archives before deleting eligible records.");
  process.exit(0);
}
const apply=args.includes("--apply"), configIndex=args.indexOf("--config");
const allowed=args.filter((value,index)=>value!=="--apply" && value!=="--dry-run" && value!=="--config" && (configIndex<0 || index!==configIndex+1));
if(allowed.length || (apply && args.includes("--dry-run"))) throw Error("Use --dry-run or --apply; see --help");
const configPath=configIndex>=0 ? args[configIndex+1] : path.join(os.homedir(),".config/bingo/maintenance.json");
function connectFactory({hostname,port}) {
  const socket=tls.connect({host:hostname,port,servername:hostname,rejectUnauthorized:true});
  const opened=new Promise((resolve,reject)=>{socket.once("secureConnect",resolve);socket.once("error",reject);});
  return {opened,readable:Readable.toWeb(socket),writable:Writable.toWeb(socket),close:()=>socket.destroy()};
}
try {
  const env=JSON.parse(await fs.readFile(configPath,"utf8"));
  const configured=new URL(env.MQTT_URL);
  if(!["wss:","ssl:"].includes(configured.protocol) || !configured.hostname.endsWith(".emqxsl.com")) throw Error("Expected the private EMQX TLS deployment");
  const account=typeof env.SERVICE_ACCOUNT_JSON==="string" ? JSON.parse(env.SERVICE_ACCOUNT_JSON) : env.SERVICE_ACCOUNT_JSON;
  if(!account?.project_id || !env.MQTT_USERNAME || !env.MQTT_PASSWORD) throw Error("Private Firebase/MQTT configuration is incomplete");
  const job=path.join(os.homedir(),".local/share/bingo/mqtt-cleanup",new Date().toISOString().replaceAll(":","-"));
  await fs.mkdir(job,{recursive:true,mode:0o700});
  const save=(name,value)=>fs.writeFile(path.join(job,name),JSON.stringify(value,null,2),{flag:"wx",mode:0o600});
  const store=new LegacyStore(env), broker=options=>brokerTcp(env,options,connectFactory);
  const [before,firebase]=await Promise.all([broker({snapshot:true,rawFilters:CLEANUP_FILTERS}),firebaseCleanupView(store)]);
  await save("before.private.json",before.retainedRecords);
  await save("firebase-before.private.json",firebase);
  const plan=await planRetainedCleanup(before.retainedRecords || {},firebase);
  await save("plan.private.json",plan);
  console.log(JSON.stringify({mode:apply?"manual-cleanup":"preview",...cleanupSummary(plan),backupDirectory:job},null,2));
  if(apply && plan.batchRecords) {
    const result=await executeRetainedCleanup(plan,{broker,store});
    await save("result.json",result);
    const after=await broker({snapshot:true,rawFilters:CLEANUP_FILTERS});
    await save("after.private.json",after.retainedRecords);
    console.log(JSON.stringify(result,null,2));
    if(result.recreatedOrStillPresent)process.exitCode=2;
  } else if(apply)console.log("No verified old records qualify for deletion.");
} catch(error) {
  console.error("Manual cleanup did not complete: "+error.message+". No success is assumed; any saved recovery archives remain available.");
  process.exitCode=1;
}
