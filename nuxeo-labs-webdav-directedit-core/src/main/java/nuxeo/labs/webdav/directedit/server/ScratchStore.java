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

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.transientstore.api.TransientStore;
import org.nuxeo.ecm.core.transientstore.api.TransientStoreService;
import org.nuxeo.runtime.api.Framework;

/**
 * Holds the temporary files Microsoft Office creates while it saves, without ever materialising them as Nuxeo
 * documents.
 * <p>
 * Saving a document from Word is not a single {@code PUT}. Office performs a transacted save inside the containing
 * collection:
 *
 * <pre>
 * PUT    tmp1.tmp        the new content
 * MOVE   file.docx  -&gt;  tmp2.tmp    the original is set aside
 * MOVE   tmp1.tmp   -&gt;  file.docx   the new content takes its place
 * DELETE tmp2.tmp        the original is dropped
 * </pre>
 *
 * It also keeps an owner file named {@code ~$file.docx} for the whole editing session. Only the canonical file name
 * maps to the Nuxeo document; every other name in the collection lives here, in a {@link TransientStore}, and
 * disappears on its own. That is what keeps {@code ~$} and {@code .tmp} documents out of the repository entirely.
 * <p>
 * Entries are keyed by edit session token, which is already per user and per document, so two people editing the
 * same document cannot collide in the scratch space. Concurrent writes to the document itself are prevented by the
 * Nuxeo lock, not here.
 * <p>
 * <b>No client has yet been observed writing here</b> — see "What a real Office client actually does" in
 * {@code AGENTS.md}. Both Word for Mac and Word for Windows send a plain {@code PUT} on the canonical name through
 * the {@code ms-word:} protocol handler. The path is kept because unobserved is not unreachable: opening the address
 * with File then Open, or from a mapped drive, routes Windows through the WebClient service, and Excel, PowerPoint
 * and older Office versions are untested.
 *
 * @since 2025.1
 */
public class ScratchStore {

    private static final Logger log = LogManager.getLogger(ScratchStore.class);

    /** Must match the store declared in {@code webdav-directedit-transientstore-contrib.xml}. */
    public static final String STORE_NAME = "officeEditScratch";

    /**
     * Marks an entry that stands in for the document's own blob, created when Office moves the canonical file aside.
     * No bytes are copied: the content is served from the document itself.
     */
    protected static final String SHADOW_PARAM = "officeEditShadow";

    protected static final String SEPARATOR = "|";

    protected TransientStore store() {
        return Framework.getService(TransientStoreService.class).getStore(STORE_NAME);
    }

    protected static String key(String sessionToken, String name) {
        return sessionToken + SEPARATOR + name;
    }

    public boolean exists(String sessionToken, String name) {
        return store().exists(key(sessionToken, name));
    }

    /**
     * Whether this name stands in for the document's own blob rather than holding content of its own.
     */
    public boolean isShadow(String sessionToken, String name) {
        return Boolean.TRUE.equals(store().getParameter(key(sessionToken, name), SHADOW_PARAM));
    }

    /**
     * @return the stored content, or {@code null} when the entry is absent or is a shadow
     */
    public Blob getBlob(String sessionToken, String name) {
        List<Blob> blobs = store().getBlobs(key(sessionToken, name));
        return blobs == null || blobs.isEmpty() ? null : blobs.get(0);
    }

    public void putBlob(String sessionToken, String name, Blob blob) {
        String entryKey = key(sessionToken, name);
        TransientStore transientStore = store();
        transientStore.putBlobs(entryKey, List.of(blob));
        transientStore.setCompleted(entryKey, true);
        // Never log the entry key itself: it starts with the session token, which is a live credential.
        log.debug("Stored scratch entry {} in session {}", name, OfficeEditSessionStore.mask(sessionToken));
    }

    public void putShadow(String sessionToken, String name) {
        String entryKey = key(sessionToken, name);
        TransientStore transientStore = store();
        transientStore.putParameter(entryKey, SHADOW_PARAM, Boolean.TRUE);
        transientStore.setCompleted(entryKey, true);
        log.debug("Stored shadow entry {} in session {}", name, OfficeEditSessionStore.mask(sessionToken));
    }

    public void remove(String sessionToken, String name) {
        store().remove(key(sessionToken, name));
    }

    /**
     * Renames an entry inside the scratch space, preserving whether it is a shadow.
     */
    public void move(String sessionToken, String from, String to) {
        if (isShadow(sessionToken, from)) {
            putShadow(sessionToken, to);
        } else {
            Blob blob = getBlob(sessionToken, from);
            if (blob == null) {
                return;
            }
            putBlob(sessionToken, to, blob);
        }
        remove(sessionToken, from);
    }
}
