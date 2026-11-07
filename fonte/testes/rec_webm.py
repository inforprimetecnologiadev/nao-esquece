"""Grava 3 s com o MediaRecorder do Chrome (microfone falso), igual ao que o app grava no WebView."""
import asyncio, base64, sys
from playwright.async_api import async_playwright
async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch(executable_path='/usr/bin/google-chrome', args=['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream'])
        pg = await b.new_page()
        await pg.goto('http://localhost:8765/manifest.json')
        b64 = await pg.evaluate("""(async()=>{ const s=await navigator.mediaDevices.getUserMedia({audio:true}); const r=new MediaRecorder(s,{mimeType:'audio/webm;codecs=opus'}); const ch=[];
          r.ondataavailable=e=>ch.push(e.data); r.start(1000); await new Promise(x=>setTimeout(x,3200)); await new Promise(x=>{ r.onstop=x; r.stop(); });
          const bl=new Blob(ch,{type:'audio/webm'}); const u=new Uint8Array(await bl.arrayBuffer()); let s2=''; for(let i=0;i<u.length;i++) s2+=String.fromCharCode(u[i]); return btoa(s2); })()""")
        open(sys.argv[1], 'wb').write(base64.b64decode(b64)); await b.close()
asyncio.run(main())
