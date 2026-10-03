"""
Phone WiFi Keyboard + Touchpad for Windows  (v2.1)
================================================
Run on your Windows PC (Python 3.8+, zero required packages):

    python phone_keyboard.py              # normal
    python phone_keyboard.py --admin      # also type into admin windows (Task Manager, installers...)
    python phone_keyboard.py --port 9000  # custom port
    python phone_keyboard.py --new-token  # invalidate old phone links

Open the printed URL on your phone (same WiFi). Bookmark it / "Add to Home Screen":
the link stays the same between runs until you use --new-token.
Optional: `pip install qrcode` to get a scannable QR code in the console.
"""
import argparse, base64, ctypes, hashlib, json, os, secrets, socket, struct, sys, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

DRYRUN = os.environ.get("PK_DRYRUN") == "1"   # for testing on non-Windows machines
VERSION = "2.1"
DISCOVERY_PORT = 8766   # UDP: lets the Android app find & pair with this PC

# ======================================================================
#  Windows input injection (SendInput)
# ======================================================================
if sys.platform == "win32":
    from ctypes import wintypes
    user32 = ctypes.WinDLL("user32", use_last_error=True)
    ULONG_PTR = ctypes.c_size_t

    class KEYBDINPUT(ctypes.Structure):
        _fields_ = [("wVk", wintypes.WORD), ("wScan", wintypes.WORD), ("dwFlags", wintypes.DWORD),
                    ("time", wintypes.DWORD), ("dwExtraInfo", ULONG_PTR)]
    class MOUSEINPUT(ctypes.Structure):
        _fields_ = [("dx", wintypes.LONG), ("dy", wintypes.LONG), ("mouseData", wintypes.DWORD),
                    ("dwFlags", wintypes.DWORD), ("time", wintypes.DWORD), ("dwExtraInfo", ULONG_PTR)]
    class HARDWAREINPUT(ctypes.Structure):
        _fields_ = [("uMsg", wintypes.DWORD), ("wParamL", wintypes.WORD), ("wParamH", wintypes.WORD)]
    class _U(ctypes.Union):
        _fields_ = [("ki", KEYBDINPUT), ("mi", MOUSEINPUT), ("hi", HARDWAREINPUT)]
    class INPUT(ctypes.Structure):
        _anonymous_ = ("u",)
        _fields_ = [("type", wintypes.DWORD), ("u", _U)]

    def _send_inputs(items):
        arr = (INPUT * len(items))(*items)
        user32.SendInput(len(items), arr, ctypes.sizeof(INPUT))
    def _kbd(vk=0, scan=0, flags=0):
        return INPUT(type=1, ki=KEYBDINPUT(vk, scan, flags, 0, 0))
    def _mouse(dx=0, dy=0, data=0, flags=0):
        return INPUT(type=0, mi=MOUSEINPUT(dx, dy, data & 0xFFFFFFFF, flags, 0, 0))
    def _vk_to_scan(vk):
        return user32.MapVirtualKeyW(vk, 0)
elif DRYRUN:
    LOG = []
    def _send_inputs(items): LOG.extend(items)
    def _kbd(vk=0, scan=0, flags=0): return ("k", vk, scan, flags)
    def _mouse(dx=0, dy=0, data=0, flags=0): return ("m", dx, dy, data, flags)
    def _vk_to_scan(vk): return vk & 0x7F
else:
    sys.exit("This program must run on Windows (the PC you want to type on).")

KEYUP, UNICODE, SCANCODE, EXTENDED = 0x2, 0x4, 0x8, 0x1
VK = {"enter": 0x0D, "backspace": 0x08, "tab": 0x09, "esc": 0x1B, "space": 0x20,
      "left": 0x25, "up": 0x26, "right": 0x27, "down": 0x28, "delete": 0x2E, "insert": 0x2D,
      "home": 0x24, "end": 0x23, "pgup": 0x21, "pgdn": 0x22, "ctrl": 0x11, "shift": 0x10,
      "alt": 0x12, "win": 0x5B, "printscreen": 0x2C, "capslock": 0x14, "menu": 0x5D,
      "volup": 0xAF, "voldown": 0xAE, "mute": 0xAD, "playpause": 0xB3, "next": 0xB0, "prev": 0xB1}
