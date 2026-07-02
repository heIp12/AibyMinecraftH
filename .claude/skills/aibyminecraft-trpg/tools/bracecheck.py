#!/usr/bin/env python3
# 괄호 균형 검사기 — 컴파일 불가 환경에서 Java/HTML 편집 후 1차 검증.
# 문자열·문자·라인주석·블록주석·Java 텍스트블록(""")을 건너뛰고 ()[]{} 짝을 확인한다.
# 한계: JS 정규식 리터럴(/.../ 안의 괄호·문자클래스)은 인식 못 함 → HTML/JS는
#       반드시 <script> 추출 후 `node --check`가 최종 판정(아래 viewer_verify.py 참조).
# 사용: python bracecheck.py <file1> [file2 ...]   결과 "OK 0/0/0" 이면 통과.
import sys
def check(path):
    s=open(path,encoding='utf-8').read()
    i=0;n=len(s);st=[];line=1
    pairs={')':'(',']':'[','}':'{'}
    op={'(','[','{'}
    cl={')',']','}'}
    while i<n:
        c=s[i]
        if c=='\n': line+=1;i+=1;continue
        if c=='/' and i+1<n and s[i+1]=='/':
            while i<n and s[i]!='\n': i+=1
            continue
        if c=='/' and i+1<n and s[i+1]=='*':
            i+=2
            while i+1<n and not(s[i]=='*' and s[i+1]=='/'):
                if s[i]=='\n': line+=1
                i+=1
            i+=2;continue
        if c=='"' and i+2<n and s[i+1]=='"' and s[i+2]=='"':
            i+=3
            while i+2<n and not(s[i]=='"' and s[i+1]=='"' and s[i+2]=='"'):
                if s[i]=='\n': line+=1
                i+=1
            i+=3;continue
        if c=='"':
            i+=1
            while i<n and s[i]!='"':
                if s[i]=='\\': i+=2;continue
                if s[i]=='\n': line+=1
                i+=1
            i+=1;continue
        if c=="'":
            i+=1
            while i<n and s[i]!="'":
                if s[i]=='\\': i+=2;continue
                i+=1
            i+=1;continue
        if c in op: st.append((c,line))
        elif c in cl:
            if not st or st[-1][0]!=pairs[c]:
                print(f"{path}: MISMATCH {c} at line {line} (stack top {st[-1] if st else None})")
                return
            st.pop()
        i+=1
    if st:
        print(f"{path}: UNCLOSED {st[-3:]} ")
    else:
        print(f"{path}: OK 0/0/0")
for p in sys.argv[1:]: check(p)
