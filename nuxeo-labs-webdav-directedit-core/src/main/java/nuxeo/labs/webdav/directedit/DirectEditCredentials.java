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
package nuxeo.labs.webdav.directedit;

/**
 * Constants shared by the operations and the authenticator that let Microsoft Office sign in with a Nuxeo
 * authentication token instead of a password.
 * <p>
 * Office speaks only the HTTP authentication schemes, so it cannot run an interactive OIDC flow. On an SSO-only
 * deployment the user has no local password to type into the Office credentials prompt. Accepting a Nuxeo token as the
 * Basic password closes that gap without weakening anything: the token is checked against the {@code authTokens}
 * directory exactly as {@code TOKEN_AUTH} does.
 *
 * @since 2025.1
 */
public final class DirectEditCredentials {

    /**
     * Application name recorded on the token. Together with {@link #DEVICE_ID} it makes {@code acquireToken}
     * idempotent, so a user always gets the same token back for this feature.
     */
    public static final String APPLICATION_NAME = "nuxeo-labs-webdav-directedit";

    public static final String DEVICE_ID = "webdav-directedit";

    public static final String DEVICE_DESCRIPTION = "WebDAV Direct Edit (Microsoft Office)";

    /**
     * Recorded on the token for documentation only.
     * <p>
     * Nuxeo token permissions are <b>not</b> a scope: the platform stores this string but never enforces it, and a
     * token authenticates its owner with their full rights. Treat an issued token as a full-account credential.
     */
    public static final String PERMISSION = "rw";

    /** Whether a Nuxeo token is accepted in place of the password on the WebDAV endpoint. */
    public static final String TOKEN_AUTH_ENABLED_PROP = "nuxeo.labs.webdavDirectEdit.tokenAuth.enabled";

    /** Whether the Web UI may display the token to the user. */
    public static final String SHOW_CREDENTIALS_HELP_PROP = "org.nuxeo.web.ui.webdavDirectEdit.showCredentialsHelp";

    private DirectEditCredentials() {
        // constants holder
    }
}