VK.update({f"f{i}": 0x6F + i for i in range(1, 13)})
EXTENDED_VKS = {0x25, 0x26, 0x27, 0x28, 0x2E, 0x2D, 0x24, 0x23, 0x21, 0x22, 0x5B, 0x5D, 0x2C}
NO_SCAN_VKS = {0x5B, 0x5D, 0xAF, 0xAE, 0xAD, 0xB3, 0xB0, 0xB1, 0x2C}  # send as pure VK

def vk_of(key):
    k = str(key).lower()
    if k in VK: return VK[k]
    if len(k) == 1 and (k.isascii() and k.isalnum()): return ord(k.upper())
    return None

def _key(vk, up):
    """Key event with hardware scan code too -> works in games/DirectInput apps."""
    flags = (KEYUP if up else 0) | (EXTENDED if vk in EXTENDED_VKS else 0)
    scan = 0 if vk in NO_SCAN_VKS else _vk_to_scan(vk)
    if scan: flags |= SCANCODE
    return _kbd(vk=vk, scan=scan, flags=flags)

def tap(vk, mods=()):
    seq = [_key(m, False) for m in mods] + [_key(vk, False), _key(vk, True)] + [_key(m, True) for m in reversed(mods)]
    _send_inputs(seq)

def type_text(text):
    seq = []
    for ch in text:
        if ch in "\r": continue
        if ch == "\n": seq += [_key(VK["enter"], False), _key(VK["enter"], True)]; continue
        if ch == "\t": seq += [_key(VK["tab"], False), _key(VK["tab"], True)]; continue
        b = ch.encode("utf-16-le")
        for i in range(0, len(b), 2):                     # emoji = surrogate pairs
            cu = int.from_bytes(b[i:i + 2], "little")
            seq += [_kbd(scan=cu, flags=UNICODE), _kbd(scan=cu, flags=UNICODE | KEYUP)]
    for i in range(0, len(seq), 200):                     # chunk huge pastes
        _send_inputs(seq[i:i + 200])

M_MOVE, M_LD, M_LU, M_RD, M_RU, M_MD, M_MU, M_WHEEL, M_HWHEEL = 0x1, 0x2, 0x4, 0x8, 0x10, 0x20, 0x40, 0x800, 0x1000
BTN = {"left": (M_LD, M_LU), "right": (M_RD, M_RU), "middle": (M_MD, M_MU)}

LOCK = threading.Lock()
def handle(cmd):
    t = cmd.get("type")
    with LOCK:
        if t == "text":
            type_text(str(cmd.get("text", ""))[:20000])
        elif t == "backspace":
            n = max(0, min(int(cmd.get("count", 1)), 1000))
            _send_inputs([e for _ in range(n) for e in (_key(8, False), _key(8, True))])
        elif t == "key":
            key, mods = cmd.get("key", ""), [VK[m] for m in cmd.get("mods", []) if m in VK]
            vk = vk_of(key)
            if vk is not None: tap(vk, mods)
            elif not mods: type_text(str(key))
        elif t == "keydown" or t == "keyup":               # for holding keys (games)
            vk = vk_of(cmd.get("key", ""))
            if vk is not None: _send_inputs([_key(vk, t == "keyup")])
        elif t == "move":
            dx, dy = int(cmd.get("dx", 0)), int(cmd.get("dy", 0))
            if dx or dy: _send_inputs([_mouse(dx=dx, dy=dy, flags=M_MOVE)])
        elif t == "scroll":
            ev = []
            if cmd.get("dy"): ev.append(_mouse(data=int(cmd["dy"]), flags=M_WHEEL))
            if cmd.get("dx"): ev.append(_mouse(data=int(cmd["dx"]), flags=M_HWHEEL))
            if ev: _send_inputs(ev)
        elif t in ("click", "down", "up"):
            d, u = BTN.get(cmd.get("button", "left"), BTN["left"])
            if t == "click":
                ev = [_mouse(flags=d), _mouse(flags=u)] * (2 if cmd.get("double") else 1)
            else:
                ev = [_mouse(flags=d if t == "down" else u)]
            _send_inputs(ev)

# ======================================================================
#  Minimal WebSocket (stdlib only) for low-latency input
# ======================================================================
WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

def ws_read(rf):
    h = rf.read(2)
    if len(h) < 2: return None, None
    op, masked, n = h[0] & 0x0F, h[1] & 0x80, h[1] & 0x7F
    if n == 126: n = struct.unpack(">H", rf.read(2))[0]
    elif n == 127: n = struct.unpack(">Q", rf.read(8))[0]
    if n > 1 << 20: return None, None
    mask = rf.read(4) if masked else b""
    data = rf.read(n)
    if masked: data = bytes(b ^ mask[i & 3] for i, b in enumerate(data))
    return op, data

