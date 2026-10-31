const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
test('the real extension M3U bridge preserves zero maxAge for an explicit refresh',async()=>{
 const source=fs.readFileSync(path.join(__dirname,'../app/src/main/java/link/mccoy88f/zephyr/ExtensionCalls.kt'),'utf8');
 const bridge=source.match(/zephyr\.m3u = \{[\s\S]*?\n\s*\};/)[0];
 const calls=[],context={zephyr:{},__zephyr:async(name,args)=>{calls.push(args);return {};}};
 vm.createContext(context);vm.runInContext(bridge,context);
 await context.zephyr.m3u.load('test',{maxAge:0});
 await context.zephyr.m3u.load('test',{maxAge:300});
 await context.zephyr.m3u.load('test',{});
 assert.deepEqual(calls.map(c=>c.maxAge),[0,300,86400]);
});
