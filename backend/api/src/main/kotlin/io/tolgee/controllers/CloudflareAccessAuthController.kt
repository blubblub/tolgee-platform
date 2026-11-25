package io.tolgee.controllers

import io.swagger.v3.oas.annotations.Operation
import io.tolgee.configuration.tolgee.TolgeeProperties
import io.tolgee.constants.Message
import io.tolgee.exceptions.AuthenticationException
import io.tolgee.model.UserAccount
import io.tolgee.openApiDocs.OpenApiHideFromPublicDocs
import io.tolgee.security.authentication.JwtService
import io.tolgee.security.payload.JwtAuthenticationResponse
import io.tolgee.security.ratelimit.RateLimited
import io.tolgee.security.thirdParty.CloudflareAccessTokenVerifier
import io.tolgee.security.thirdParty.CloudflareAccessTokenVerifier.InvalidCloudflareAccessTokenException
import io.tolgee.service.security.UserAccountService
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Fork addition: single login behind Cloudflare Access.
 *
 * Access has already authenticated the browser by the time a request reaches Tolgee, so this turns
 * that identity into an ordinary Tolgee session instead of asking the user to log in a second time.
 *
 * It must stay under `/api/public`: that path sits behind Access, so the header arrives. `/v2` and
 * `/mcp` are bypassed for API-key clients and carry no header at all.
 */
@RestController
@RequestMapping("/api/public")
@AuthenticationTag
class CloudflareAccessAuthController(
  private val tolgeeProperties: TolgeeProperties,
  private val tokenVerifier: CloudflareAccessTokenVerifier,
  private val userAccountService: UserAccountService,
  private val jwtService: JwtService,
) {
  private val logger = LoggerFactory.getLogger(javaClass)

  @PostMapping("/authorize_cloudflare_access")
  @Operation(
    summary = "Authenticate user (Cloudflare Access)",
    description =
      "Logs in the existing account whose e-mail matches the identity verified by Cloudflare Access. " +
        "Never creates an account.",
  )
  @OpenApiHideFromPublicDocs
  @RateLimited(5, isAuthentication = true)
  fun authenticateWithCloudflareAccess(request: HttpServletRequest): JwtAuthenticationResponse {
    if (!tolgeeProperties.authentication.cloudflareAccess.enabled) {
      throw AuthenticationException(Message.AUTHENTICATION_METHOD_DISABLED)
    }

    val token = request.getHeader(ACCESS_JWT_HEADER)
    if (token.isNullOrBlank()) {
      throw refuse("no $ACCESS_JWT_HEADER header on the request")
    }

    val email =
      try {
        tokenVerifier.verifyAndGetEmail(token)
      } catch (e: InvalidCloudflareAccessTokenException) {
        throw refuse("invalid token: ${e.message}")
      }

    val user = userAccountService.findActive(email) ?: throw refuse("no active account for the verified e-mail")
    refusalReason(user)?.let { throw refuse("account ${user.id} $it") }

    // A regular token, as the Google login issues: sensitive operations still ask for the password
    return JwtAuthenticationResponse(jwtService.emitToken(user.id))
  }

  /**
   * Accounts that must keep using the login form. The same exclusions as the Google account matching in
   * `ThirdPartyUserHandler.linkExistingAccount`, for the same reasons.
   */
  private fun refusalReason(user: UserAccount): String? =
    when {
      // Access must not become a way around the second factor
      user.totpKey != null -> "has two-factor authentication"
      // whoever registered an unconfirmed address may not be its owner
      user.emailVerification != null -> "has an unconfirmed e-mail"
      // SSO-managed accounts have their own session lifecycle
      user.accountType == UserAccount.AccountType.MANAGED -> "is managed by SSO"
      else -> null
    }

  /**
   * One answer for every refusal, so the endpoint does not reveal which e-mails have accounts. The
   * webapp falls back to the login form without showing an error; the reason is only logged.
   */
  private fun refuse(reason: String): AuthenticationException {
    logger.info("Cloudflare Access login refused: $reason")
    return AuthenticationException(Message.THIRD_PARTY_UNAUTHORIZED)
  }

  companion object {
    const val ACCESS_JWT_HEADER = "Cf-Access-Jwt-Assertion"
  }
}