def ws_send(wf, data, op=1):
    if isinstance(data, str): data = data.encode()
    n = len(data)
    hdr = bytes([0x80 | op]) + (bytes([n]) if n < 126 else bytes([126]) + struct.pack(">H", n) if n < 65536
                                else bytes([127]) + struct.pack(">Q", n))
    wf.write(hdr + data); wf.flush()

# ======================================================================
#  Phone UI
# ======================================================================
PAGE = r"""<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no,viewport-fit=cover">
<meta name="apple-mobile-web-app-capable" content="yes"><meta name="mobile-web-app-capable" content="yes">
<meta name="theme-color" content="#111"><title>WiFi Keyboard</title><style>
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent;-webkit-user-select:none;user-select:none}
html,body{margin:0;height:100%;overscroll-behavior:none}
body{font-family:system-ui,sans-serif;background:#0f0f12;color:#eee;padding:10px 10px calc(10px + env(safe-area-inset-bottom));display:flex;flex-direction:column;gap:8px}
header{display:flex;align-items:center;justify-content:space-between;font-weight:600}
#st{font-size:12px;display:flex;align-items:center;gap:6px;font-weight:400}
#dot{width:9px;height:9px;border-radius:50%;background:#e55}
.bar{display:flex;gap:6px}
textarea{flex:1;height:64px;font-size:17px;background:#1c1c22;color:#fff;border:1px solid #333;border-radius:12px;padding:10px;resize:none;-webkit-user-select:text;user-select:text}
.tabs{display:flex;background:#1c1c22;border-radius:10px;padding:3px}
.tabs button{border:0;background:transparent;padding:9px}
.tabs button.on{background:#33333d}
.pane{display:none;flex:1;flex-direction:column;gap:6px;min-height:0}.pane.on{display:flex}
.row{display:flex;gap:6px}
button{flex:1;padding:13px 2px;font-size:15px;background:#23232b;color:#eee;border:1px solid #33333d;border-radius:9px;touch-action:manipulation}
button:active,button.press{background:#3a3a46}
button.on.mod,button.on.tog{background:#3b6;color:#000;border-color:#3b6}
button.sm{font-size:11px;padding:10px 0}
#pad{flex:1;min-height:180px;background:#18181e;border:1px solid #33333d;border-radius:14px;touch-action:none;display:flex;align-items:center;justify-content:center;color:#555;font-size:13px;text-align:center;padding:10px}
label{font-size:12px;color:#aaa;display:flex;align-items:center;gap:8px}
input[type=range]{flex:1}
small{color:#777;font-size:11px}
</style></head><body>
<header><span>⌨️ WiFi Keyboard</span><span id="st"><span id="dot"></span><span id="stt">connecting…</span></span></header>
<div class="bar"><textarea id="ta" autocomplete="off" autocorrect="off" autocapitalize="off" spellcheck="false" placeholder="Tap & type, it goes straight to the PC"></textarea>
<div style="display:flex;flex-direction:column;gap:6px;width:76px"><button id="mode" class="tog" style="font-size:12px">Live</button><button id="sendb" style="font-size:12px;display:none">Send ➤</button></div></div>
<div class="tabs"><button data-p="keys" class="on">Keys</button><button data-p="mouse">Touchpad</button><button data-p="media">Media / F-keys</button></div>

<div class="pane on" id="keys">
 <div class="row"><button class="mod" data-m="ctrl">Ctrl</button><button class="mod" data-m="shift">Shift</button><button class="mod" data-m="alt">Alt</button><button class="mod" data-m="win">Win</button></div>
 <div class="row"><button data-k="esc">Esc</button><button data-k="tab">Tab</button><button data-k="backspace" data-r>⌫</button><button data-k="delete" data-r>Del</button><button data-k="enter">⏎</button></div>
 <div class="row"><button data-k="home">Home</button><button data-k="up" data-r>▲</button><button data-k="end">End</button><button data-k="pgup" data-r>PgUp</button></div>
 <div class="row"><button data-k="left" data-r>◀</button><button data-k="down" data-r>▼</button><button data-k="right" data-r>▶</button><button data-k="pgdn" data-r>PgDn</button></div>
 <div class="row"><button data-k="c" data-mods="ctrl">Copy</button><button data-k="v" data-mods="ctrl">Paste</button><button data-k="x" data-mods="ctrl">Cut</button><button data-k="z" data-mods="ctrl" data-r>Undo</button><button data-k="a" data-mods="ctrl">All</button></div>
 <div class="row"><button data-k="tab" data-mods="alt">Alt+Tab</button><button data-k="win">Start</button><button data-k="d" data-mods="win">Desktop</button><button data-k="f4" data-mods="alt">Alt+F4</button><button data-k="s" data-mods="ctrl">Save</button></div>
 <small>Tap a modifier, then type a letter or tap a key (e.g. Ctrl → s). Arrow/⌫ keys repeat while held.</small>
</div>

<div class="pane" id="mouse">
 <div id="pad">1 finger: move · tap: click<br>2 fingers: scroll · 2-finger tap: right-click<br>double-tap & hold: drag</div>
 <div class="row"><button data-b="left">Left</button><button data-b="middle">Middle</button><button data-b="right">Right</button><button id="drag" class="tog">Drag 🔒</button></div>
 <label>Speed <input id="sens" type="range" min="0.5" max="4" step="0.1" value="1.6"></label>
</div>

<div class="pane" id="media">
 <div class="row"><button data-k="prev">⏮</button><button data-k="playpause">⏯</button><button data-k="next">⏭</button></div>
 <div class="row"><button data-k="voldown" data-r>🔉</button><button data-k="mute">🔇</button><button data-k="volup" data-r>🔊</button></div>
 <div class="row" id="f1"></div><div class="row" id="f2"></div>
 <div class="row"><button data-k="printscreen">PrtSc</button><button data-k="s" data-mods="win,shift">Snip</button><button data-k="esc" data-mods="ctrl,shift">TaskMgr</button><button data-k="l" data-mods="win">Lock</button></div>
</div>

<script>
const $=id=>document.getElementById(id), T=new URLSearchParams(location.search).get('t');
const buzz=()=>navigator.vibrate&&navigator.vibrate(8);
// ---------- connection: WebSocket with auto-reconnect, HTTP fallback ----------
let ws=null, wsOk=false, backoff=500, q=Promise.resolve();
function status(ok,txt){$('dot').style.background=ok?'#3c6':'#e55';$('stt').textContent=txt}
function connect(){
 try{ws=new WebSocket((location.protocol==='https:'?'wss://':'ws://')+location.host+'/ws?t='+encodeURIComponent(T));}catch(e){return}
 ws.onopen=()=>{wsOk=true;backoff=500;status(true,'connected')};
 ws.onclose=()=>{wsOk=false;status(false,'reconnecting…');setTimeout(connect,backoff);backoff=Math.min(backoff*2,5000)};
 ws.onerror=()=>ws.close();
}
connect();
setInterval(()=>{if(wsOk)ws.send('{"type":"ping"}')},15000);
document.addEventListener('visibilitychange',()=>{if(!document.hidden&&!wsOk){backoff=300;connect()}});
function send(c){
 if(wsOk&&ws.readyState===1){ws.send(JSON.stringify(c));return}
 c.token=T; q=q.then(()=>fetch('/api',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(c)})
   .then(r=>status(r.ok,r.ok?'connected (http)':'error '+r.status)).catch(()=>status(false,'offline')));
}
// ---------- tabs ----------
document.querySelectorAll('.tabs button').forEach(b=>b.onclick=()=>{
 document.querySelectorAll('.tabs button,.pane').forEach(x=>x.classList.remove('on'));b.classList.add('on');$(b.dataset.p).classList.add('on')});
for(let i=1;i<=12;i++){const b=document.createElement('button');b.className='sm';b.textContent='F'+i;b.dataset.k='f'+i;$(i<=6?'f1':'f2').appendChild(b)}
// ---------- modifiers ----------
const mods=new Set();
const clearMods=()=>{mods.clear();document.querySelectorAll('.mod').forEach(b=>b.classList.remove('on'))};
document.querySelectorAll('.mod').forEach(b=>b.addEventListener('pointerdown',e=>{e.preventDefault();buzz();
 const m=b.dataset.m;mods.has(m)?mods.delete(m):mods.add(m);b.classList.toggle('on')}));
// ---------- key buttons (with hold-to-repeat) ----------
document.querySelectorAll('button[data-k]').forEach(b=>{
 let t1=null,t2=null;
 const fire=m=>send({type:'key',key:b.dataset.k,mods:m});
 const stop=()=>{clearTimeout(t1);clearInterval(t2);b.classList.remove('press')};
 b.addEventListener('pointerdown',e=>{e.preventDefault();buzz();b.classList.add('press');
  const m=b.dataset.mods?b.dataset.mods.split(','):[...mods]; fire(m); if(!b.dataset.mods)clearMods();
  if(b.hasAttribute('data-r')) t1=setTimeout(()=>t2=setInterval(()=>fire(m),45),380);});
 ['pointerup','pointerleave','pointercancel'].forEach(ev=>b.addEventListener(ev,stop));
});
// ---------- typing: Live (diff-based, works with any phone keyboard/IME) or Compose ----------
const ta=$('ta'); let last='', live=true;
$('mode').classList.add('on');
$('mode').onclick=()=>{live=!live;$('mode').textContent=live?'Live':'Compose';$('mode').classList.toggle('on',live);
 $('sendb').style.display=live?'none':'block';ta.value='';last='';ta.placeholder=live?'Tap & type, it goes straight to the PC':'Write, then hit Send ➤';ta.focus()};
$('sendb').onclick=()=>{if(ta.value){send({type:'text',text:ta.value});ta.value='';buzz()}ta.focus()};
ta.addEventListener('input',()=>{
 if(!live)return;
 const v=ta.value;let i=0;while(i<last.length&&i<v.length&&last[i]===v[i])i++;
 const del=[...last.slice(i)].length, add=v.slice(i); last=v;
 if(del)send({type:'backspace',count:del});
 if(add){ if(mods.size){for(const ch of add)send({type:'key',key:ch==='\n'?'enter':ch===' '?'space':ch.toLowerCase(),mods:[...mods]});clearMods();}
          else send({type:'text',text:add}); }
 if(v.endsWith('\n')||v.length>200){ta.value='';last='';}
});
ta.addEventListener('keydown',e=>{
 if(!live||ta.value!=='')return;
 const map={Backspace:'backspace',Enter:'enter',ArrowLeft:'left',ArrowRight:'right',ArrowUp:'up',ArrowDown:'down',Tab:'tab',Escape:'esc',Delete:'delete'};
 if(map[e.key]){e.preventDefault();e.key==='Backspace'?send({type:'backspace',count:1}):send({type:'key',key:map[e.key],mods:[...mods]})}
});
// ---------- touchpad ----------
const pad=$('pad'); let st=0, moved=0, maxT=0, lx=0, ly=0, ax=0, ay=0, sx=0, sy=0, raf=0, lastTap=0, dragging=false, lock=false;
const cen=ts=>{let x=0,y=0;for(const t of ts){x+=t.clientX;y+=t.clientY}return{x:x/ts.length,y:y/ts.length}};
function flush(){raf=0;const s=+$('sens').value;
 const mx=Math.trunc(ax),my=Math.trunc(ay); if(mx||my){send({type:'move',dx:mx,dy:my});ax-=mx;ay-=my}
 const wy=Math.trunc(sy),wx=Math.trunc(sx); if(wy||wx){send({type:'scroll',dy:wy,dx:wx});sy-=wy;sx-=wx}}
pad.addEventListener('touchstart',e=>{e.preventDefault();const n=e.touches.length;maxT=Math.max(maxT,n);
 if(!st){st=Date.now();moved=0;if(n===1&&st-lastTap<280&&!lock){dragging=true;send({type:'down',button:'left'})}}
 const c=cen(e.touches);lx=c.x;ly=c.y},{passive:false});
pad.addEventListener('touchmove',e=>{e.preventDefault();const c=cen(e.touches),dx=c.x-lx,dy=c.y-ly;lx=c.x;ly=c.y;
 moved+=Math.abs(dx)+Math.abs(dy);const s=+$('sens').value;
 if(e.touches.length>=2){sy+=dy*5;sx-=dx*5}else{const a=s*(1+Math.min(Math.hypot(dx,dy)/12,2.5));ax+=dx*a;ay+=dy*a}
 if(!raf)raf=requestAnimationFrame(flush)},{passive:false});
pad.addEventListener('touchend',e=>{e.preventDefault();
 if(e.touches.length){const c=cen(e.touches);lx=c.x;ly=c.y;return}
 const dt=Date.now()-st;
 if(dragging){send({type:'up',button:'left'});dragging=false;lastTap=0}
 else if(dt<220&&moved<8){buzz();send({type:'click',button:maxT>=2?'right':'left'});lastTap=maxT>=2?0:Date.now()}
 st=0;maxT=0},{passive:false});
document.querySelectorAll('button[data-b]').forEach(b=>b.addEventListener('pointerdown',e=>{e.preventDefault();buzz();send({type:'click',button:b.dataset.b})}));
$('drag').onclick=()=>{lock=!lock;$('drag').classList.toggle('on',lock);send({type:lock?'down':'up',button:'left'});buzz()};
</script></body></html>"""

