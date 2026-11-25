package io.tolgee.security.thirdParty

import io.jsonwebtoken.Claims
import io.jsonwebtoken.JwsHeader
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.SigningKeyResolverAdapter
import io.tolgee.component.CurrentDateProvider
import io.tolgee.configuration.tolgee.TolgeeProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestTemplate
import java.math.BigInteger
import java.security.Key
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/**
 * Verifies the JWT Cloudflare Access stamps on every request it proxies (`Cf-Access-Jwt-Assertion`).
 *
 * The origin only accepts traffic from Cloudflare, but the header is still nothing more than a string
 * until its signature is checked, so nothing here is skipped: RS256 against the team's published keys,
 * issuer, audience and expiry.
 */
@Component
class CloudflareAccessTokenVerifier(
  private val tolgeeProperties: TolgeeProperties,
  private val restTemplate: RestTemplate,
  private val currentDateProvider: CurrentDateProvider,
) {
  private val logger = LoggerFactory.getLogger(javaClass)

  @Volatile
  private var keys: Map<String, PublicKey> = emptyMap()

  @Volatile
  private var keysFetchedAt = 0L

  /**
   * @return the verified, lowercased e-mail of the person behind the token
   * @throws InvalidCloudflareAccessTokenException when the token cannot be trusted or is not a person's
   */
  fun verifyAndGetEmail(token: String): String {
    val properties = tolgeeProperties.authentication.cloudflareAccess
    val claims =
      try {
        Jwts
          .parserBuilder()
          .setClock { currentDateProvider.date }
          .setAllowedClockSkewSeconds(CLOCK_SKEW_SECONDS)
          .requireIssuer(properties.issuer)
          .setSigningKeyResolver(keyResolver)
          .build()
          .parseClaimsJws(token)
          .body
      } catch (e: JwtException) {
        throw InvalidCloudflareAccessTokenException(e.message ?: e.javaClass.simpleName, e)
      } catch (e: IllegalArgumentException) {
        throw InvalidCloudflareAccessTokenException(e.message ?: e.javaClass.simpleName, e)
      }

    if (claims.expiration == null) {
      throw InvalidCloudflareAccessTokenException("token has no expiry")
    }

    // jjwt 0.11 can only require a single string audience; Access sends an array
    val audiences =
      when (val aud = claims["aud"]) {
        is String -> listOf(aud)
        is Collection<*> -> aud.filterIsInstance<String>()
        else -> emptyList()
      }
    if (properties.audience !in audiences) {
      throw InvalidCloudflareAccessTokenException("token was issued for a different Access application")
    }

    // A service token identifies itself with `common_name` and carries no e-mail: it is a machine,
    // and machines authenticate with API keys.
    val email = (claims["email"] as? String)?.trim()
    if (email.isNullOrEmpty() || !email.contains('@')) {
      throw InvalidCloudflareAccessTokenException("token does not identify a person")
    }
    return email.lowercase()
  }

  private val keyResolver =
    object : SigningKeyResolverAdapter() {
      override fun resolveSigningKey(
        header: JwsHeader<*>,
        claims: Claims,
      ): Key {
        // Pinning the algorithm closes the door on `none` and on HMAC signed with the public key
        if (header.algorithm != "RS256") {
          throw JwtException("unsupported algorithm ${header.algorithm}")
        }
        val keyId = header.keyId ?: throw JwtException("token has no key id")
        return findKey(keyId) ?: throw JwtException("unknown signing key")
      }
    }

  private fun findKey(keyId: String): PublicKey? {
    val now = currentDateProvider.date.time
    if (now - keysFetchedAt > KEYS_TTL_MS) {
      refreshKeys(now)
    }
    keys[keyId]?.let { return it }

    // Access rotates its keys, so an unknown id may simply be newer than the cache. The minimum
    // interval keeps forged key ids from turning this endpoint into a request generator.
    if (now - keysFetchedAt > KEYS_MIN_REFRESH_INTERVAL_MS) {
      refreshKeys(now)
    }
    return keys[keyId]
  }

  @Synchronized
  private fun refreshKeys(now: Long) {
    // stamped even on failure, so an outage at Cloudflare is not retried on every request
    keysFetchedAt = now
    val url = tolgeeProperties.authentication.cloudflareAccess.certsUrl
    val response =
      try {
        restTemplate.getForObject(url, Map::class.java)
      } catch (e: RestClientException) {
        logger.warn("Could not fetch Cloudflare Access signing keys from $url", e)
        return
      }
    val fetched =
      (response?.get("keys") as? Collection<*>)
        .orEmpty()
        .mapNotNull { it as? Map<*, *> }
        .mapNotNull { jwk ->
          val keyId = jwk["kid"] as? String ?: return@mapNotNull null
          val key = toPublicKey(jwk) ?: return@mapNotNull null
          keyId to key
        }.toMap()
    if (fetched.isEmpty()) {
      logger.warn("Cloudflare Access returned no usable signing keys from $url")
      return
    }
    keys = fetched
  }

  private fun toPublicKey(jwk: Map<*, *>): PublicKey? {
    if (jwk["kty"] != "RSA") {
      return null
    }
    val modulus = jwk["n"] as? String ?: return null
    val exponent = jwk["e"] as? String ?: return null
    return try {
      val decoder = Base64.getUrlDecoder()
      KeyFactory
        .getInstance("RSA")
        .generatePublic(
          RSAPublicKeySpec(BigInteger(1, decoder.decode(modulus)), BigInteger(1, decoder.decode(exponent))),
        )
    } catch (e: Exception) {
      null
    }
  }

  class InvalidCloudflareAccessTokenException(
    message: String,
    cause: Throwable? = null,
  ) : RuntimeException(message, cause)

  companion object {
    private const val CLOCK_SKEW_SECONDS = 60L
    private const val KEYS_TTL_MS = 60 * 60 * 1000L
    private const val KEYS_MIN_REFRESH_INTERVAL_MS = 60 * 1000L
  }
}
