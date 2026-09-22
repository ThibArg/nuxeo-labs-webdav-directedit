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
 * Reason why a document cannot be opened in an Office desktop application.
 * <p>
 * The {@link #getId() id} is sent to the Web UI, which resolves it to the i18n key
 * {@code webdavDirectEdit.error.<id>}.
 *
 * @since 2025.1
 */
public enum UnavailabilityReason {

    /** The feature is switched off, see {@code org.nuxeo.web.ui.webdavDirectEdit.enabled}. */
    DISABLED("disabled"),

    /** The document has no blob at the requested xpath. */
    NO_BLOB("noBlob"),

    /**
     * The requested xpath is not the document's main blob. The endpoint reads and writes only the blob exposed by the
     * document's {@code BlobHolder}, so any other blob is out of reach.
     */
    NOT_MAIN_BLOB("notMainBlob"),

    /** The blob is not a Microsoft Office document. */
    NOT_OFFICE_FILE("notOfficeFile"),

    /** The document cannot be edited at all: it is a version, a proxy, or it is trashed. */
    NOT_EDITABLE("notEditable"),

    /** The user cannot write the document, so Office would fail on save. */
    NO_WRITE_PERMISSION("noWritePermission");

    protected final String id;

    UnavailabilityReason(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }
}