# ======================================================================
#  HTTP server
# ======================================================================
TOKEN = ""

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "PhoneKeyboard/" + VERSION
    def log_message(self, *a): pass

    def _token_ok(self):
        return secrets.compare_digest(parse_qs(urlparse(self.path).query).get("t", [""])[0], TOKEN)

    def _reply(self, code, body=b"", ctype="text/plain; charset=utf-8"):
        self.send_response(code)
        self.send_header("Content-Type", ctype); self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store"); self.end_headers()
        if body: self.wfile.write(body)

    def do_GET(self):
        path = urlparse(self.path).path
        if not self._token_ok():
            self._reply(403, "Wrong or missing token. Use the link printed on the PC.".encode()); return
        if path == "/ws" and self.headers.get("Upgrade", "").lower() == "websocket":
            return self._websocket()
        if path == "/":
            self._reply(200, PAGE.encode(), "text/html; charset=utf-8"); return
        self._reply(404, b"not found")

    def _websocket(self):
        key = self.headers.get("Sec-WebSocket-Key", "")
        accept = base64.b64encode(hashlib.sha1((key + WS_GUID).encode()).digest()).decode()
        self.send_response(101, "Switching Protocols")
        self.send_header("Upgrade", "websocket"); self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", accept); self.end_headers(); self.wfile.flush()
        peer = self.client_address[0]
        print(f"  📱 phone connected: {peer}")
        try:
            while True:
                op, data = ws_read(self.rfile)
                if op is None or op == 8: break
                if op == 9: ws_send(self.wfile, data, op=10); continue      # ping -> pong
                if op != 1: continue
                try:
                    cmd = json.loads(data)
                    if cmd.get("type") != "ping": handle(cmd)
                except Exception as e:
                    print("  bad message:", e)
        except (ConnectionError, OSError):
            pass
        finally:
            print(f"  📴 phone disconnected: {peer}")
            self.close_connection = True

    def do_POST(self):
        if urlparse(self.path).path != "/api": self._reply(404, b"not found"); return
        try:
            cmd = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0)) or 0))
            if not secrets.compare_digest(str(cmd.get("token", "")), TOKEN): self._reply(403, b"bad token"); return
            handle(cmd); self._reply(204)
        except Exception as e:
            self._reply(400, str(e).encode())

