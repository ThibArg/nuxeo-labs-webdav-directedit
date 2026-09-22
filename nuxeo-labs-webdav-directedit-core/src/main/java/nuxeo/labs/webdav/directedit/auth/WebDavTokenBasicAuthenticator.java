/*
 * (C) Copyright 2026 Hyland (http://hyland.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     Thibaud Arguillere
 */
package nuxeo.labs.webdav.directedit.auth;

import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.ecm.platform.api.login.UserIdentificationInfo;
import org.nuxeo.ecm.platform.ui.web.auth.plugins.BasicAuthenticator;
import org.nuxeo.ecm.tokenauth.service.TokenAuthenticationService;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.services.config.ConfigurationService;

import nuxeo.labs.webdav.directedit.DirectEditCredentials;
import nuxeo.labs.webdav.directedit.WebDavDirectEditService;

/**
 * Basic authenticator that also accepts a Nuxeo authentication token in place of the password.
 * <p>
 * Microsoft Office talks to WebDAV with its own HTTP stack and cannot run an interactive SSO flow, so on an SSO-only
 * deployment the user has no password to type into the Office credentials prompt. Letting the token stand in for the
 * password closes that gap while keeping plain password authentication working for quick tests and for deployments
 * with local accounts.
 * <p>
 * This plugin is wired only into the authentication chain of this plugin's own WebDAV endpoint, and it further checks
 * the request URL itself before ever treating a secret as a token.
 *
 * @since 2025.1
 */
public class WebDavTokenBasicAuthenticator extends BasicAuthenticator {

    private static final Logger log = LogManager.getLogger(WebDavTokenBasicAuthenticator.class);

    /**
     * Nuxeo tokens are {@code UUID.randomUUID().toString()}, see {@code TokenAuthenticationServiceImpl#acquireToken}.
     * Matching this shape before looking a secret up keeps real passwords away from the token service, which logs its
     * argument verbatim at DEBUG level.
     */
    protected static final Pattern TOKEN_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    @Override
    public UserIdentificationInfo handleRetrieveIdentity(HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        UserIdentificationInfo identity = super.handleRetrieveIdentity(httpRequest, httpResponse);
        if (identity == null) {
            return identity;
        }

        /*
         * Token acceptance is confined to this plugin's own WebDAV endpoint. Nothing else in Nuxeo should treat a
         * Basic password as a token, and a plugin has no business widening the authentication rules of URLs it does
         * not own.
         */
        if (!isOfficeEditRequest(httpRequest) || !isTokenAuthEnabled()) {
            return identity;
        }

        String secret = identity.getPassword();
        if (StringUtils.isBlank(secret) || !TOKEN_PATTERN.matcher(secret).matches()) {
            return identity;
        }

        String tokenUser = getUserNameForToken(secret);
        // Require the token to belong to the user that was announced, so a token can never be used to log in as
        // somebody else.
        if (tokenUser != null && tokenUser.equals(identity.getUserName())) {
            log.debug("Authenticated {} from a Nuxeo token on the WebDAV endpoint", tokenUser);
            // The single-argument constructor marks the credentials as already checked, so the filter will not try to
            // match the token against the user directory password.
            return new UserIdentificationInfo(tokenUser);
        }

        // Not a token, or not this user's token: fall back to a regular password check.
        return identity;
    }

    /**
     * Whether the request targets this plugin's WebDAV endpoint, which is the only place a token is accepted in place
     * of a password.
     */
    protected boolean isOfficeEditRequest(HttpServletRequest httpRequest) {
        String uri = httpRequest.getRequestURI();
        return uri != null && uri.contains(WebDavDirectEditService.WEBDAV_PATH_PREFIX);
    }

    protected boolean isTokenAuthEnabled() {
        ConfigurationService configurationService = Framework.getService(ConfigurationService.class);
        // Fail closed: without the configuration service the kill switch cannot be honoured.
        return configurationService != null
                && !configurationService.isBooleanFalse(DirectEditCredentials.TOKEN_AUTH_ENABLED_PROP);
    }

    protected String getUserNameForToken(String token) {
        TokenAuthenticationService tokenAuthenticationService = Framework.getService(TokenAuthenticationService.class);
        if (tokenAuthenticationService == null) {
            return null;
        }
        try {
            return tokenAuthenticationService.getUserName(token);
        } catch (RuntimeException e) {
            log.debug("Could not resolve a user from the supplied secret, treating it as a password", e);
            return null;
        }
    }
}
