package dev.zipshare.data.model

import java.net.URLEncoder

/**
 * Builds the `otpauth://` URI that authenticator apps understand, so a secret can be handed to
 * one with a tap instead of being typed in by hand.
 *
 * Matches what Zipline's own QR encodes (otplib `keyuri`): the label is `issuer:username` and the
 * issuer is repeated as a parameter, which is what makes an authenticator group the entry under
 * the right service name.
 *
 * The issuer defaults to `Zipline` because that is the server's own default; an instance that sets
 * `mfa.totp.issuer` only changes the name shown in the authenticator list, never the codes - those
 * depend on the secret alone.
 */
fun otpauthUri(secret: String, username: String, issuer: String = "Zipline"): String {
    // Colons separate the label's two halves, so neither half may contain a raw one.
    val label = "${enc(issuer)}:${enc(username)}"
    return "otpauth://totp/$label?secret=${enc(secret)}&issuer=${enc(issuer)}"
}

/**
 * `URLEncoder` is form encoding, not URI encoding: it turns a space into `+`, which an
 * authenticator would show literally. `%20` is what the spec wants.
 */
private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
