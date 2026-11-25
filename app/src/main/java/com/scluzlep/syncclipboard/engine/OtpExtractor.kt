package com.scluzlep.syncclipboard.engine

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe time-windowed SHA-256 deduplication cache shared between OTP notification
 * capture and clipboard upload so tapping "复制验证码" never double-uploads within 10s.
 */
class RecentHashDedup(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val nowProvider: () -> Long = { System.currentTimeMillis() }
) {
    private val entries = ConcurrentHashMap<String, Long>()

    fun tryClaimText(text: String): Boolean {
        return tryClaimHash(sha256Upper(text))
    }

    fun tryClaimHash(hashUpper: String): Boolean {
        val normalized = hashUpper.uppercase()
        val now = nowProvider()
        while (true) {
            val existingExpiry = entries[normalized]
            if (existingExpiry != null && now < existingExpiry) {
                return false
            }
            val nextExpiry = now + windowMs
            val won = if (existingExpiry == null) {
                entries.putIfAbsent(normalized, nextExpiry) == null
            } else {
                entries.replace(normalized, existingExpiry, nextExpiry)
            }
            if (won) {
                if (entries.size > MAX_ENTRIES_BEFORE_PRUNE) {
                    pruneExpired(now)
                }
                return true
            }
        }
    }

    fun isRecentText(text: String): Boolean = isRecentHash(sha256Upper(text))

    fun isRecentHash(hashUpper: String): Boolean {
        val expiry = entries[hashUpper.uppercase()] ?: return false
        return nowProvider() < expiry
    }

    fun clear() {
        entries.clear()
    }

    private fun pruneExpired(now: Long) {
        entries.entries.removeIf { it.value <= now }
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 10_000L
        private const val MAX_ENTRIES_BEFORE_PRUNE = 64

        fun sha256Upper(text: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02X".format(it) }
        }
    }
}

/**
 * Extracts 4–8 digit OTP verification codes from notification text with CN/EN keyword context.
 * Never logs notification body or extracted OTP plaintext.
 */
object OtpExtractor {
    private val CN_KEYWORDS = listOf(
        "验证码", "校验码", "动态码", "动态密码", "登录码",
        "安全码", "确认码", "授权码", "短信码", "认证码",
        "一次性密码", "随机码", "取件码"
    )

    private val EN_KEYWORD_REGEX = Regex(
        """(?i)\b(?:verification\s+code|verify\s+code|security\s+code|auth(?:entication)?\s+code|login\s+code|one[- ]time\s+(?:password|passcode|code)|passcode|otp|pin|code)\b"""
    )

    private val CN_KEYWORD_REGEX = Regex(
        CN_KEYWORDS.joinToString(separator = "|") { Regex.escape(it) }
    )

    private val CODE_CANDIDATE_REGEX = Regex("""(?<!\d)\d{4,8}(?!\d)""")
    private val DATE_LIKE_AROUND_REGEX = Regex("""\d{4}[-/]\d{1,2}[-/]\d{1,2}""")
    private val REJECT_AFTER_UNITS = Regex("""^\s*(?:年|月|日|时|分|秒|分钟|小时|天|元|块|折|%|px|mb|gb|kb|ms|s)\b""", RegexOption.IGNORE_CASE)
    private val REJECT_BEFORE_CURRENCY = Regex("""[¥￥$€£]\s*$""")

    /**
     * Fast pre-filter (mirroring `SystemNotificationHooker` pre-screen).
     */
    fun hasOtpContext(text: String): Boolean {
        if (text.length < 6) return false
        return CN_KEYWORD_REGEX.containsMatchIn(text) || EN_KEYWORD_REGEX.containsMatchIn(text)
    }

    /**
     * Extracts a 4–8 digit verification code when CN/EN keyword context is present, or null.
     */
    fun extract(text: String): String? {
        if (text.isBlank()) return null

        val keywordMatches = (CN_KEYWORD_REGEX.findAll(text) + EN_KEYWORD_REGEX.findAll(text))
            .sortedBy { it.range.first }
            .toList()
        if (keywordMatches.isEmpty()) return null

        val candidates = CODE_CANDIDATE_REGEX.findAll(text)
            .filter { match -> isPlausibleCode(text, match) }
            .toList()
        if (candidates.isEmpty()) return null

        // 1. Prefer a valid 4-8 digit token shortly after a keyword (up to 32 chars).
        for (kw in keywordMatches) {
            val after = candidates.firstOrNull { c ->
                c.range.first >= kw.range.last && (c.range.first - kw.range.last) <= 32
            }
            if (after != null) return after.value
        }

        // 2. Otherwise accept a valid 4-8 digit token shortly before a keyword (e.g., "123456 is your verification code").
        for (kw in keywordMatches) {
            val before = candidates.lastOrNull { c ->
                c.range.last < kw.range.first && (kw.range.first - c.range.last) <= 32
            }
            if (before != null) return before.value
        }

        // 3. Fallback: nearest valid token within 80 chars of any keyword.
        var best: MatchResult? = null
        var bestDist = Int.MAX_VALUE
        for (kw in keywordMatches) {
            for (c in candidates) {
                val dist = when {
                    c.range.first >= kw.range.last -> c.range.first - kw.range.last
                    else -> kw.range.first - c.range.last
                }
                if (dist in 0..80 && dist < bestDist) {
                    bestDist = dist
                    best = c
                }
            }
        }
        return best?.value
    }

    private fun isPlausibleCode(fullText: String, match: MatchResult): Boolean {
        val start = match.range.first
        val endExclusive = match.range.last + 1

        // Reject decimal numbers like 1234.56 or version numbers like 1.4829,
        // while allowing sentence-ending periods like "passcode is 4829. Do not share."
        if (start > 0 && fullText[start - 1] == '.' && (start == 1 || fullText[start - 2].isDigit())) return false
        if (endExclusive + 1 < fullText.length && fullText[endExclusive] == '.' && fullText[endExclusive + 1].isDigit()) return false

        // Reject currency prefix like ¥1234 or $5000
        val beforeWindow = fullText.substring((start - 4).coerceAtLeast(0), start)
        if (REJECT_BEFORE_CURRENCY.containsMatchIn(beforeWindow)) return false

        // Reject unit/date suffix like 2026年, 1000元, 3000ms
        val afterWindow = fullText.substring(endExclusive, (endExclusive + 8).coerceAtMost(fullText.length))
        if (REJECT_AFTER_UNITS.containsMatchIn(afterWindow)) return false

        // Reject YYYY inside YYYY-MM-DD or YYYY/MM/DD
        val aroundWindow = fullText.substring(
            (start - 6).coerceAtLeast(0),
            (endExclusive + 8).coerceAtMost(fullText.length)
        )
        val dateMatch = DATE_LIKE_AROUND_REGEX.find(aroundWindow)
        if (dateMatch != null) {
            val absStart = (start - 6).coerceAtLeast(0) + dateMatch.range.first
            val absEnd = (start - 6).coerceAtLeast(0) + dateMatch.range.last + 1
            if (start >= absStart && endExclusive <= absEnd) return false
        }

        return true
    }
}
