const test=require('node:test'),assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const root=path.join(__dirname,'../../main/resources/extracted/web/js');
function harness(widget){
 let now=0,sequence=0;const timers=new Map(),handlers=[],samples=[];
 const element={addClass(){return this},removeClass(){return this},appendTo(){return this},height(){return this},css(){return this},text(){return this}};
 const $=()=>({...element,on(event,handler){handlers.push(handler);return this}});
 const context=vm.createContext({$,componentconstructors:{},performance:{now:()=>now},
  getMinecraftTime:t=>{samples.push(t);return {hours:6,minutes:0,day:true,night:false,servertime:Math.trunc(t)}},
  window:{setTimeout:fn=>{timers.set(++sequence,fn);return sequence},clearTimeout:id=>timers.delete(id)}});
 vm.runInContext(fs.readFileSync(path.join(root,widget+'.js'),'utf8'),context);
 context.componentconstructors[widget]({options:{container:{}}},{showdigitalclock:true,showweather:false});
 return {samples,timers,update(time,rate){for(const handler of handlers)handler({}, {servertime:time,servertimeRate:rate})},
  tick(ms){now+=ms;const callbacks=[...timers.values()];timers.clear();for(const fn of callbacks)fn()}};
}
for(const widget of ['digitalclock','timeofdayclock']){
 test(widget+' holds paused and missing clocks without interpolation',()=>{
  const h=harness(widget);h.update(6000,0);assert.equal(h.timers.size,0);h.tick(7000);assert.equal(h.samples.length,1);
  h.update(6000,1);assert.equal(h.timers.size,1);h.update(-1,0);assert.equal(h.timers.size,0);
 });
 test(widget+' interpolates actual elapsed time at the supplied clock rate',()=>{
  const h=harness(widget);h.update(6000,0.5);h.tick(1000);assert.equal(h.samples.at(-1),6010);
  h.update(6000,2);h.tick(2000);assert.equal(h.samples.at(-1),6080);
  h.update(6000,0.001);h.tick(700);h.tick(700);assert.ok(Math.abs(h.samples.at(-1)-6000.028)<1e-8);
  h.update(23990,1);h.tick(1000);assert.equal(h.samples.at(-1),10);
 });
 test(widget+' replaces pending timers on updates and supports older packets',()=>{
  const h=harness(widget);h.update(6000,2);h.update(18000,undefined);assert.equal(h.timers.size,1);
  h.tick(1000);assert.equal(h.samples.at(-1),18020);
 });
}
