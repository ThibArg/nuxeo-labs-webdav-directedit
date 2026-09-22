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
import static nuxeo.labs.webdav.directedit.WebDavDirectEditTestUtils.createFolder;
import static nuxeo.labs.webdav.directedit.WebDavDirectEditTestUtils.createWorkspace;
import static nuxeo.labs.webdav.directedit.WebDavDirectEditTestUtils.createWorkspaceRoot;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import jakarta.inject.Inject;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.trash.TrashService;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;

/**
 * Covers the mapping from a document to its WebDAV URL, and every reason a document can be refused.
 *
 * @since 2025.1
 */
@RunWith(FeaturesRunner.class)
@Features(WebDavDirectEditFeature.class)
@Deploy("nuxeo.labs.webdavdirectedit.nuxeo-labs-webdav-directedit-core:test-base-url.xml")
public class TestWebDavDirectEditService {

    protected static final String BASE_URL = "https://nuxeo.example.com/nuxeo";

    @Inject
    protected CoreSession session;

    @Inject
    protected WebDavDirectEditService service;

    protected DocumentModel workspace;

    @Before
    public void setUp() {
        createWorkspaceRoot(session);
        workspace = createWorkspace(session, "my-workspace");
        session.save();
    }

    @Test
    public void shouldBeEnabledByDefault() {
        assertTrue(service.isEnabled());
    }

