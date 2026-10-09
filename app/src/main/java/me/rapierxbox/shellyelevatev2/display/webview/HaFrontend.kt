package me.rapierxbox.shellyelevatev2.display.webview

// home assistant tweaks for the dashboard webview
object HaFrontend {

    // ha picks its fast modern bundle by user agent and only for browsers from the last two years
    // webview 119 is the last one for android 7 so it always gets the slow es5 bundle
    // fixed so a much newer ha that needs more than 119 offers falls back to es5 on its own
    const val MODERN_CHROME_MAJOR = 140

    private val CHROME_TOKEN = Regex("""Chrome/(\d+)(\.[\d.]+)?""")

    // raises the chrome major so ha serves the modern bundle. newer or missing tokens stay as they are
    fun modernUserAgent(userAgent: String): String {
        val match = CHROME_TOKEN.find(userAgent) ?: return userAgent
        val major = match.groupValues[1].toIntOrNull() ?: return userAgent
        if (major >= MODERN_CHROME_MAJOR) return userAgent
        return userAgent.replaceRange(match.range, "Chrome/$MODERN_CHROME_MAJOR.0.0.0")
    }

    // runs before any page script on every page
    // the modern bundle only polyfills what its own targets lack (chrome 130 safari 18.1 and firefox
    // of the last two years) so apis those all have but 119 lacks are filled in here
    // url.canParse (chrome 120) and url.parse (126) are used today
    // array.fromAsync (121) and the set methods (122) are not used yet but cost nothing to cover
    // every polyfill leaves a native implementation alone
    // the login and onboarding pages skip their particle canvas under reduced motion
    // that canvas keeps a core busy for as long as a display sits at the login
    const val DOCUMENT_START_SCRIPT = """(function(){
var def=function(o,k,f){if(o&&typeof o[k]!=='function')Object.defineProperty(o,k,{value:f,writable:true,configurable:true})};
if(typeof URL==='function'){
def(URL,'canParse',function(u){try{new URL(u,arguments[1]);return true}catch(e){return false}});
def(URL,'parse',function(u){try{return new URL(u,arguments[1])}catch(e){return null}});
}
def(Array,'fromAsync',async function(items,fn,that){
var out=[],i=0,v;
if(items!=null&&typeof items[Symbol.asyncIterator]==='function'){for await(v of items)out.push(fn?await fn.call(that,v,i++):v)}
else{for(v of Array.from(items)){v=await v;out.push(fn?await fn.call(that,v,i++):v)}}
return out;
});
if(typeof Set==='function'){
var S=Set.prototype,keys=function(o){return Array.from(o.keys())};
def(S,'union',function(o){var r=new Set(this);keys(o).forEach(function(v){r.add(v)});return r});
def(S,'intersection',function(o){var r=new Set();this.forEach(function(v){if(o.has(v))r.add(v)});return r});
def(S,'difference',function(o){var r=new Set(this);this.forEach(function(v){if(o.has(v))r.delete(v)});return r});
def(S,'symmetricDifference',function(o){var t=this,r=new Set(this);keys(o).forEach(function(v){if(t.has(v))r.delete(v);else r.add(v)});return r});
def(S,'isSubsetOf',function(o){var ok=true;this.forEach(function(v){if(!o.has(v))ok=false});return ok});
def(S,'isSupersetOf',function(o){var t=this;return keys(o).every(function(v){return t.has(v)})});
def(S,'isDisjointFrom',function(o){var ok=true;this.forEach(function(v){if(o.has(v))ok=false});return ok});
}
var p=location.pathname;
if(p.indexOf('/auth/')!==0&&p.indexOf('/onboarding')!==0)return;
var mm=window.matchMedia;
if(typeof mm!=='function')return;
window.matchMedia=function(q){
var r=mm.apply(window,arguments);
var s=String(q);
if(s.indexOf('prefers-reduced-motion')<0)return r;
try{Object.defineProperty(r,'matches',{value:s.indexOf('no-preference')<0})}catch(e){}
return r;
};
})();"""

    // opt in so every page sees prefers-reduced-motion as reduce in js checks
    // css media queries cannot be faked so the vars ha sets under reduce are copied here
    // important since ha declares the same vars after this runs
    const val REDUCED_MOTION_SCRIPT = """(function(){
var mm=window.matchMedia;
if(typeof mm==='function'){
window.matchMedia=function(q){
var r=mm.apply(window,arguments);
var s=String(q);
if(s.indexOf('prefers-reduced-motion')<0)return r;
try{Object.defineProperty(r,'matches',{value:s.indexOf('no-preference')<0})}catch(e){}
return r;
};
}
var css='html{--ha-animation-duration-none:1ms!important;--ha-animation-duration-instant:1ms!important;--ha-animation-duration-fast:1ms!important;--ha-animation-duration-normal:1ms!important;--ha-animation-duration-slow:1ms!important}';
var add=function(){
var el=document.head||document.documentElement;
if(!el)return false;
var st=document.createElement('style');
st.textContent=css;
el.appendChild(st);
return true;
};
if(!add())document.addEventListener('DOMContentLoaded',add);
})();"""
}
