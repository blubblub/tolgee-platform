package io.tolgee.security.thirdParty

import io.tolgee.configuration.tolgee.TolgeeProperties
import io.tolgee.constants.Message
import io.tolgee.dtos.request.auth.AuthProviderChangeData
import io.tolgee.exceptions.AuthenticationException
import io.tolgee.exceptions.BadRequestException
import io.tolgee.model.Organization
import io.tolgee.model.UserAccount
import io.tolgee.model.UserAccount.AccountType
import io.tolgee.model.enums.ThirdPartyAuthType
import io.tolgee.security.thirdParty.data.ThirdPartyUserDetails
import io.tolgee.service.TenantService
import io.tolgee.service.organization.OrganizationRoleService
import io.tolgee.service.organization.OrganizationService
import io.tolgee.service.security.AuthProviderChangeService
import io.tolgee.service.security.SignUpService
import io.tolgee.service.security.UserAccountService
import org.springframework.stereotype.Component

@Component
class ThirdPartyUserHandler(
  private val signUpService: SignUpService,
  private val organizationService: OrganizationService,
  private val organizationRoleService: OrganizationRoleService,
  private val userAccountService: UserAccountService,
  private val authProviderChangeService: AuthProviderChangeService,
  private val tenantService: TenantService,
  private val tolgeeProperties: TolgeeProperties,
) {
  fun findOrCreateUser(data: ThirdPartyUserDetails): UserAccount {
    val userAccount = getUserAccount(data)

    authProviderChangeService.tryInitiate(
      userAccount,
      data.username,
      AuthProviderChangeData(
        data.thirdPartyAuthType,
        data.authId,
        ssoDomain = data.tenant?.domain,
        ssoRefreshToken = data.refreshToken,
        ssoExpiration = userAccountService.getCurrentSsoExpiration(data.thirdPartyAuthType),
      ),
    )

    if (userAccount != null) {
      if (data.thirdPartyAuthType in arrayOf(ThirdPartyAuthType.SSO, ThirdPartyAuthType.SSO_GLOBAL)) {
        userAccountService.updateSsoSession(userAccount, data.refreshToken)
      }
      return userAccount
    }

    return linkExistingAccount(data) ?: createUser(data)
  }

  /**
   * Fork addition: a first Google login for an e-mail that already has a native account attaches Google to
   * that account rather than failing with USERNAME_ALREADY_EXISTS. The password is kept, so both methods
   * work afterwards.
   *
   * Google only — its delegate refuses unverified e-mails, which is what makes matching by e-mail safe.
   * Returning null falls through to [createUser], which reports the conflict as before.
   */
  private fun linkExistingAccount(data: ThirdPartyUserDetails): UserAccount? {
    if (data.thirdPartyAuthType != ThirdPartyAuthType.GOOGLE ||
      !tolgeeProperties.authentication.google.linkExistingAccounts
    ) {
      return null
    }

    val existing = userAccountService.findActive(data.username) ?: return null

    val linkable =
      existing.thirdPartyAuthType == null &&
        existing.accountType != AccountType.MANAGED &&
        // Google alone must not become a way around the second factor
        existing.totpKey == null &&
        // whoever registered an unconfirmed address may not be its owner
        existing.emailVerification == null
    if (!linkable) {
      return null
    }

    checkNotManagedByOrganization(existing.domain)

    authProviderChangeService.apply(
      existing,
      AuthProviderChangeData(data.thirdPartyAuthType, data.authId),
    )
    return existing
  }

  private fun getManagingOrganization(data: ThirdPartyUserDetails): Organization? {
    return data.tenant?.organizationId?.let {
      organizationService.find(it) ?: throw AuthenticationException(Message.ORGANIZATION_NOT_FOUND)
    }
  }

  private fun checkNotManagedByOrganization(domain: String?) {
    if (tenantService.getEnabledConfigByDomainOrNull(domain)?.organizationId != null) {
      // There is sso configured for the domain - don't allow sign up without sso
      throw AuthenticationException(Message.USE_SSO_FOR_AUTHENTICATION_INSTEAD, listOf(domain))
    }
  }

  private fun checkUserManagingOrganization(
    data: ThirdPartyUserDetails,
    user: UserAccount,
  ) {
    val org = getManagingOrganization(data)
    if (org == null) {
      checkNotManagedByOrganization(user.domain)
      return
    }

    if (user.organizationRoles.any { it.organization?.id != org.id }) {
      // User accepted invitation for different organization
      throw BadRequestException(Message.INVITATION_ORGANIZATION_MISMATCH)
    }

    if (!organizationRoleService.hasAnyOrganizationRole(user.id, org.id)) {
      organizationRoleService.grantMemberRoleToUser(user, org)
    }
    organizationRoleService.setManaged(user, org, true)
  }

  private fun getUserAccount(data: ThirdPartyUserDetails): UserAccount? {
    if (data.thirdPartyAuthType == ThirdPartyAuthType.SSO) {
      if (data.tenant == null) {
        // This should never happen
        throw AuthenticationException(Message.THIRD_PARTY_AUTH_UNKNOWN_ERROR)
      }
      return userAccountService.findEnabledBySsoDomain(data.tenant.domain, data.authId)
    }
    return userAccountService.findByThirdParty(data.thirdPartyAuthType, data.authId)
  }

  private fun createUserEntity(data: ThirdPartyUserDetails): UserAccount {
    val user = UserAccount()
    user.username = data.username.lowercase()
    user.name = data.name
    user.thirdPartyAuthId = data.authId
    user.thirdPartyAuthType = data.thirdPartyAuthType
    user.ssoRefreshToken = data.refreshToken
    user.accountType = guessAccountType(data)
    user.ssoSessionExpiry = userAccountService.getCurrentSsoExpiration(data.thirdPartyAuthType)
    return user
  }

  private fun guessAccountType(data: ThirdPartyUserDetails): AccountType {
    return when (data.thirdPartyAuthType) {
      ThirdPartyAuthType.SSO, ThirdPartyAuthType.SSO_GLOBAL -> AccountType.MANAGED
      ThirdPartyAuthType.GITHUB, ThirdPartyAuthType.OAUTH2, ThirdPartyAuthType.GOOGLE -> AccountType.THIRD_PARTY
    }
  }

  private fun createUser(data: ThirdPartyUserDetails): UserAccount {
    userAccountService.findActiveOrDisabled(data.username)?.let {
      if (it.disabledAt != null) {
        throw AuthenticationException(Message.USER_ACCOUNT_DISABLED)
      }
      throw AuthenticationException(Message.USERNAME_ALREADY_EXISTS)
    }

    val user = createUserEntity(data)

    signUpService.signUp(user, data.invitationCode, null)
    checkUserManagingOrganization(data, user)
    return user
  }
}