# ======================================================================
#  UDP discovery + pairing (used by the Android app)
# ======================================================================
PAIR_PENDING, PAIR_LOCK = [], threading.Lock()

def udp_service(port):
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind(("0.0.0.0", DISCOVERY_PORT))
    except OSError as e:
        print(f"  (auto-discovery off: UDP {DISCOVERY_PORT} busy: {e})"); return
    name = socket.gethostname()
    while True:
        try: data, addr = s.recvfrom(512)
        except OSError: continue
        msg = data.decode("utf-8", "replace").strip()
        if msg == "PK_DISCOVER":
            s.sendto(json.dumps({"name": name, "port": port, "v": VERSION}).encode(), addr)
        elif msg.startswith("PK_PAIR"):
            dev = "".join(c for c in msg[7:].strip() if c.isprintable())[:40] or "Android phone"
            threading.Thread(target=_pair_request, args=(s, addr, dev, port), daemon=True).start()

def _pair_request(s, addr, dev, port):
    req = {"ev": threading.Event(), "ok": False, "ip": addr[0]}
    with PAIR_LOCK:
        if any(r["ip"] == addr[0] for r in PAIR_PENDING) or len(PAIR_PENDING) > 3: return
        PAIR_PENDING.append(req)
    print(f"\n  📲 '{dev}' ({addr[0]}) wants to connect.\n     Type  y  + Enter to allow,  n  to deny.")
    answered = req["ev"].wait(60)
    with PAIR_LOCK:
        if req in PAIR_PENDING: PAIR_PENDING.remove(req)
    ok = answered and req["ok"]
    print(f"  {'✅ paired with' if ok else '❌ denied'} {dev}")
    reply = {"ok": True, "token": TOKEN, "port": port} if ok else {"ok": False}
    try: s.sendto(json.dumps(reply).encode(), addr)
    except OSError: pass

