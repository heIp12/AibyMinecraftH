#!/usr/bin/env python3
"""
log-viewer.html 검증 하니스 (헤드리스 크로미엄). 컴파일 불가 환경에서 뷰어 변경을
실제 렌더로 확인한다. 두 가지를 한다: (1) <script> 추출 후 node --check(문법),
(2) 로그(+선택 시나리오)를 주입해 브라우저에서 loadLogText 실행 후 프로브 JS 결과 dump.

사용:
  python viewer_verify.py --viewer <log-viewer.html> --log <a.jsonl|a.txt>
                          [--scenario <s.json>] [--probe <probe.js>] [--name NAME]
  - --probe 파일이 없으면 기본 프로브(EVENTS 수·VIEWPOINTS·통화내역 수)를 출력.
  - 프로브 JS는 loadLogText 호출 뒤 실행되며, 마지막 표현식/`return`이 아니라
    전역 함수·변수(EVENTS, VIEWPOINTS, visibleTo, filtered, zoneMapSvg, curVP 등)를
    자유롭게 읽어 ★객체를 out 변수에 담으면★ JSON으로 찍힌다. 예:
        out = { total: EVENTS.length, comm: EVENTS.filter(e=>COMM_KINDS.has(e.kind)).length };

동작 원리(직접 하니스를 짤 때 참고):
  · <script>(src 없는 것)만 이어붙여 node --check.
  · loadLogText(name, text) 가 진입점. 시나리오는 SCENARIO=... 로 주입 후 renderInfo 계열.
  · 화자별 시점 검사는 curVP 를 세팅하고 filtered()/visibleTo(e,vp) 확인.
  · 헤드리스 실행: headless_shell --headless --no-sandbox --disable-gpu
      --virtual-time-budget=6000 --run-all-compositor-stages-before-draw --dump-dom
"""
import sys, os, re, json, subprocess, glob, argparse, tempfile

def find_chrome():
    for pat in ("/opt/pw-browsers/chromium_headless_shell-*/chrome-linux/headless_shell",
                "/opt/pw-browsers/chromium-*/chrome-linux/chrome"):
        hits = sorted(glob.glob(pat))
        if hits: return hits[-1]
    return None

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--viewer", required=True)
    ap.add_argument("--log", required=True)
    ap.add_argument("--scenario")
    ap.add_argument("--probe")
    ap.add_argument("--name", default="verify#1.events.jsonl")
    a = ap.parse_args()

    html = open(a.viewer, encoding="utf-8").read()
    # (1) 문법 검사
    blocks = re.findall(r'<script(?![^>]*\bsrc=)[^>]*>(.*?)</script>', html, re.S)
    tmp = tempfile.NamedTemporaryFile("w", suffix=".js", delete=False, encoding="utf-8")
    tmp.write("\n;\n".join(blocks)); tmp.close()
    nc = subprocess.run(["node", "--check", tmp.name], capture_output=True, text=True)
    print("NODE:", "OK" if nc.returncode == 0 else nc.stderr.strip())
    if nc.returncode != 0: sys.exit(1)

    log = open(a.log, encoding="utf-8").read()
    scen = open(a.scenario, encoding="utf-8").read() if a.scenario else "null"
    probe = open(a.probe, encoding="utf-8").read() if a.probe else \
        "out = { events: EVENTS.length, viewpoints: VIEWPOINTS.length, " \
        "comm: EVENTS.filter(function(e){return COMM_KINDS.has(e.kind);}).length };"
    driver = ("<script>window.__LOG=%s;window.__SCEN=%s;</script>\n<script>\n"
        "window.addEventListener('load',function(){setTimeout(function(){\n"
        " try{ if(window.__SCEN)SCENARIO=window.__SCEN; loadLogText(%s, window.__LOG);\n"
        "  var out={}; %s\n"
        "  document.title='RESULT:'+JSON.stringify(out);\n"
        "  var el=document.createElement('pre');el.id='__result';el.textContent=document.title;document.body.appendChild(el);\n"
        " }catch(err){var el=document.createElement('pre');el.id='__result';el.textContent='ERR:'+(err.stack||err.message);document.body.appendChild(el);}\n"
        "},300);});\n</script>") % (json.dumps(log), scen, json.dumps(a.name), probe)
    harness = html.replace("</body>", driver + "\n</body>")
    hf = tempfile.NamedTemporaryFile("w", suffix=".html", delete=False, encoding="utf-8")
    hf.write(harness); hf.close()

    chrome = find_chrome()
    if not chrome: print("CHROME: not found under /opt/pw-browsers"); sys.exit(2)
    r = subprocess.run([chrome, "--headless", "--no-sandbox", "--disable-gpu",
        "--virtual-time-budget=6000", "--run-all-compositor-stages-before-draw",
        "--dump-dom", "file://" + hf.name], capture_output=True, text=True)
    m = re.search(r'id="__result">([^<]*)', r.stdout)
    print(m.group(1) if m else "(no result element — render failed or timed out)")

if __name__ == "__main__":
    main()
