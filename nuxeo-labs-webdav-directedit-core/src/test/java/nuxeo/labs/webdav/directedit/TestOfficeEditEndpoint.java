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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.UUID;

import jakarta.inject.Inject;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpOptions;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.BasicAuthCache;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.apache.jackrabbit.webdav.DavConstants;
import org.apache.jackrabbit.webdav.client.methods.HttpDelete;
import org.apache.jackrabbit.webdav.client.methods.HttpLock;
import org.apache.jackrabbit.webdav.client.methods.HttpMkcol;
import org.apache.jackrabbit.webdav.client.methods.HttpMove;
import org.apache.jackrabbit.webdav.client.methods.HttpPropfind;
import org.apache.jackrabbit.webdav.client.methods.HttpProppatch;
import org.apache.jackrabbit.webdav.client.methods.HttpUnlock;
import org.apache.jackrabbit.webdav.lock.LockInfo;
import org.apache.jackrabbit.webdav.lock.Scope;
import org.apache.jackrabbit.webdav.lock.Type;
import org.apache.jackrabbit.webdav.property.DavPropertyName;
import org.apache.jackrabbit.webdav.property.DavPropertyNameSet;
import org.apache.jackrabbit.webdav.property.DavPropertySet;
import org.apache.jackrabbit.webdav.property.DefaultDavProperty;
import org.apache.jackrabbit.webdav.xml.Namespace;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.api.blobholder.BlobHolder;
import org.nuxeo.ecm.core.api.trash.TrashService;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.kv.KeyValueService;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.ServletContainerFeature;
import org.nuxeo.runtime.test.runner.TransactionalFeature;

import nuxeo.labs.webdav.directedit.server.OfficeEditSessionStore;

/**
 * Drives the endpoint over real HTTP with a real WebDAV client.
 * <p>
 * The two tests that matter most are {@link #shouldReplayTheOfficeSaveDance()}, which replays the exact request
 * sequence Microsoft Word performs when it saves, and {@link #shouldOpenADocumentOutsideAnyWorkspace()}, which covers
 * the case the platform's WebDAV module cannot serve at all.
 *
 * @since 2025.1
 */
@RunWith(FeaturesRunner.class)
@Features(OfficeEditServerFeature.class)
@Deploy("nuxeo.labs.webdavdirectedit.nuxeo-labs-webdav-directedit-core:test-small-upload-limit.xml")
public class TestOfficeEditEndpoint {

    protected static final String USERNAME = "Administrator";

    protected static final String WORD_MIME_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    protected static final String ORIGINAL_CONTENT = "original content";

    @Inject
    protected CoreSession session;

    @Inject
    protected ServletContainerFeature servletContainerFeature;

    @Inject
    protected TransactionalFeature transactionalFeature;

    protected CloseableHttpClient client;

    protected HttpClientContext context;

    protected DocumentModel document;

    /** The edit session token that addresses {@link #document}; also the credential the URL carries. */
    protected String token;

    @Before
    public void setUp() {
        client = HttpClients.createDefault();
        context = basicAuthContext();

        WebDavDirectEditTestUtils.createWorkspaceRoot(session);
        DocumentModel workspace = WebDavDirectEditTestUtils.createWorkspace(session, "my-workspace");
        document = WebDavDirectEditTestUtils.createFile(session, workspace, "report", "Report.docx", WORD_MIME_TYPE,
                ORIGINAL_CONTENT);
        session.save();

        token = new OfficeEditSessionStore().create(document.getId(), USERNAME);
        transactionalFeature.nextTransaction();
    }

    @After
    public void tearDown() throws IOException {
        client.close();
    }

    /* ==================== OPTIONS ==================== */

    @Test
    public void shouldAdvertiseDavAndMsAuthorVia() throws Exception {
        // Word sends OPTIONS first and opens the file read-only unless both headers are present.
        try (CloseableHttpResponse response = execute(new HttpOptions(fileUri()))) {
            assertEquals(200, status(response));
            assertEquals("1,2", response.getFirstHeader("DAV").getValue());
            assertEquals("DAV", response.getFirstHeader("MS-Author-Via").getValue());
            assertTrue(response.getFirstHeader("Allow").getValue().contains("PROPFIND"));
        }
    }

