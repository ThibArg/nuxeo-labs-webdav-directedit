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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.ecm.platform.api.login.UserIdentificationInfo;
import org.nuxeo.ecm.platform.ui.web.auth.interfaces.NuxeoAuthenticationPlugin;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.services.config.ConfigurationService;

import nuxeo.labs.webdav.directedit.server.DavTarget;
import nuxeo.labs.webdav.directedit.server.OfficeEditSessionStore;

/**
 * Authenticates a request from the edit session token carried in its URL, so Microsoft Office never has to show a
 * credentials prompt.
 * <p>
 * This is what makes the feature usable on a single sign-on deployment. Office drives WebDAV with its own HTTP stack:
 * it has no browser, cannot follow an identity provider redirect, and supports only Basic and Digest. On an SSO-only
 * deployment the user therefore has no password to type. A token in the URL removes the question.
 * <p>
 * The same shape is used by the platform's own {@code nuxeo-wopi} module, which faces the identical problem and pins
 * {@code JWT_AUTH} to its endpoint with a specific authentication chain. A JWT is not usable here: it is read from a
 * header or a query parameter, never from a path segment, and an HMAC512 token is roughly 183 characters, which does
 * not fit the 216-character limit Excel imposes on the URI.
 * <p>
 * This plugin never prompts. When it cannot authenticate it returns {@code null} and the chain falls through to
 * {@link WebDavTokenBasicAuthenticator}, which does prompt. An expired session therefore degrades to a normal Basic
 * challenge, and because the session record outlives its authentication window, answering that challenge opens the
 * document rather than yielding a 404. That is what keeps Basic authentication usable alongside SSO.
 *
 * @since 2025.1
 */
public class OfficeEditCapabilityAuthenticator implements NuxeoAuthenticationPlugin {

    private static final Logger log = LogManager.getLogger(OfficeEditCapabilityAuthenticator.class);

    public static final String ENABLED_PROP = "nuxeo.labs.webdavDirectEdit.capabilityUrl.enabled";

    protected final OfficeEditSessionStore sessionStore = new OfficeEditSessionStore();

    @Override
    public UserIdentificationInfo handleRetrieveIdentity(HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        if (!isEnabled()) {
            return null;
        }
        String token = DavTarget.firstSegmentOf(httpRequest);
        return sessionStore.resolveForAuthentication(token).map(session -> {
            log.debug("Authenticated {} from an edit session token", session::userName);
            // The single-argument constructor marks the credentials as already checked, so the filter will not try to
            // match anything against the user directory.
            return new UserIdentificationInfo(session.userName());
        }).orElse(null);
    }

    protected boolean isEnabled() {
        ConfigurationService configurationService = Framework.getService(ConfigurationService.class);
        // Fail closed: without the configuration service the kill switch cannot be honoured.
        return configurationService != null && !configurationService.isBooleanFalse(ENABLED_PROP);
    }

    /**
     * Never prompts. Prompting is the job of the Basic authenticator that follows this one in the chain.
     */
    @Override
    public Boolean needLoginPrompt(HttpServletRequest httpRequest) {
        return Boolean.FALSE;
    }

    @Override
    public Boolean handleLoginPrompt(HttpServletRequest httpRequest, HttpServletResponse httpResponse, String baseURL) {
        return Boolean.FALSE;
    }

    /**
     * Returns an empty list on purpose. A non-empty prefix here would make URLs bypass authentication altogether, for
     * every chain in the server, not just this one.
     */
    @Override
    public List<String> getUnAuthenticatedURLPrefix() {
        return new ArrayList<>();
    }

    @Override
    public void initPlugin(Map<String, String> parameters) {
        // no parameters
    }
}