def console_loop():
    """Main thread: answers pairing prompts (y/n)."""
    while True:
        line = sys.stdin.readline() if sys.stdin else ""
        if not line:                      # no console input available
            time.sleep(1); continue
        with PAIR_LOCK:
            req = PAIR_PENDING[0] if PAIR_PENDING else None
        if req:
            req["ok"] = line.strip().lower() in ("y", "yes")
            req["ev"].set()

# ======================================================================
#  Startup helpers
# ======================================================================
def lan_ips():
    ips = []
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try: s.connect(("10.255.255.255", 1)); ips.append(s.getsockname()[0])
    except Exception: pass
    finally: s.close()
    try:
        for ip in socket.gethostbyname_ex(socket.gethostname())[2]:
            if ip not in ips and not ip.startswith("127."): ips.append(ip)
    except Exception: pass
    return ips or ["127.0.0.1"]

def load_token(fresh):
    folder = os.path.join(os.environ.get("APPDATA") or os.path.expanduser("~"), "PhoneKeyboard")
    path = os.path.join(folder, "token.txt")
    if not fresh and os.path.exists(path):
        t = open(path, encoding="utf-8").read().strip()
        if t: return t
    t = secrets.token_urlsafe(8)
    os.makedirs(folder, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f: f.write(t)
    return t

def relaunch_as_admin():
    if sys.platform != "win32" or ctypes.windll.shell32.IsUserAnAdmin(): return
    args = [a for a in sys.argv[1:]]
    if not getattr(sys, "frozen", False): args = [os.path.abspath(sys.argv[0])] + args
    params = " ".join(f'"{a}"' for a in args)
    if ctypes.windll.shell32.ShellExecuteW(None, "runas", sys.executable, params, None, 1) > 32:
        sys.exit(0)
    print("  (admin elevation was cancelled, running normally)")

def main():
    global TOKEN
    ap = argparse.ArgumentParser(description="Use your phone as a WiFi keyboard + touchpad for this PC.")
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--new-token", action="store_true", help="generate a new secret link (old bookmarks stop working)")
    ap.add_argument("--admin", action="store_true", help="run elevated so you can type into admin windows")
    a = ap.parse_args()
    if a.admin: relaunch_as_admin()
    if sys.platform == "win32":
        try: ctypes.windll.kernel32.SetConsoleTitleW("Phone WiFi Keyboard")
        except Exception: pass
        os.system("")  # enable ANSI/UTF-8 friendly console
        try: sys.stdout.reconfigure(encoding="utf-8")
        except Exception: pass
    TOKEN = load_token(a.new_token)
    try:
        srv = ThreadingHTTPServer(("0.0.0.0", a.port), Handler)
    except OSError:
        sys.exit(f"Port {a.port} is busy. Try:  python {os.path.basename(sys.argv[0])} --port 9000")
    srv.daemon_threads = True
    urls = [f"http://{ip}:{a.port}/?t={TOKEN}" for ip in lan_ips()]
    print("=" * 60)
    print(f"  Phone WiFi Keyboard v{VERSION} is running")
    print(f"  Open this on your phone (same WiFi):\n\n     {urls[0]}\n")
    for u in urls[1:]: print(f"     alt: {u}")
    try:
        import qrcode
        qr = qrcode.QRCode(border=1); qr.add_data(urls[0]); qr.print_ascii(invert=True)
    except ImportError:
        print("  Tip: `pip install qrcode` to show a scannable QR code here.")
    if sys.platform == "win32" and not ctypes.windll.shell32.IsUserAnAdmin():
        print("  Note: can't type into admin windows. Use --admin if you need that.")
    print("  If the phone can't connect: allow Python on PRIVATE networks in the")
    print("  Windows firewall popup, and make sure your WiFi is set to 'Private'.")
    print(f"  Android app: just open it, this PC shows up automatically.")
    print("  Ctrl+C to stop.")
    print("=" * 60)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    threading.Thread(target=udp_service, args=(a.port,), daemon=True).start()
    try: console_loop()
    except (KeyboardInterrupt, EOFError): print("\n  bye 👋")

if __name__ == "__main__":
    main()