    @Test
    public void shouldAnswerOptionsEvenForAnUnknownDocument() throws Exception {
        // OPTIONS must not depend on the document existing, or discovery fails before it starts.
        try (CloseableHttpResponse response = execute(new HttpOptions(baseUri() + "/no-such-token/Nope.docx"))) {
            assertEquals(200, status(response));
        }
    }

    /* ==================== PROPFIND ==================== */

    @Test
    public void shouldListTheCollectionAndItsSingleFile() throws Exception {
        var request = new HttpPropfind(collectionUri(), DavConstants.PROPFIND_ALL_PROP, DavConstants.DEPTH_1);
        try (CloseableHttpResponse response = execute(request)) {
            assertEquals(207, status(response));
            String body = body(response);
            assertTrue(body.contains("<D:collection"));
            assertTrue(body.contains("Report.docx"));
            assertTrue(body.contains("getcontentlength"));
            // sabre/dav documents that some Office builds break when lockroot is present.
            assertTrue("lockroot must never be emitted", !body.contains("lockroot"));
        }
    }

    @Test
    public void shouldReportTheBlobLengthAndType() throws Exception {
        var request = new HttpPropfind(fileUri(), DavConstants.PROPFIND_ALL_PROP, DavConstants.DEPTH_0);
        try (CloseableHttpResponse response = execute(request)) {
            assertEquals(207, status(response));
            String body = body(response);
            assertTrue(body.contains(">" + ORIGINAL_CONTENT.length() + "<"));
            assertTrue(body.contains(WORD_MIME_TYPE));
        }
    }

    /* ==================== GET ==================== */

    @Test
    public void shouldServeTheBlob() throws Exception {
        try (CloseableHttpResponse response = execute(new HttpGet(fileUri()))) {
            assertEquals(200, status(response));
            assertEquals(ORIGINAL_CONTENT, body(response));
            assertEquals("bytes", response.getFirstHeader("Accept-Ranges").getValue());
        }
    }

    @Test
    public void shouldServeAByteRange() throws Exception {
        var request = new HttpGet(fileUri());
        request.setHeader("Range", "bytes=0-7");
        try (CloseableHttpResponse response = execute(request)) {
            assertEquals(206, status(response));
            assertEquals(ORIGINAL_CONTENT.substring(0, 8), body(response));
            assertEquals("bytes 0-7/" + ORIGINAL_CONTENT.length(),
                    response.getFirstHeader("Content-Range").getValue());
        }
    }

    /* ==================== LOCK ==================== */

    @Test
    public void shouldLockAndUnlockTheDocument() throws Exception {
        // Without a successful LOCK, Word opens the document read-only.
        String lockToken;
        var lock = new HttpLock(fileUri(), new LockInfo(Scope.EXCLUSIVE, Type.WRITE, USERNAME, 10000L, false));
        try (CloseableHttpResponse response = execute(lock)) {
            assertEquals(200, status(response));
            lockToken = response.getFirstHeader("Lock-Token").getValue();
            assertTrue(lockToken.startsWith("<urn:uuid:"));
        }
        transactionalFeature.nextTransaction();
        assertNotNull(session.getLockInfo(document.getRef()));

        try (CloseableHttpResponse response = execute(new HttpUnlock(fileUri(), lockToken))) {
            assertEquals(204, status(response));
        }
        transactionalFeature.nextTransaction();
        assertNull(session.getLockInfo(document.getRef()));
    }

    /* ==================== Saving ==================== */

    @Test
    public void shouldSaveWithADirectPut() throws Exception {
        try (CloseableHttpResponse response = execute(put(fileUri(), "edited in Word"))) {
            assertEquals(204, status(response));
        }
        transactionalFeature.nextTransaction();
        assertEquals("edited in Word", mainBlobString());
    }

