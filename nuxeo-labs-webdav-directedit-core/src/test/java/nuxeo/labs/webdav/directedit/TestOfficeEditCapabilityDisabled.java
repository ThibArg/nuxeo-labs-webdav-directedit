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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import jakarta.inject.Inject;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.BasicAuthCache;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.ServletContainerFeature;
import org.nuxeo.runtime.test.runner.TransactionalFeature;

import nuxeo.labs.webdav.directedit.server.OfficeEditSessionStore;

/**
 * Covers the kill switch: with {@code capabilityUrl.enabled=false} the token in the URL must stop being a credential.
 * <p>
 * It still addresses the document, so the endpoint keeps working — Microsoft Office simply has to answer a Basic
 * challenge, which is the behaviour a deployment that does not want a bearer credential in a URL is asking for.
 *
 * @since 2025.1
 */
@RunWith(FeaturesRunner.class)
@Features(OfficeEditServerFeature.class)
@Deploy("nuxeo.labs.webdavdirectedit.nuxeo-labs-webdav-directedit-core:test-capability-url-disabled.xml")
public class TestOfficeEditCapabilityDisabled {

    protected static final String USERNAME = "Administrator";

    protected static final String WORD_MIME_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Inject
    protected CoreSession session;

    @Inject
    protected ServletContainerFeature servletContainerFeature;

    @Inject
    protected TransactionalFeature transactionalFeature;

    protected CloseableHttpClient client;

    protected String fileUri;

    @Before
    public void setUp() {
        client = HttpClients.createDefault();
        WebDavDirectEditTestUtils.createWorkspaceRoot(session);
        DocumentModel workspace = WebDavDirectEditTestUtils.createWorkspace(session, "my-workspace");
        DocumentModel document = WebDavDirectEditTestUtils.createFile(session, workspace, "report", "Report.docx",
                WORD_MIME_TYPE, "original content");
        session.save();
        String token = new OfficeEditSessionStore().create(document.getId(), USERNAME);
        transactionalFeature.nextTransaction();
        fileUri = servletContainerFeature.getHttpUrl() + "/site/officeedit/" + token + "/Report.docx";
    }

    @After
    public void tearDown() throws IOException {
        client.close();
    }

    @Test
    public void shouldChallengeInsteadOfTrustingTheUrl() throws Exception {
        try (CloseableHttpResponse response = client.execute(new HttpGet(fileUri), HttpClientContext.create())) {
            assertEquals(401, response.getStatusLine().getStatusCode());
            assertTrue(response.getFirstHeader("WWW-Authenticate").getValue().startsWith("Basic realm="));
            assertFalse("the document content must not leak",
                    EntityUtils.toString(response.getEntity(), UTF_8).contains("original content"));
        }
    }

    @Test
    public void shouldStillServeTheDocumentOnceAuthenticated() throws Exception {
        try (CloseableHttpResponse response = client.execute(new HttpGet(fileUri), basicAuthContext())) {
            assertEquals(200, response.getStatusLine().getStatusCode());
            assertEquals("original content", EntityUtils.toString(response.getEntity(), UTF_8));
        }
    }

    protected HttpClientContext basicAuthContext() {
        var credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(USERNAME, USERNAME));
        var authCache = new BasicAuthCache();
        authCache.put(new HttpHost("localhost", servletContainerFeature.getPort()), new BasicScheme());
        var httpContext = HttpClientContext.create();
        httpContext.setCredentialsProvider(credentialsProvider);
        httpContext.setAuthCache(authCache);
        return httpContext;
    }
}
