package heipsys.trpg;

import com.google.gson.Gson;
import com.google.gson.JsonElement;

/**
 * 절단(truncated)된 JSON 응답 복구 — 균형 잡힌 prefix 로 잘라 파싱 가능한 형태를 만든다.
 * .gdam 청크/단일 생성(32K)이 max_tokens 절단·경미한 깨짐으로 파싱 실패할 때, 값비싼 재생성 전에
 * 저렴하게 복구를 시도한다 (GdamGenerator.tryParseObject/tryParseArray 의 마지막 폴백).
 *
 * ★비용 최적화(ec6c865a 참조본에서 이식)★: 잘린 앞부분 항목이라도 살려 재호출 1회를 아낀다.
 * 순수 문자열 유틸(AI 호출 없음)이라 provider 와 무관하게 동작한다.
 */
public final class JsonSalvage {
    private static final Gson GSON = new Gson();  // 파싱 유효성 검사 전용 (출력 직렬화 X)
    private JsonSalvage() {}

    /**
     * 잘린 JSON 복구 — 최대 6회 백오프하며 시도.
     *  - 트레일링 , : 공백 제거 → 열린 ", [, { 자동 닫기 → 파싱 시도
     *  - 실패시 마지막 , 또는 { / [ 직전까지 자르고 재시도
     * @return 파싱에 성공한 균형 prefix, 복구 실패시 null
     */
    public static String salvageTruncatedJson(String s) {
        if (s == null || s.isEmpty()) return null;
        String current = s.trim();
        for (int attempt = 0; attempt < 6; attempt++) {
            // 트레일링 노이즈 제거
            while (!current.isEmpty()) {
                char last = current.charAt(current.length() - 1);
                if (last == ',' || last == ':' || Character.isWhitespace(last)) {
                    current = current.substring(0, current.length() - 1);
                } else break;
            }
            String candidate = balanceBrackets(stripTrailingCommas(escapeRawControlCharsInStrings(current)));
            try {
                GSON.fromJson(candidate, JsonElement.class);
                return candidate;
            } catch (Exception ignore) {}
            // 백오프: 마지막 , 또는 { / [ 직전까지 자르기
            int lastComma = current.lastIndexOf(',');
            int lastOpenObj = current.lastIndexOf('{');
            int lastOpenArr = current.lastIndexOf('[');
            int cutoff = Math.max(lastComma, Math.max(lastOpenObj, lastOpenArr));
            if (cutoff <= 0) break;
            current = current.substring(0, cutoff);
        }
        return null;
    }

    /**
     * ★경미한 문법 오류 교정 — 트레일링 콤마 제거★ (파싱 전 저렴한 보정, 절단과 무관하게 항상 적용 가능).
     * 소형·대형 모델이 객체·배열 끝에 콤마를 흘리면( {"a":1,} · [1,2,] ) Gson이 "Expected name/value"로
     * 파싱을 통째로 실패시킨다(예: $.timeline.main_events[].branches). 문자열 리터럴 내부의 콤마는 절대
     * 건드리지 않게 문자열-인지 스캔으로, ',' 다음 첫 비공백이 '}' 또는 ']' 이면 그 콤마만 버린다.
     * ★유효한 JSON에는 트레일링 콤마가 없으므로 무해(멱등)★ — 깨진 응답만 살린다.
     */
    public static String stripTrailingCommas(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder sb = new StringBuilder(s.length());
        boolean inStr = false, escape = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escape) { sb.append(c); escape = false; continue; }
            if (c == '\\') { sb.append(c); escape = true; continue; }
            if (c == '"') { inStr = !inStr; sb.append(c); continue; }
            if (!inStr && c == ',') {
                int j = i + 1;
                while (j < s.length() && Character.isWhitespace(s.charAt(j))) j++;
                if (j < s.length() && (s.charAt(j) == '}' || s.charAt(j) == ']')) continue; // 트레일링 콤마 스킵
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 문자열 리터럴 내부의 raw 제어문자(\n \r \t 등 <0x20)를 escape 시퀀스로 치환.
     * 소형 모델이 서술 문자열 안에 줄바꿈을 escape 없이 뱉으면 따옴표를 닫아도 JSON 이 invalid →
     * 절단 살베이지가 6회 백오프 내내 실패하는 케이스 복구.
     * (문자열 안 unescaped 따옴표는 근본적으로 모호 → 복구 불가, 호출부 폴백에 맡김)
     */
    static String escapeRawControlCharsInStrings(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        boolean inStr = false, escape = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escape) { sb.append(c); escape = false; continue; }
            if (c == '\\') { sb.append(c); escape = true; continue; }
            if (c == '"') { inStr = !inStr; sb.append(c); continue; }
            if (inStr && c < 0x20) {
                switch (c) {
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:   sb.append(' ');  // 기타 제어문자 → 공백
                }
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** 열린 괄호/대괄호/문자열을 닫아 균형 맞춤. 문자열 리터럴 내부의 괄호/이스케이프는 무시. */
    public static String balanceBrackets(String s) {
        int objOpen = 0, arrOpen = 0;
        boolean inStr = false;
        boolean escape = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escape) { escape = false; continue; }
            if (c == '\\') { escape = true; continue; }
            if (c == '"') { inStr = !inStr; continue; }
            if (inStr) continue;
            if (c == '{') objOpen++;
            else if (c == '}') objOpen--;
            else if (c == '[') arrOpen++;
            else if (c == ']') arrOpen--;
        }
        StringBuilder sb = new StringBuilder(s);
        // 문자열 도중에 잘렸으면 " 닫기
        if (inStr) sb.append('"');
        while (arrOpen-- > 0) sb.append(']');
        while (objOpen-- > 0) sb.append('}');
        return sb.toString();
    }
}