    /**
     * Replays the transacted save Microsoft Word performs, step for step.
     * <p>
     * This is the sequence the whole design turns on, and the assertion that no {@code .tmp} or {@code ~$} document is
     * left behind is the improvement over routing this through the platform's WebDAV module.
     */
    @Test
    public void shouldReplayTheOfficeSaveDance() throws Exception {
        String newContent = "saved from Word";
        String temporary = collectionUri() + "/A1B2C3D4.tmp";
        String backup = collectionUri() + "/E5F6A7B8.tmp";

        // 1. the new content goes to a temporary file
        try (CloseableHttpResponse response = execute(put(temporary, newContent))) {
            assertEquals(201, status(response));
        }
        // 2. the original is moved aside
        try (CloseableHttpResponse response = execute(new HttpMove(fileUri(), backup, true))) {
            assertEquals(201, status(response));
        }
        // 3. the temporary file takes the original's name: this is the commit
        try (CloseableHttpResponse response = execute(new HttpMove(temporary, fileUri(), true))) {
            assertEquals(204, status(response));
        }
        // 4. the set-aside original is dropped
        try (CloseableHttpResponse response = execute(new HttpDelete(backup))) {
            assertEquals(204, status(response));
        }

        transactionalFeature.nextTransaction();
        assertEquals(newContent, mainBlobString());
        // The file name and mime type survive the round trip through two temporary names.
        assertEquals("Report.docx", mainBlob().getFilename());
        assertEquals(WORD_MIME_TYPE, mainBlob().getMimeType());
    }

    @Test
    public void shouldNotCreateAnyDocumentForOfficeTemporaryFiles() throws Exception {
        long before = countDocuments();

        try (CloseableHttpResponse response = execute(put(collectionUri() + "/~$Report.docx", "owner"))) {
            assertEquals(201, status(response));
        }
        try (CloseableHttpResponse response = execute(put(collectionUri() + "/AABBCCDD.tmp", "scratch"))) {
            assertEquals(201, status(response));
        }
        transactionalFeature.nextTransaction();

        assertEquals("no temporary document may reach the repository", before, countDocuments());
        // They are still readable as WebDAV resources, which is what Office expects.
        try (CloseableHttpResponse response = execute(new HttpGet(collectionUri() + "/~$Report.docx"))) {
            assertEquals(200, status(response));
            assertEquals("owner", body(response));
        }
    }

    /* ==================== The reason this endpoint exists ==================== */

    /**
     * A document with no Workspace anywhere in its path. The platform's WebDAV module publishes only top-level
     * Workspaces and their content, so this document has no WebDAV address at all there.
     */
    @Test
    public void shouldOpenADocumentOutsideAnyWorkspace() throws Exception {
        DocumentModel folder = session.createDocumentModel("/", "loose-folder", "Folder");
        folder = session.createDocument(folder);
        DocumentModel outsider = WebDavDirectEditTestUtils.createFile(session, folder, "outsider", "Outsider.docx",
                WORD_MIME_TYPE, "outside any workspace");
        session.save();
        transactionalFeature.nextTransaction();

        String uri = baseUri() + "/" + new OfficeEditSessionStore().create(outsider.getId(), USERNAME)
                + "/Outsider.docx";
        try (CloseableHttpResponse response = execute(new HttpGet(uri))) {
            assertEquals(200, status(response));
            assertEquals("outside any workspace", body(response));
        }
        try (CloseableHttpResponse response = execute(put(uri, "edited anyway"))) {
            assertEquals(204, status(response));
        }
        transactionalFeature.nextTransaction();
        assertEquals("edited anyway",
                session.getDocument(new IdRef(outsider.getId())).getAdapter(BlobHolder.class).getBlob().getString());
    }

    /* ==================== Single sign-on ==================== */

