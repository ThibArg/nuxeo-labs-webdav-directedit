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

import static nuxeo.labs.webdav.directedit.WebDavDirectEditTestUtils.createFile;
import static nuxeo.labs.webdav.directedit.WebDavDirectEditTestUtils.createWorkspace;
import static nuxeo.labs.webdav.directedit.WebDavDirectEditTestUtils.createWorkspaceRoot;
import static nuxeo.labs.webdav.directedit.WebDavDirectEditTestUtils.parseJson;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import jakarta.inject.Inject;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.automation.AutomationService;
import org.nuxeo.ecm.automation.OperationContext;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Covers the JSON contract of the Automation operations, which the Web UI depends on.
 *
 * @since 2025.1
 */
@RunWith(FeaturesRunner.class)
@Features(WebDavDirectEditFeature.class)
@Deploy("nuxeo.labs.webdavdirectedit.nuxeo-labs-webdav-directedit-core:test-base-url.xml")
public class TestDirectEditOperations {

    protected static final String BASE_URL = "https://nuxeo.example.com/nuxeo";

    protected static final String WORD_MIME_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Inject
    protected CoreSession session;

    @Inject
    protected AutomationService automationService;

    protected DocumentModel workspace;

    @Before
    public void setUp() {
        createWorkspaceRoot(session);
        workspace = createWorkspace(session, "my-workspace");
        session.save();
    }

    protected JsonNode runGetUrl(DocumentModel input) throws Exception {
        try (OperationContext ctx = new OperationContext(session)) {
            ctx.setInput(input);
            Blob result = (Blob) automationService.run(ctx, GetDirectEditUrlOp.ID);
            return parseJson(result);
        }
    }

    @Test
    public void shouldReturnTheFullPayloadWhenAvailable() throws Exception {
        DocumentModel doc = createFile(session, workspace, "report", "Report.docx", WORD_MIME_TYPE);
        session.save();

        JsonNode json = runGetUrl(doc);

        assertTrue(json.get("available").asBoolean());
        assertEquals("word", json.get("app").asText());
        assertEquals("Report.docx", json.get("fileName").asText());
        String davPath = json.get("davPath").asText();
        String token = davPath.substring(0, davPath.indexOf('/'));
        assertEquals(token + "/Report.docx", davPath);
        assertEquals(BASE_URL + "/site/officeedit/" + token + "/Report.docx", json.get("davUrl").asText());
        assertEquals("ms-word:ofe|u|" + BASE_URL + "/site/officeedit/" + token + "/Report.docx",
                json.get("protocolUrl").asText());
        assertTrue(json.get("secure").asBoolean());
        assertTrue(json.get("reason").isNull());
    }

    @Test
    public void shouldReturnTheReasonWhenUnavailable() throws Exception {
        DocumentModel doc = createFile(session, workspace, "readme", "readme.txt", "text/plain");
        session.save();

        JsonNode json = runGetUrl(doc);

        assertFalse(json.get("available").asBoolean());
        assertEquals("notOfficeFile", json.get("reason").asText());
        assertTrue(json.get("app").isNull());
        assertTrue(json.get("protocolUrl").isNull());
        // Every field is always present, so the Web UI never has to guard against a missing key.
        assertTrue(json.has("davUrl"));
        assertTrue(json.has("davPath"));
        assertTrue(json.has("secure"));
    }

    @Test
    public void shouldHonourTheBaseUrlParameter() throws Exception {
        // The configured base URL wins over the one the browser reports, so an administrator can force HTTPS.
        DocumentModel doc = createFile(session, workspace, "report", "Report.docx", WORD_MIME_TYPE);
        session.save();

        try (OperationContext ctx = new OperationContext(session)) {
            ctx.setInput(doc);
            Blob result = (Blob) automationService.run(ctx, GetDirectEditUrlOp.ID,
                    java.util.Map.of("baseUrl", "http://ignored.example.com/nuxeo"));
            JsonNode json = parseJson(result);
            assertTrue(json.get("davUrl").asText().startsWith(BASE_URL + "/site/officeedit/"));
            assertTrue(json.get("davUrl").asText().endsWith("/Report.docx"));
        }
    }

    @Test
    public void shouldAcquireAndRevokeCredentials() throws Exception {
        String firstToken;
        try (OperationContext ctx = new OperationContext(session)) {
            Blob result = (Blob) automationService.run(ctx, GetDirectEditCredentialsOp.ID);
            JsonNode json = parseJson(result);
            assertTrue(json.get("enabled").asBoolean());
            assertEquals(session.getPrincipal().getName(), json.get("username").asText());
            firstToken = json.get("token").asText();
            assertNotNull(firstToken);
            assertFalse(firstToken.isBlank());
        }

        // acquireToken is idempotent on (user, application, device), so the user keeps a single stable token.
        try (OperationContext ctx = new OperationContext(session)) {
            Blob result = (Blob) automationService.run(ctx, GetDirectEditCredentialsOp.ID);
            assertEquals(firstToken, parseJson(result).get("token").asText());
        }

        try (OperationContext ctx = new OperationContext(session)) {
            automationService.run(ctx, RevokeDirectEditCredentialsOp.ID);
        }

        // After revocation a brand new token is issued.
        try (OperationContext ctx = new OperationContext(session)) {
            Blob result = (Blob) automationService.run(ctx, GetDirectEditCredentialsOp.ID);
            assertFalse(firstToken.equals(parseJson(result).get("token").asText()));
        }
    }

    @Test
    public void shouldTolerateRevokingWithoutAnyToken() throws Exception {
        try (OperationContext ctx = new OperationContext(session)) {
            automationService.run(ctx, RevokeDirectEditCredentialsOp.ID);
        }
    }
}
