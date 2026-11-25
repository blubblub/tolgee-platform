package io.tolgee.configuration.tolgee

import io.tolgee.configuration.annotations.DocProperty
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "tolgee.authentication.cloudflare-access")
@DocProperty(
  description =
    "Fork-only. When Tolgee runs behind Cloudflare Access, the identity Access has already verified " +
      "can log the user in, so nobody signs in twice. Tolgee verifies the signed " +
      "`Cf-Access-Jwt-Assertion` header and logs in the **existing** account with the same e-mail. " +
      "It never creates accounts, so invitations stay the only way in. " +
      "Enabled when both `team-domain` and `audience` are set.",
  displayName = "Cloudflare Access",
)
class CloudflareAccessProperties {
  @DocProperty(
    description =
      "The Access team domain, which is also the token issuer, " +
        "e.g. `https://your-team.cloudflareaccess.com`. Signing keys are fetched from " +
        "`<team-domain>/cdn-cgi/access/certs`.",
  )
  var teamDomain: String? = null

  @DocProperty(
    description =
      "The Application Audience (AUD) tag of the Access application protecting this instance. " +
        "Tokens issued for any other application are refused.",
  )
  var audience: String? = null

  val enabled: Boolean
    get() = !teamDomain.isNullOrBlank() && !audience.isNullOrBlank()

  val issuer: String
    get() = teamDomain.orEmpty().trim().trimEnd('/')

  val certsUrl: String
    get() = "$issuer/cdn-cgi/access/certs"
}