    /**
     * The whole point of the capability URL: Office never sends credentials and never sees a prompt.
     * <p>
     * Microsoft Office has no browser and supports only Basic and Digest, so on an SSO-only deployment the user has
     * no password to type. Everything below runs with <b>no {@code Authorization} header whatsoever</b>.
     */
    @Test
    public void shouldCompleteAWholeSaveWithNoCredentialsAtAll() throws Exception {
        String temporary = collectionUri() + "/A1B2C3D4.tmp";
        String backup = collectionUri() + "/E5F6A7B8.tmp";

        try (CloseableHttpResponse response = executeAnonymously(new HttpGet(fileUri()))) {
            assertEquals(200, status(response));
            assertEquals(ORIGINAL_CONTENT, body(response));
        }
        var lock = new HttpLock(fileUri(), new LockInfo(Scope.EXCLUSIVE, Type.WRITE, USERNAME, 10000L, false));
        try (CloseableHttpResponse response = executeAnonymously(lock)) {
            assertEquals(200, status(response));
        }
        try (CloseableHttpResponse response = executeAnonymously(put(temporary, "saved under SSO"))) {
            assertEquals(201, status(response));
        }
        try (CloseableHttpResponse response = executeAnonymously(new HttpMove(fileUri(), backup, true))) {
            assertEquals(201, status(response));
        }
        try (CloseableHttpResponse response = executeAnonymously(new HttpMove(temporary, fileUri(), true))) {
            assertEquals(204, status(response));
        }
        try (CloseableHttpResponse response = executeAnonymously(new HttpDelete(backup))) {
            assertEquals(204, status(response));
        }

        transactionalFeature.nextTransaction();
        assertEquals("saved under SSO", mainBlobString());
    }

    @Test
    public void shouldFallBackToABasicPromptWhenTheSessionIsUnknown() throws Exception {
        // An expired session must degrade to a challenge Office can answer, not to a failure.
        String stale = baseUri() + "/" + java.util.UUID.randomUUID() + "/Report.docx";
        try (CloseableHttpResponse response = executeAnonymously(new HttpGet(stale))) {
            assertEquals(401, status(response));
            assertTrue(response.getFirstHeader("WWW-Authenticate").getValue().startsWith("Basic realm="));
        }
    }

    @Test
    public void shouldNotLetASessionReachAnotherDocument() throws Exception {
        // The capability is the address: a token resolves to one document and there is no way to name another.
        DocumentModel other = WebDavDirectEditTestUtils.createFile(session,
                session.getDocument(document.getParentRef()), "other", "Other.docx", WORD_MIME_TYPE, "other content");
        session.save();
        transactionalFeature.nextTransaction();

        // Asking our session for the other document's file name is simply a scratch entry, never that document.
        try (CloseableHttpResponse response = executeAnonymously(new HttpGet(collectionUri() + "/Other.docx"))) {
            assertEquals(404, status(response));
        }
        try (CloseableHttpResponse response = executeAnonymously(put(collectionUri() + "/Other.docx", "hijacked"))) {
            assertEquals(201, status(response));
        }
        transactionalFeature.nextTransaction();
        assertEquals("other content",
                session.getDocument(new IdRef(other.getId())).getAdapter(BlobHolder.class).getBlob().getString());
    }

    @Test
    public void shouldRefuseASessionWhoseUserCannotBeAuthenticated() throws Exception {
        /*
         * A session names the user it was issued for, and the capability authenticator authenticates as that user.
         * If that user no longer resolves, the request must fail closed rather than fall back to whoever else is
         * presenting credentials: note that the client here does send Administrator's Basic credentials, and still
         * gets nothing.
         */
        String foreign = baseUri() + "/" + new OfficeEditSessionStore().create(document.getId(), "deleted-user")
                + "/Report.docx";
        try (CloseableHttpResponse response = execute(new HttpGet(foreign))) {
            assertEquals(401, status(response));
            assertFalse("the document content must not leak", body(response).contains(ORIGINAL_CONTENT));
        }
    }

