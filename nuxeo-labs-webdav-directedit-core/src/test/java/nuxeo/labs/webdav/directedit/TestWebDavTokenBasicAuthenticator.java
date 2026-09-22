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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.platform.api.login.UserIdentificationInfo;
import org.nuxeo.ecm.tokenauth.service.TokenAuthenticationService;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;

import nuxeo.labs.webdav.directedit.auth.WebDavTokenBasicAuthenticator;

/**
 * Covers the authenticator that lets Microsoft Office sign in with a Nuxeo token instead of a password.
 *
 * @since 2025.1
 */
@RunWith(FeaturesRunner.class)
@Features(WebDavDirectEditFeature.class)
public class TestWebDavTokenBasicAuthenticator {

    @Inject
    protected CoreSession session;

    @Inject
    protected TokenAuthenticationService tokenAuthenticationService;

    protected WebDavTokenBasicAuthenticator authenticator;

    protected String userName;

    protected String token;

    @Before
    public void setUp() {
        authenticator = new WebDavTokenBasicAuthenticator();
        authenticator.initPlugin(Map.of("AutoPrompt", "true", "RealmName", "Nuxeo WebDAV"));
        userName = session.getPrincipal().getName();
        token = tokenAuthenticationService.acquireToken(userName, DirectEditCredentials.APPLICATION_NAME,
                DirectEditCredentials.DEVICE_ID, DirectEditCredentials.DEVICE_DESCRIPTION,
                DirectEditCredentials.PERMISSION);
    }

    protected UserIdentificationInfo retrieveIdentity(String user, String secret) {
        return retrieveIdentity(user, secret, "/nuxeo/site/officeedit/0000-1111/Report.docx");
    }

    protected UserIdentificationInfo retrieveIdentity(String user, String secret, String requestUri) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        String credentials = Base64.getEncoder()
                                   .encodeToString((user + ":" + secret).getBytes(StandardCharsets.UTF_8));
        when(request.getHeader("authorization")).thenReturn("Basic " + credentials);
        when(request.getRequestURI()).thenReturn(requestUri);
        return authenticator.handleRetrieveIdentity(request, response);
    }

    @Test
    public void shouldAcceptATokenAsThePassword() {
        UserIdentificationInfo identity = retrieveIdentity(userName, token);

        assertEquals(userName, identity.getUserName());
        // Already validated against the authTokens directory, so the filter must not check a password.
        assertTrue(identity.credentialsChecked());
        assertNull(identity.getPassword());
    }

    @Test
    public void shouldFallBackToAPasswordCheckForANonToken() {
        UserIdentificationInfo identity = retrieveIdentity(userName, "some-real-password");

        assertEquals(userName, identity.getUserName());
        assertFalse(identity.credentialsChecked());
        assertEquals("some-real-password", identity.getPassword());
    }

    @Test
    public void shouldRefuseATokenBelongingToAnotherUser() {
        // A token must never let its bearer log in as somebody else.
        UserIdentificationInfo identity = retrieveIdentity("someone-else", token);

        assertEquals("someone-else", identity.getUserName());
        assertFalse(identity.credentialsChecked());
        assertEquals(token, identity.getPassword());
    }

    @Test
    public void shouldIgnoreARevokedToken() {
        tokenAuthenticationService.revokeToken(token);

        UserIdentificationInfo identity = retrieveIdentity(userName, token);

        assertFalse(identity.credentialsChecked());
    }

    @Test
    public void shouldReturnNullWithoutAnAuthorizationHeader() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getHeader("authorization")).thenReturn(null);

        assertNull(authenticator.handleRetrieveIdentity(request, response));
    }

    @Test
    public void shouldNotAcceptATokenOutsideTheWebDavEndpoint() {
        // The addon also wires WEBDAV_BASIC_AUTH into a chain selected on the User-Agent alone, with no URL pattern.
        // Token acceptance must stay confined to the WebDAV endpoint.
        UserIdentificationInfo identity = retrieveIdentity(userName, token, "/nuxeo/api/v1/path/");

        assertFalse(identity.credentialsChecked());
        assertEquals(token, identity.getPassword());
    }

    @Test
    public void shouldNeverSendANonTokenSecretToTheTokenService() {
        // TokenAuthenticationService#getUserName logs its argument verbatim at DEBUG level, so a real password must
        // never reach it. Only UUID-shaped secrets are looked up.
        var probe = new WebDavTokenBasicAuthenticator() {
            final List<String> lookedUp = new ArrayList<>();

            @Override
            protected String getUserNameForToken(String secret) {
                lookedUp.add(secret);
                return super.getUserNameForToken(secret);
            }
        };
        probe.initPlugin(Map.of("AutoPrompt", "true", "RealmName", "Nuxeo WebDAV"));
        authenticator = probe;

        retrieveIdentity(userName, "hunter2");
        retrieveIdentity(userName, "not-a-uuid-at-all");
        assertTrue("A password must never reach the token service", probe.lookedUp.isEmpty());

        retrieveIdentity(userName, token);
        assertEquals(List.of(token), probe.lookedUp);
    }

    @Test
    @Deploy("nuxeo.labs.webdavdirectedit.nuxeo-labs-webdav-directedit-core:test-token-auth-disabled.xml")
    public void shouldNotAcceptATokenWhenDisabled() {
        UserIdentificationInfo identity = retrieveIdentity(userName, token);

        assertFalse(identity.credentialsChecked());
        assertEquals(token, identity.getPassword());
    }
}
