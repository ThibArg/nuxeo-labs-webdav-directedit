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
package nuxeo.labs.webdav.directedit.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.Test;

/**
 * Guards the one security property that is easy to break by accident: the edit session token authenticates requests,
 * so it must never reach a log.
 * <p>
 * It lives in the same package as the classes it tests because the helpers involved are deliberately not public.
 *
 * @since 2025.1
 */
public class TestTokenMasking {

    protected static final String CONTEXT_PATH = "/nuxeo";

    protected static final String SERVLET_PATH = "/site/officeedit";

    @Test
    public void shouldNeverRevealTheWholeToken() {
        String token = UUID.randomUUID().toString();
        String masked = OfficeEditSessionStore.mask(token);

        assertFalse("the token must not appear in its masked form", masked.contains(token));
        // At most a short prefix, enough to correlate two lines, never enough to replay the address.
        assertTrue(masked.length() < token.length());
        assertEquals("<none>", OfficeEditSessionStore.mask(null));
        assertEquals("<none>", OfficeEditSessionStore.mask("  "));
        assertEquals("<token>", OfficeEditSessionStore.mask("short"));
    }

    @Test
    public void shouldKeepTheTokenOutOfALoggedPath() {
        String token = UUID.randomUUID().toString();
        String safe = OfficeEditServlet.safePath(request("/" + token + "/Report.docx"));

        assertFalse("safePath is what log statements use, it must not carry the token", safe.contains(token));
        assertTrue("the file name stays readable, it is not a secret", safe.contains("Report.docx"));
    }

    @Test
    public void shouldRenderTheRootAndCollectionsWithoutFailing() {
        // A malformed or partial path must still produce something loggable rather than throw inside a log statement.
        assertEquals("/", OfficeEditServlet.safePath(request("/")));
        assertEquals("/", OfficeEditServlet.safePath(request("")));
        assertTrue(OfficeEditServlet.safePath(request("/" + UUID.randomUUID() + "/")).endsWith("/"));
    }

    protected HttpServletRequest request(String pathBelowMountPoint) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getContextPath()).thenReturn(CONTEXT_PATH);
        when(request.getServletPath()).thenReturn(SERVLET_PATH);
        when(request.getRequestURI()).thenReturn(CONTEXT_PATH + SERVLET_PATH + pathBelowMountPoint);
        return request;
    }
}
