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

import java.util.Optional;

import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.DocumentModel;

/**
 * Computes whether, and how, a document can be opened in a Microsoft Office desktop application over WebDAV.
 *
 * @since 2025.1
 */
public interface WebDavDirectEditService {

    /** Default xpath of the blob WebDAV exposes. */
    String DEFAULT_BLOB_XPATH = "file:content";

    /**
     * Path prefix of this plugin's own WebDAV endpoint, relative to the Nuxeo context path.
     * <p>
     * A document is addressed as {@code <prefix><docId>/<blobFileName>}. Addressing by id rather than by repository
     * path is what lets any document be opened, with no requirement that it live under a Workspace.
     */
    String WEBDAV_PATH_PREFIX = "/site/officeedit/";

    /**
     * Whether the feature is enabled at all, see {@code org.nuxeo.web.ui.webdavDirectEdit.enabled}.
     */
    boolean isEnabled();

    /**
     * Returns the Office application able to open the given blob, based on its mime type first and on its file name
     * extension as a fallback.
     *
     * @return an empty optional when the blob is not a Microsoft Office document
     */
    Optional<OfficeApp> resolveOfficeApp(Blob blob);

    /**
     * Returns the Office application matching the given mime type and file name. Either may be {@code null}.
     *
     * @return an empty optional when neither matches a Microsoft Office format
     */
    Optional<OfficeApp> resolveOfficeApp(String mimeType, String fileName);

    /**
     * Computes everything the Web UI needs to open the document in Office, or the reason why it cannot.
     * <p>
     * This never throws for a business reason: an unopenable document yields an unavailable {@link DirectEditInfo}
     * carrying an {@link UnavailabilityReason}.
     *
     * @param doc the document to open
     * @param xpath the blob xpath, {@code null} for the configured default; must designate the document's main blob
     * @param requestBaseUrl base URL as seen by the browser, used only when the server has no configured one; may be
     *            {@code null}
     */
    DirectEditInfo getDirectEditInfo(DocumentModel doc, String xpath, String requestBaseUrl);

    /**
     * Returns the xpath WebDAV actually reads and writes for the given document, that is the xpath of its
     * {@code BlobHolder}.
     *
     * @return an empty optional when the document has no {@code BlobHolder}, or one that is not xpath-backed
     */
    Optional<String> getMainBlobXPath(DocumentModel doc);
}