    /**
     * The demonstration case, and the reason the session record outlives its authentication window.
     * <p>
     * Once the window has lapsed the URL no longer proves who is asking, so Office is challenged — but it still
     * designates its document, so answering the challenge opens the file. With a single lifetime this returned 404
     * and no correct password could rescue it.
     */
    @Test
    public void shouldServeTheDocumentWithBasicOnceTheAuthWindowHasLapsed() throws Exception {
        String staleUri = agedSessionUri();
        try (CloseableHttpResponse response = execute(new HttpGet(staleUri))) {
            assertEquals(200, status(response));
            assertEquals(ORIGINAL_CONTENT, body(response));
        }
        // And it still saves, which is what actually matters.
        try (CloseableHttpResponse response = execute(put(staleUri, "edited after re-authenticating"))) {
            assertEquals(204, status(response));
        }
        transactionalFeature.nextTransaction();
        assertEquals("edited after re-authenticating", mainBlobString());
    }

    @Test
    public void shouldChallengeWithBasicOnceTheAuthWindowHasLapsed() throws Exception {
        try (CloseableHttpResponse response = executeAnonymously(new HttpGet(agedSessionUri()))) {
            assertEquals(401, status(response));
            assertTrue(response.getFirstHeader("WWW-Authenticate").getValue().startsWith("Basic realm="));
            assertFalse("the document content must not leak", body(response).contains(ORIGINAL_CONTENT));
        }
    }

    /**
     * Writes a session record whose authentication stamp is older than the window, which is the one thing a test
     * cannot obtain by waiting. Composing the record here couples the test to the stored format, deliberately: that
     * format is the contract being exercised.
     */
    protected String agedSessionUri() {
        String staleToken = UUID.randomUUID().toString();
        // Issued well inside the absolute lifetime, but last authenticated long outside the idle window.
        long longAgo = System.currentTimeMillis()
                - (OfficeEditSessionStore.DEFAULT_AUTH_TTL_MINUTES + 60) * 60_000L;
        String sep = OfficeEditSessionStore.SEPARATOR;
        Framework.getService(KeyValueService.class)
                 .getKeyValueStore(OfficeEditSessionStore.STORE_NAME)
                 .put(staleToken, document.getId() + sep + longAgo + sep + longAgo + sep + USERNAME);
        return baseUri() + "/" + staleToken + "/Report.docx";
    }

    /* ==================== Hardening ==================== */

    @Test
    public void shouldRefusePropPatchWithoutAValidSession() throws Exception {
        // PROPPATCH answers 207 unconditionally, so it is the one verb that could otherwise be driven without
        // holding a capability, and it is the verb that reads an unbounded body.
        var request = new HttpProppatch(baseUri() + "/" + UUID.randomUUID() + "/Report.docx", win32Properties(),
                new DavPropertyNameSet());
        try (CloseableHttpResponse response = execute(request)) {
            assertEquals(404, status(response));
        }
    }

    @Test
    public void shouldAcceptPropPatchOnAValidSession() throws Exception {
        // Office sets Win32 properties on every save and treats a failure as fatal: the check must not break it.
        var request = new HttpProppatch(fileUri(), win32Properties(), new DavPropertyNameSet());
        try (CloseableHttpResponse response = execute(request)) {
            assertEquals(207, status(response));
            String body = body(response);
            // The property must be echoed back, in its own namespace, or Office treats the save as failed.
            assertTrue(body.contains("Win32LastModifiedTime"));
            assertTrue(body.contains("urn:schemas-microsoft-com:"));
        }
    }

    @Test
    public void shouldRefuseABodyLargerThanTheCeiling() throws Exception {
        // The ceiling is configured down to 1 MB for this run, see test-small-upload-limit.xml.
        var request = put(collectionUri() + "/Big.tmp", "x".repeat(2 * 1024 * 1024));
        try (CloseableHttpResponse response = execute(request)) {
            assertEquals(413, status(response));
        }
    }

    @Test
    public void shouldRejectAControlCharacterInTheName() throws Exception {
        // A CR or LF in the decoded name would let a caller forge lines in server.log.
        try (CloseableHttpResponse response = execute(
                new HttpGet(collectionUri() + "/a%0D%0AINFO%20forged.docx"))) {
            assertEquals(404, status(response));
        }
    }

