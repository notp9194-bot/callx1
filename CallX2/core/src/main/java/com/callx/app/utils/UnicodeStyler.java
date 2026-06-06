package com.callx.app.utils;

/**
 * UnicodeStyler — Normal ASCII text ko Unicode Mathematical Script characters
 * mein convert karta hai.
 *
 * Example: "Hello" → "𝐻𝑒𝓁𝓁𝑜"
 *
 * Yeh actual font nahi hai — Unicode codepoints hain jo har device pe
 * same dikhte hain, bina kisi font install kiye.
 *
 * Usage:
 *   String styled = UnicodeStyler.toScript("Hello this is samsung j7 style text");
 *   // → "𝐻𝑒𝓁𝓁𝑜 𝓉𝒽𝒾𝓈 𝒾𝓈 𝓈𝒶𝓂𝓈𝓊𝓃𝑔 𝒿7 𝓈𝓉𝓎𝓁𝑒 𝓉𝑒𝓍𝓉"
 */
public class UnicodeStyler {

    // ── Mathematical Script Unicode block ────────────────────────────────
    // Uppercase: U+1D49C series (𝒜𝒞𝒟...) — some are missing, use fallback
    // Lowercase: U+1D4B6 series (𝒶𝒷𝒸...)
    //
    // Missing uppercase codepoints in Mathematical Script block have
    // dedicated Unicode alternatives (B, E, F, H, I, L, M, R, e, g, o):
    //   B → ℬ (U+212C)   E → ℰ (U+2130)   F → ℱ (U+2131)
    //   H → ℋ (U+210B)   I → ℐ (U+2110)   L → ℒ (U+2112)
    //   M → ℳ (U+2133)   R → ℛ (U+211B)
    // Lowercase:
    //   e → ℯ (U+212F)   g → ℊ (U+210A)   o → ℴ (U+2134)

    private static final int[] UPPER_SCRIPT = {
        0x1D49C, // A → 𝒜
        0x212C,  // B → ℬ
        0x1D49E, // C → 𝒞
        0x1D49F, // D → 𝒟
        0x2130,  // E → ℰ
        0x2131,  // F → ℱ
        0x1D4A2, // G → 𝒢
        0x210B,  // H → ℋ
        0x2110,  // I → ℐ
        0x1D4A5, // J → 𝒥
        0x1D4A6, // K → 𝒦
        0x2112,  // L → ℒ
        0x2133,  // M → ℳ
        0x1D4A9, // N → 𝒩
        0x1D4AA, // O → 𝒪
        0x1D4AB, // P → 𝒫
        0x1D4AC, // Q → 𝒬
        0x211B,  // R → ℛ
        0x1D4AE, // S → 𝒮
        0x1D4AF, // T → 𝒯
        0x1D4B0, // U → 𝒰
        0x1D4B1, // V → 𝒱
        0x1D4B2, // W → 𝒲
        0x1D4B3, // X → 𝒳
        0x1D4B4, // Y → 𝒴
        0x1D4B5, // Z → 𝒵
    };

    private static final int[] LOWER_SCRIPT = {
        0x1D4B6, // a → 𝒶
        0x1D4B7, // b → 𝒷
        0x1D4B8, // c → 𝒸
        0x1D4B9, // d → 𝒹
        0x212F,  // e → ℯ
        0x1D4BB, // f → 𝒻
        0x210A,  // g → ℊ
        0x1D4BD, // h → 𝒽
        0x1D4BE, // i → 𝒾
        0x1D4BF, // j → 𝒿
        0x1D4C0, // k → 𝓀
        0x1D4C1, // l → 𝓁
        0x1D4C2, // m → 𝓂
        0x1D4C3, // n → 𝓃
        0x2134,  // o → ℴ
        0x1D4C5, // p → 𝓅
        0x1D4C6, // q → 𝓆
        0x1D4C7, // r → 𝓇
        0x1D4C8, // s → 𝓈
        0x1D4C9, // t → 𝓉
        0x1D4CA, // u → 𝓊
        0x1D4CB, // v → 𝓋
        0x1D4CC, // w → 𝓌
        0x1D4CD, // x → 𝓍
        0x1D4CE, // y → 𝓎
        0x1D4CF, // z → 𝓏
    };

    /**
     * Normal ASCII text ko Unicode Mathematical Script mein convert karo.
     * Non-ASCII characters (numbers, emoji, punctuation) as-is rahenge.
     *
     * @param input  Original text
     * @return       Script-styled text
     */
    public static String toScript(String input) {
        if (input == null || input.isEmpty()) return input;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (c >= 'A' && c <= 'Z') {
                sb.appendCodePoint(UPPER_SCRIPT[c - 'A']);
            } else if (c >= 'a' && c <= 'z') {
                sb.appendCodePoint(LOWER_SCRIPT[c - 'a']);
            } else {
                // Numbers, spaces, punctuation, emoji — as-is
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Script text ko wapas normal ASCII mein convert karo (best-effort).
     * Useful agar user edit karna chahe.
     * Note: Surrogate pairs handle kiye hain Unicode SMP characters ke liye.
     */
    public static String fromScript(String input) {
        if (input == null || input.isEmpty()) return input;

        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < input.length()) {
            int cp = input.codePointAt(i);
            i += Character.charCount(cp);

            boolean matched = false;

            for (int j = 0; j < UPPER_SCRIPT.length; j++) {
                if (cp == UPPER_SCRIPT[j]) {
                    sb.append((char) ('A' + j));
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                for (int j = 0; j < LOWER_SCRIPT.length; j++) {
                    if (cp == LOWER_SCRIPT[j]) {
                        sb.append((char) ('a' + j));
                        matched = true;
                        break;
                    }
                }
            }
            if (!matched) {
                sb.appendCodePoint(cp);
            }
        }
        return sb.toString();
    }

    /**
     * Preview string generate karo style picker ke liye.
     */
    public static String preview() {
        return toScript("Samsung Style");
    }
}
