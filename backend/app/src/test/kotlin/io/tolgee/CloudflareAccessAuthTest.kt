package io.tolgee

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.SignatureAlgorithm
import io.jsonwebtoken.security.Keys
import io.tolgee.constants.Message
import io.tolgee.fixtures.andAssertThatJson
import io.tolgee.fixtures.andIsOk
import io.tolgee.fixtures.andIsUnauthorized
import io.tolgee.fixtures.mapResponseTo
import io.tolgee.model.UserAccount
import io.tolgee.testing.AbstractControllerTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.web.client.RestTemplate
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.Date

class CloudflareAccessAuthTest : AbstractControllerTest() {
  @MockitoBean
  @Autowired
  private val restTemplate: RestTemplate? = null

  private lateinit var user: UserAccount

  @BeforeEach
  fun setup() {
    tolgeeProperties.authentication.cloudflareAccess.teamDomain = ISSUER
    tolgeeProperties.authentication.cloudflareAccess.audience = AUDIENCE
    whenever(restTemplate!!.getForObject(eq("$ISSUER/cdn-cgi/access/certs"), any<Class<Map<*, *>>>()))
      .thenReturn(mapOf("keys" to listOf(jwk(KEY_ID, teamKeys))))
    user = dbPopulator.createUserIfNotExists(EMAIL, "native-password")
  }

  @AfterEach
  fun cleanup() {
    tolgeeProperties.authentication.cloudflareAccess.teamDomain = null
    tolgeeProperties.authentication.cloudflareAccess.audience = null
    userAccountService.findActive(EMAIL)?.let { userAccountService.delete(it) }
  }

  @Test
  fun `logs in the existing account behind the verified e-mail`() {
    val result = login(token()).andIsOk.andReturn().mapResponseTo<Map<String, Any>>()

    val accessToken = result["accessToken"] as String
    mvc
      .perform(MockMvcRequestBuilders.get("/v2/user").header("Authorization", "Bearer $accessToken"))
      .andIsOk
      .andAssertThatJson { node("username").isEqualTo(EMAIL) }
  }

  @Test
  fun `matches the e-mail regardless of case`() {
    login(token(email = "Access.User@Domain.com")).andIsOk
  }

  @Test
  fun `does not create an account for an unknown e-mail`() {
    login(token(email = "stranger@domain.com")).andIsRefused()

    assertThat(userAccountService.findActive("stranger@domain.com")).isNull()
  }

  @Test
  fun `refuses a request without the header`() {
    mvc.perform(MockMvcRequestBuilders.post(URL)).andIsRefused()
  }

  @Test
  fun `refuses a token for another Access application`() {
    login(token(audience = listOf("another-application"))).andIsRefused()
  }

  @Test
  fun `refuses a token from another team`() {
    login(token(issuer = "https://someone-else.cloudflareaccess.com")).andIsRefused()
  }

  @Test
  fun `refuses an expired token`() {
    login(token(expiresInMs = -10 * 60 * 1000)).andIsRefused()
  }

  @Test
  fun `refuses a token signed by a key the team does not publish`() {
    login(token(signingKey = otherKeys.private)).andIsRefused()
    login(token(signingKey = otherKeys.private, keyId = "unknown-key")).andIsRefused()
  }

  @Test
  fun `refuses a token signed with a shared secret instead of the team key`() {
    val hmacKey = Keys.hmacShaKeyFor(teamKeys.public.encoded.copyOf(64))
    val forged =
      Jwts
        .builder()
        .setHeaderParam("kid", KEY_ID)
        .setIssuer(ISSUER)
        .claim("aud", listOf(AUDIENCE))
        .claim("email", EMAIL)
        .setExpiration(Date(System.currentTimeMillis() + 60_000))
        .signWith(hmacKey, SignatureAlgorithm.HS512)
        .compact()

    login(forged).andIsRefused()
  }

  @Test
  fun `refuses garbage`() {
    login("not-a-token").andIsRefused()
  }

  @Test
  fun `refuses a service token, which carries no e-mail`() {
    login(token(email = null, extraClaims = mapOf("common_name" to "tolgee-machines.access"))).andIsRefused()
  }

  @Test
  fun `leaves accounts with two-factor authentication to the login form`() {
    user.totpKey = byteArrayOf(1, 2, 3)
    userAccountService.save(user)

    login(token()).andIsRefused()
  }

  @Test
  fun `is refused when Cloudflare Access is not configured`() {
    tolgeeProperties.authentication.cloudflareAccess.audience = null

    login(token())
      .andIsUnauthorized
      .andAssertThatJson { node("code").isEqualTo(Message.AUTHENTICATION_METHOD_DISABLED.code) }
  }

  @Test
  fun `public configuration tells the webapp whether to try`() {
    mvc
      .perform(MockMvcRequestBuilders.get("/api/public/configuration"))
      .andIsOk
      .andAssertThatJson { node("authMethods.cloudflareAccess.enabled").isEqualTo(true) }

    tolgeeProperties.authentication.cloudflareAccess.teamDomain = null

    mvc
      .perform(MockMvcRequestBuilders.get("/api/public/configuration"))
      .andIsOk
      .andAssertThatJson { node("authMethods.cloudflareAccess.enabled").isEqualTo(false) }
  }

  private fun login(token: String): ResultActions =
    mvc.perform(MockMvcRequestBuilders.post(URL).header("Cf-Access-Jwt-Assertion", token))

  private fun ResultActions.andIsRefused() {
    andIsUnauthorized.andAssertThatJson { node("code").isEqualTo(Message.THIRD_PARTY_UNAUTHORIZED.code) }
  }

  private fun token(
    email: String? = EMAIL,
    audience: List<String> = listOf(AUDIENCE),
    issuer: String = ISSUER,
    expiresInMs: Long = 60_000,
    signingKey: PrivateKey = teamKeys.private,
    keyId: String = KEY_ID,
    extraClaims: Map<String, Any> = emptyMap(),
  ): String {
    val builder =
      Jwts
        .builder()
        .setHeaderParam("kid", keyId)
        .setIssuer(issuer)
        .claim("aud", audience)
        .setExpiration(Date(System.currentTimeMillis() + expiresInMs))
    email?.let { builder.claim("email", it) }
    extraClaims.forEach { (name, value) -> builder.claim(name, value) }
    return builder.signWith(signingKey, SignatureAlgorithm.RS256).compact()
  }

  private fun jwk(
    keyId: String,
    keys: KeyPair,
  ): Map<String, String> {
    val public = keys.public as RSAPublicKey
    val encoder = Base64.getUrlEncoder().withoutPadding()
    return mapOf(
      "kid" to keyId,
      "kty" to "RSA",
      "alg" to "RS256",
      "use" to "sig",
      "n" to encoder.encodeToString(public.modulus.toByteArray()),
      "e" to encoder.encodeToString(public.publicExponent.toByteArray()),
    )
  }

  companion object {
    private const val URL = "/api/public/authorize_cloudflare_access"
    private const val ISSUER = "https://test-team.cloudflareaccess.com"
    private const val AUDIENCE = "test-audience-tag"
    private const val KEY_ID = "team-key-1"
    private const val EMAIL = "access.user@domain.com"

    // generated once: the verifier caches the team's keys for the lifetime of the Spring context
    private val teamKeys: KeyPair = generateKeys()
    private val otherKeys: KeyPair = generateKeys()

    private fun generateKeys(): KeyPair {
      val generator = KeyPairGenerator.getInstance("RSA")
      generator.initialize(2048)
      return generator.generateKeyPair()
    }
  }
}