    @Test
    public void shouldRefuseToWriteADocumentThatHasBeenTrashed() throws Exception {
        // The service refuses a trashed document when it issues the address; the servlet must re-check, because a
        // document can be trashed while Office still holds an address for it.
        Framework.getService(TrashService.class).trashDocument(document);
        session.save();
        transactionalFeature.nextTransaction();

        try (CloseableHttpResponse response = execute(put(fileUri(), "should not land"))) {
            assertEquals(403, status(response));
        }
    }

    /* ==================== What the endpoint refuses ==================== */

    @Test
    public void shouldRefuseToDeleteTheDocumentFile() throws Exception {
        try (CloseableHttpResponse response = execute(new HttpDelete(fileUri()))) {
            assertEquals(403, status(response));
        }
        transactionalFeature.nextTransaction();
        assertTrue(session.exists(document.getRef()));
    }

    @Test
    public void shouldRefuseMkcol() throws Exception {
        try (CloseableHttpResponse response = execute(new HttpMkcol(collectionUri() + "/subfolder"))) {
            assertEquals(405, status(response));
        }
    }

    @Test
    public void shouldRefuseToMoveAcrossCollections() throws Exception {
        String elsewhere = baseUri() + "/" + new OfficeEditSessionStore().create(
                session.getRootDocument().getId(), USERNAME) + "/Stolen.docx";
        try (CloseableHttpResponse response = execute(new HttpMove(fileUri(), elsewhere, true))) {
            assertEquals(403, status(response));
        }
    }

    @Test
    public void shouldReturnNotFoundForAnUnknownDocument() throws Exception {
        try (CloseableHttpResponse response = execute(new HttpGet(baseUri() + "/nosuchsession/Report.docx"))) {
            assertEquals(404, status(response));
        }
    }

    @Test
    public void shouldReturnNotFoundForAnUnknownScratchName() throws Exception {
        try (CloseableHttpResponse response = execute(new HttpGet(collectionUri() + "/never-written.tmp"))) {
            assertEquals(404, status(response));
        }
    }

    /* ==================== Helpers ==================== */

    protected String baseUri() {
        return servletContainerFeature.getHttpUrl() + "/site/officeedit";
    }

    protected String collectionUri() {
        return baseUri() + "/" + token;
    }

    protected String fileUri() {
        return collectionUri() + "/" + URLEncoder.encode("Report.docx", UTF_8).replace("+", "%20");
    }

    protected HttpPut put(String uri, String content) {
        var request = new HttpPut(uri);
        request.setEntity(new ByteArrayEntity(content.getBytes(UTF_8)));
        return request;
    }

    protected CloseableHttpResponse execute(HttpUriRequest request) throws IOException {
        return client.execute(request, context);
    }

    /**
     * Sends the request with no credentials of any kind, which is how Office behaves when the URL carries an edit
     * session token.
     */
    protected CloseableHttpResponse executeAnonymously(HttpUriRequest request) throws IOException {
        return client.execute(request, HttpClientContext.create());
    }

    protected static int status(CloseableHttpResponse response) {
        return response.getStatusLine().getStatusCode();
    }

    protected static String body(CloseableHttpResponse response) throws IOException {
        return response.getEntity() == null ? "" : EntityUtils.toString(response.getEntity(), UTF_8);
    }

    protected Blob mainBlob() {
        return session.getDocument(new IdRef(document.getId())).getAdapter(BlobHolder.class).getBlob();
    }

    protected String mainBlobString() throws IOException {
        return mainBlob().getString();
    }

    protected long countDocuments() {
        return session.query("SELECT * FROM Document WHERE ecm:isTrashed = 0").size();
    }

    /** Preemptive Basic authentication: Office sends credentials the same way once it has been challenged. */
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

    /** The four properties Microsoft Office sets on every save. */
    protected DavPropertySet win32Properties() {
        var namespace = Namespace.getNamespace("Z", "urn:schemas-microsoft-com:");
        var properties = new DavPropertySet();
        properties.add(new DefaultDavProperty<>(DavPropertyName.create("Win32LastModifiedTime", namespace),
                "Mon, 12 Dec 2011 17:37:08 GMT"));
        return properties;
    }
}
