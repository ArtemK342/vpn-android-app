package ru.fsociety.vpn

/**
 * Rules for a new password — the same as the server's (vpn-site api/password_policy.py): 8–128 characters,
 * an uppercase and a lowercase letter (any alphabet). Popular passwords («Qwerty123») are rejected by the server
 * only; its message is shown as the error.
 */
fun passwordRulesOk(p: String): Boolean =
    p.length in 8..128 && p.any { it.isUpperCase() } && p.any { it.isLowerCase() }
