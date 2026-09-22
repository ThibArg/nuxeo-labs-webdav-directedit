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

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.Blobs;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.api.blobholder.BlobHolder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Repository fixtures shared by the tests.
 * <p>
 * The tree mirrors what the default WebDAV backend expects: a {@code WorkspaceRoot} holding top-level
 * {@code Workspace} documents, which are the only entries the backend publishes.
 *
 * @since 2025.1
 */
public class WebDavDirectEditTestUtils {

    public static final String WORKSPACES_PATH = "/workspaces";

    private WebDavDirectEditTestUtils() {
        // utility class
    }

    /**
     * Creates {@code /workspaces} and returns it.
     */
    public static DocumentModel createWorkspaceRoot(CoreSession session) {
        DocumentModel root = session.createDocumentModel("/", "workspaces", "WorkspaceRoot");
        root.setPropertyValue("dc:title", "Workspaces");
        return session.createDocument(root);
    }

    /**
     * Creates a top-level Workspace below {@code /workspaces}.
     */
    public static DocumentModel createWorkspace(CoreSession session, String name) {
        DocumentModel workspace = session.createDocumentModel(WORKSPACES_PATH, name, "Workspace");
        workspace.setPropertyValue("dc:title", name);
        return session.createDocument(workspace);
    }

    /**
     * Creates a Folder below the given parent.
     */
    public static DocumentModel createFolder(CoreSession session, DocumentModel parent, String name) {
        DocumentModel folder = session.createDocumentModel(parent.getPathAsString(), name, "Folder");
        folder.setPropertyValue("dc:title", name);
        return session.createDocument(folder);
    }

    /**
     * Creates a File whose document name and blob file name may differ, which is the normal situation in Nuxeo and the
     * reason WebDAV addresses files by blob file name.
     */
    public static DocumentModel createFile(CoreSession session, DocumentModel parent, String docName,
            String blobFileName, String mimeType) {
        return createFile(session, parent, docName, blobFileName, mimeType, "dummy content");
    }

    /**
     * Creates a File with the given blob content, so a round trip through the WebDAV endpoint can be asserted on.
     */
    public static DocumentModel createFile(CoreSession session, DocumentModel parent, String docName,
            String blobFileName, String mimeType, String content) {
        DocumentModel file = session.createDocumentModel(parent.getPathAsString(), docName, "File");
        file.setPropertyValue("dc:title", blobFileName);
        Blob blob = Blobs.createBlob(content, mimeType, StandardCharsets.UTF_8.name());
        blob.setFilename(blobFileName);
        file.getAdapter(BlobHolder.class).setBlob(blob);
        return session.createDocument(file);
    }

    /**
     * Creates a File with no blob at all.
     */
    public static DocumentModel createEmptyFile(CoreSession session, DocumentModel parent, String docName) {
        DocumentModel file = session.createDocumentModel(parent.getPathAsString(), docName, "File");
        file.setPropertyValue("dc:title", docName);
        return session.createDocument(file);
    }

    /**
     * Parses a JSON blob returned by one of the operations.
     */
    public static JsonNode parseJson(Blob blob) {
        try {
            return new ObjectMapper().readTree(blob.getString());
        } catch (IOException e) {
            throw new NuxeoException("Could not parse the operation result", e);
        }
    }
}
