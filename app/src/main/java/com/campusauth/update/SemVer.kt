package com.campusauth.update

/**
 * Loose semantic version: "1.2.3", "v1.2.3", "1.2.3-beta1", "1.2".
 * Missing minor/patch parts default to 0; [pre] is the prerelease tag if any.
 */
internal data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val pre: String,
)

/**
 * Parse a version-ish string. Returns null when it doesn't start with a
 * number (e.g. "dev"), so callers can skip the comparison entirely.
 */
internal fun parseVersion(raw: String): SemVer? {
    var s = raw.trim()
    if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1)

    val dash = s.indexOf('-')
    val plus = s.indexOf('+')
    val end = listOf(dash, plus).filter { it >= 0 }.minOrNull() ?: s.length
    val core = s.substring(0, end)
    val pre = if (dash in 0 until s.length - 1) s.substring(dash + 1).substringBefore('+') else ""

    val parts = core.split('.')
    val major = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
    val minor = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
    val patch = parts.getOrNull(2)?.trim()?.toIntOrNull() ?: 0
    return SemVer(major, minor, patch, pre)
}

/** Standard semver-ish ordering. A release outranks its own prereleases. */
internal fun compareVersions(a: SemVer, b: SemVer): Int {
    compareValuesBy(a, b, { it.major }, { it.minor }, { it.patch }).let { if (it != 0) return it }
    return when {
        a.pre.isEmpty() && b.pre.isEmpty() -> 0
        a.pre.isEmpty() -> 1   // 1.0.0 > 1.0.0-beta
        b.pre.isEmpty() -> -1
        else -> a.pre.compareTo(b.pre)
    }
}

/**
 * True only when [remoteTag] is a strictly newer version than [localVersion].
 * Unparseable versions never count as "newer" — we don't nag on dev builds.
 */
internal fun isRemoteNewer(remoteTag: String, localVersion: String): Boolean {
    val remote = parseVersion(remoteTag) ?: return false
    val local = parseVersion(localVersion) ?: return false
    return compareVersions(remote, local) > 0
}