    @Test
    public void shouldBuildUrlForWordDocumentAtWorkspaceRoot() {
        DocumentModel doc = createFile(session, workspace, "report", "Report.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        session.save();

        DirectEditInfo info = service.getDirectEditInfo(doc, null, null);

        assertTrue(info.available());
        assertNull(info.reason());
        assertEquals(OfficeApp.WORD, info.app());
        assertEquals("Report.docx", info.fileName());
        // The document is addressed by id, so its location in the repository is irrelevant.
        // The first segment is an opaque edit session token, not the document id: it also authenticates the
        // request, which is what spares Office a credentials prompt under SSO.
        String token = tokenOf(info);
        assertNotEquals(doc.getId(), token);
        assertEquals(token + "/Report.docx", info.davPath());
        assertEquals(BASE_URL + "/site/officeedit/" + token + "/Report.docx", info.davUrl());
        assertEquals("ms-word:ofe|u|" + BASE_URL + "/site/officeedit/" + token + "/Report.docx",
                info.protocolUrl());
        assertTrue(info.secure());
    }

    @Test
    public void shouldBuildUrlForNestedDocument() {
        DocumentModel folder = createFolder(session, workspace, "sub-folder");
        DocumentModel doc = createFile(session, folder, "budget", "Budget.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        session.save();

        DirectEditInfo info = service.getDirectEditInfo(doc, null, null);

        assertTrue(info.available());
        assertEquals(OfficeApp.EXCEL, info.app());
        // Depth in the repository no longer shows up in the address at all.
        assertEquals(tokenOf(info) + "/Budget.xlsx", info.davPath());
    }

    @Test
    public void shouldPercentEncodeSpacesAndAccents() {
        DocumentModel folder = createFolder(session, workspace, "mes dossiers");
        DocumentModel doc = createFile(session, folder, "presentation", "Réunion annuelle.pptx",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation");
        session.save();

        DirectEditInfo info = service.getDirectEditInfo(doc, null, null);

        assertTrue(info.available());
        assertEquals(OfficeApp.POWERPOINT, info.app());
        // davPath stays readable, only the URL is encoded.
        assertEquals(tokenOf(info) + "/Réunion annuelle.pptx", info.davPath());
        assertEquals(BASE_URL + "/site/officeedit/" + tokenOf(info) + "/R%C3%A9union%20annuelle.pptx",
                info.davUrl());
    }

    @Test
    public void shouldDetectOfficeFileFromExtensionWhenMimeTypeIsUnknown() {
        DocumentModel doc = createFile(session, workspace, "legacy", "Legacy.doc", "application/octet-stream");
        session.save();

        DirectEditInfo info = service.getDirectEditInfo(doc, null, null);

        assertTrue(info.available());
        assertEquals(OfficeApp.WORD, info.app());
    }

    @Test
    public void shouldDetectOfficeFileFromMimeTypeWhenExtensionIsMissing() {
        DocumentModel doc = createFile(session, workspace, "noext", "spreadsheet", "application/vnd.ms-excel");
        session.save();

        DirectEditInfo info = service.getDirectEditInfo(doc, null, null);

        assertTrue(info.available());
        assertEquals(OfficeApp.EXCEL, info.app());
    }

    @Test
    public void shouldRejectNonOfficeFile() {
        DocumentModel doc = createFile(session, workspace, "readme", "readme.txt", "text/plain");
        session.save();

        DirectEditInfo info = service.getDirectEditInfo(doc, null, null);

        assertFalse(info.available());
        assertEquals(UnavailabilityReason.NOT_OFFICE_FILE, info.reason());
        // The file name is still reported so the Web UI can name the document in its message.
        assertEquals("readme.txt", info.fileName());
        assertNull(info.protocolUrl());
    }

    @Test
    public void shouldRejectDocumentWithoutBlob() {
        DocumentModel doc = WebDavDirectEditTestUtils.createEmptyFile(session, workspace, "empty");
        session.save();

        DirectEditInfo info = service.getDirectEditInfo(doc, null, null);

        assertFalse(info.available());
        assertEquals(UnavailabilityReason.NO_BLOB, info.reason());
    }

    @Test
    public void shouldRejectNonMainBlobXPath() {
        DocumentModel doc = createFile(session, workspace, "report", "Report.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        session.save();

        // WebDAV reads and writes only the BlobHolder blob. Pointing Office at an attachment would make it silently
        // overwrite file:content on save, so this must be refused.
        DirectEditInfo info = service.getDirectEditInfo(doc, "files:files/0/file", null);

        assertFalse(info.available());
        assertEquals(UnavailabilityReason.NOT_MAIN_BLOB, info.reason());
    }

    @Test
    public void shouldOpenADocumentOutsideAnyWorkspace() {
        // The reason this plugin no longer uses the nuxeo-webdav addon: its backend publishes only top-level
        // Workspaces, so a document anywhere else had no WebDAV address at all and the button refused to open it.
        DocumentModel looseFolder = session.createDocumentModel("/", "outside", "Folder");
        looseFolder = session.createDocument(looseFolder);
        DocumentModel doc = createFile(session, looseFolder, "report", "Report.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        session.save();

        DirectEditInfo info = service.getDirectEditInfo(doc, null, null);

        assertTrue(info.available());
        assertNull(info.reason());
        assertEquals(tokenOf(info) + "/Report.docx", info.davPath());
    }

    @Test
    public void shouldOpenBothDocumentsSharingABlobFileName() {
        // Addressing by id removes the ambiguity that made one of these two documents unopenable: a path-based
        // WebDAV tree addresses files by blob file name, and two siblings sharing one are indistinguishable.
        DocumentModel first = createFile(session, workspace, "first", "Report.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        DocumentModel second = createFile(session, workspace, "second", "Report.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        session.save();

        DirectEditInfo firstInfo = service.getDirectEditInfo(first, null, null);
        DirectEditInfo secondInfo = service.getDirectEditInfo(second, null, null);

        assertTrue(firstInfo.available());
        assertTrue(secondInfo.available());
        assertNotEquals("each document must get its own address", firstInfo.davPath(), secondInfo.davPath());
        assertEquals(tokenOf(firstInfo) + "/Report.docx", firstInfo.davPath());
        assertEquals(tokenOf(secondInfo) + "/Report.docx", secondInfo.davPath());
    }

    @Test
    public void shouldRejectVersionsProxiesAndTrashedDocuments() {
        DocumentModel doc = createFile(session, workspace, "report", "Report.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        session.save();

        DocumentModel version = session.getDocument(session.checkIn(doc.getRef(), null, null));
        assertEquals(UnavailabilityReason.NOT_EDITABLE,
                service.getDirectEditInfo(version, null, null).reason());

        Framework.getService(TrashService.class).trashDocument(doc);
        session.save();
        assertEquals(UnavailabilityReason.NOT_EDITABLE,
                service.getDirectEditInfo(session.getDocument(doc.getRef()), null, null).reason());
    }

    @Test
    public void shouldTolerateANullDocument() {
        // A log lambda holding doc::getId would blow up here even with DEBUG off.
        DirectEditInfo info = service.getDirectEditInfo(null, null, null);

        assertFalse(info.available());
        assertEquals(UnavailabilityReason.NO_BLOB, info.reason());
    }

    @Test
    public void shouldIgnoreAnUnacceptableCallerSuppliedBaseUrl() {
        // The caller-supplied base URL ends up in a link the user is invited to click, so only plain absolute
        // http(s) URLs are accepted. The configured one wins here anyway, but the guard itself must hold.
        assertFalse(WebDavDirectEditServiceImpl.isAcceptableBaseUrl("javascript:alert(1)"));
        assertFalse(WebDavDirectEditServiceImpl.isAcceptableBaseUrl("https://host/nuxeo?a=b"));
        assertFalse(WebDavDirectEditServiceImpl.isAcceptableBaseUrl("https://user:pwd@host/nuxeo"));
        assertFalse(WebDavDirectEditServiceImpl.isAcceptableBaseUrl("/nuxeo"));
        assertFalse(WebDavDirectEditServiceImpl.isAcceptableBaseUrl(""));
        assertTrue(WebDavDirectEditServiceImpl.isAcceptableBaseUrl("https://host/nuxeo"));
        assertTrue(WebDavDirectEditServiceImpl.isAcceptableBaseUrl("http://host:8080/nuxeo"));
    }

    @Test
    public void shouldDetectLoopbackHostsByHostNotSubstring() {
        assertTrue(WebDavDirectEditServiceImpl.isLoopback("http://localhost:8080/nuxeo"));
        assertTrue(WebDavDirectEditServiceImpl.isLoopback("http://127.0.0.1:8080/nuxeo"));
        // A real host that merely mentions localhost in its path must not be mistaken for a loopback address.
        assertFalse(WebDavDirectEditServiceImpl.isLoopback("https://nuxeo.example.com/localhost"));
    }

    @Test
    @Deploy("nuxeo.labs.webdavdirectedit.nuxeo-labs-webdav-directedit-core:test-disabled.xml")
    public void shouldEnforceTheKillSwitchServerSide() {
        // The Web UI hides the button, but an operation can always be called directly.
        DocumentModel doc = createFile(session, workspace, "report", "Report.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        session.save();

        assertFalse(service.isEnabled());

        DirectEditInfo info = service.getDirectEditInfo(doc, null, null);
        assertFalse(info.available());
        assertEquals(UnavailabilityReason.DISABLED, info.reason());
        assertNull(info.protocolUrl());
    }

    @Test
    public void shouldReportTheMainBlobXPath() {
        DocumentModel doc = createFile(session, workspace, "report", "Report.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        session.save();

        assertEquals("file:content", service.getMainBlobXPath(doc).orElseThrow());
    }

    @Test
    public void shouldResolveOfficeAppFromMimeTypeAndFileName() {
        assertEquals(OfficeApp.WORD, service.resolveOfficeApp("application/msword", null).orElseThrow());
        assertEquals(OfficeApp.EXCEL, service.resolveOfficeApp(null, "a.XLSX").orElseThrow());
        assertEquals(OfficeApp.POWERPOINT, service.resolveOfficeApp(null, "deck.pptm").orElseThrow());
        assertTrue(service.resolveOfficeApp("image/png", "photo.png").isEmpty());
        assertTrue(service.resolveOfficeApp(null, null).isEmpty());
        // "???" is the sentinel jMimeMagic returns when it cannot decide; the extension must win.
        assertEquals(OfficeApp.WORD, service.resolveOfficeApp("???", "notes.docx").orElseThrow());
    }

    @Test
    public void shouldMarkPlainHttpUrlAsInsecure() {
        DirectEditInfo info = DirectEditInfo.available(OfficeApp.WORD, "a.docx", "id/a.docx",
                "http://host/nuxeo/site/officeedit/id/a.docx");
        assertFalse(info.secure());
        assertNotNull(info.protocolUrl());
    }

    /** The edit session token is the first segment of the dav path. */
    protected static String tokenOf(DirectEditInfo info) {
        return info.davPath().substring(0, info.davPath().indexOf('/'));
    }
}
