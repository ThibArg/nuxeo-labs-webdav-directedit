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

import static nuxeo.labs.webdav.directedit.server.DavConstants.BYTES;
import static nuxeo.labs.webdav.directedit.server.DavConstants.COPY;
import static nuxeo.labs.webdav.directedit.server.DavConstants.DAV_COMPLIANCE;
import static nuxeo.labs.webdav.directedit.server.DavConstants.DEFAULT_MIME_TYPE;
import static nuxeo.labs.webdav.directedit.server.DavConstants.DELETE;
import static nuxeo.labs.webdav.directedit.server.DavConstants.DEPTH_0;
import static nuxeo.labs.webdav.directedit.server.DavConstants.GET;
import static nuxeo.labs.webdav.directedit.server.DavConstants.HEAD;
import static nuxeo.labs.webdav.directedit.server.DavConstants.HEADER_ACCEPT_RANGES;
import static nuxeo.labs.webdav.directedit.server.DavConstants.HEADER_ALLOW;
import static nuxeo.labs.webdav.directedit.server.DavConstants.HEADER_CONTENT_RANGE;
import static nuxeo.labs.webdav.directedit.server.DavConstants.HEADER_DAV;
import static nuxeo.labs.webdav.directedit.server.DavConstants.HEADER_DEPTH;
import static nuxeo.labs.webdav.directedit.server.DavConstants.HEADER_DESTINATION;
import static nuxeo.labs.webdav.directedit.server.DavConstants.HEADER_ETAG;
import static nuxeo.labs.webdav.directedit.server.DavConstants.HEADER_LAST_MODIFIED;
import static nuxeo.labs.webdav.directedit.server.DavConstants.HEADER_LOCK_TOKEN;
import static nuxeo.labs.webdav.directedit.server.DavConstants.HEADER_MS_AUTHOR_VIA;
import static nuxeo.labs.webdav.directedit.server.DavConstants.HEADER_OVERWRITE;
import static nuxeo.labs.webdav.directedit.server.DavConstants.HEADER_RANGE;
import static nuxeo.labs.webdav.directedit.server.DavConstants.LOCK;
import static nuxeo.labs.webdav.directedit.server.DavConstants.MKCOL;
import static nuxeo.labs.webdav.directedit.server.DavConstants.MOVE;
import static nuxeo.labs.webdav.directedit.server.DavConstants.MS_AUTHOR_VIA_DAV;
import static nuxeo.labs.webdav.directedit.server.DavConstants.OPTIONS;
import static nuxeo.labs.webdav.directedit.server.DavConstants.PROPFIND;
import static nuxeo.labs.webdav.directedit.server.DavConstants.PROPPATCH;
import static nuxeo.labs.webdav.directedit.server.DavConstants.PUT;
import static nuxeo.labs.webdav.directedit.server.DavConstants.SC_INSUFFICIENT_STORAGE;
import static nuxeo.labs.webdav.directedit.server.DavConstants.SC_LOCKED;
import static nuxeo.labs.webdav.directedit.server.DavConstants.SC_MULTI_STATUS;
import static nuxeo.labs.webdav.directedit.server.DavConstants.UNKNOWN_MIME_TYPE;
import static nuxeo.labs.webdav.directedit.server.DavConstants.UNLOCK;
import static nuxeo.labs.webdav.directedit.server.DavConstants.XML_CONTENT_TYPE;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.Blobs;
import org.nuxeo.ecm.core.api.CoreInstance;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentNotFoundException;
import org.nuxeo.ecm.core.api.DocumentSecurityException;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.api.Lock;
import org.nuxeo.ecm.core.api.LockException;
import org.nuxeo.ecm.core.api.NuxeoPrincipal;
import org.nuxeo.ecm.core.api.PropertyException;
import org.nuxeo.ecm.core.api.blobholder.BlobHolder;
import org.nuxeo.ecm.core.api.security.SecurityConstants;
import org.nuxeo.ecm.core.blob.ByteRange;
import org.nuxeo.ecm.core.io.download.DownloadHelper;
import org.nuxeo.ecm.core.io.download.DownloadService;
import org.nuxeo.ecm.core.transientstore.api.MaximumTransientSpaceExceeded;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.services.config.ConfigurationService;
import org.nuxeo.runtime.transaction.TransactionHelper;

import nuxeo.labs.webdav.directedit.server.MultiStatusWriter.DavResourceInfo;

/**
 * A WebDAV-speaking endpoint that exposes exactly one file per document, so Microsoft Office can edit it in place.
 * <p>
 * This is deliberately <b>not</b> a general purpose WebDAV server. It implements only the subset of RFC 4918 that
 * Office exercises, over a URL space that is two levels deep:
 *
 * <pre>
 * /site/officeedit/&lt;docId&gt;/              a synthetic collection, one per document
 * /site/officeedit/&lt;docId&gt;/&lt;fileName&gt;    the document's main blob
 * </pre>
 *
 * Addressing a document by id rather than by repository path is what makes this work for <b>any</b> document, with no
 * requirement that it live under a Workspace, and what makes two documents sharing a file name unambiguous.
 * <p>
 * The single invariant everything else follows from: <b>only the canonical file name maps to the Nuxeo document.
 * Every other name in the collection is scratch</b>, held in a {@link ScratchStore} and never materialised as a
 * document. Consequently this endpoint can never create, rename, trash or delete a document, and {@code DELETE} on
 * the canonical name is refused.
 *
 * @since 2025.1
 */
public class OfficeEditServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LogManager.getLogger(OfficeEditServlet.class);

    /** Ceiling on a single upload. WebDAV has no partial write, so this is also the largest editable document. */
    public static final String MAX_UPLOAD_MB_PROP = "nuxeo.labs.webdavDirectEdit.maxUploadSizeMB";

    public static final int DEFAULT_MAX_UPLOAD_MB = 512;

    /**
     * Raised the first time a client takes the transacted save path, once per server lifetime.
     * <p>
     * Neither Word for Mac nor Word for Windows took it through the {@code ms-word:} protocol handler when this was
     * last measured, yet the path is very likely reachable another way — see {@link #noteTransactedSave}. Rather than
     * keep guessing, the code reports it: if any Office version, anywhere, ever uses it, one line appears in the log.
     */
    protected static final AtomicBoolean TRANSACTED_SAVE_SEEN = new AtomicBoolean();

    protected final transient ScratchStore scratchStore = new ScratchStore();

    protected final transient OfficeEditSessionStore sessionStore = new OfficeEditSessionStore();

    /**
     * {@code HttpServlet#service} answers {@code 501 Not Implemented} for anything outside the seven methods it knows,
     * which is every WebDAV method. Dispatching has to be taken over entirely.
     */
    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        String method = request.getMethod();
        /*
         * One line per request, so a real Office client can be observed verb by verb. Nothing else records this: the
         * platform enables no access log, and the handlers below only log failures. This is the only way to learn
         * what a given Office version actually sends.
         */
        if (log.isDebugEnabled()) {
            log.debug("{} {}", method, safePath(request));
        }
        try {
            switch (method) {
                case OPTIONS -> doDavOptions(response);
                case PROPFIND -> doPropfind(request, response);
                case PROPPATCH -> doProppatch(request, response);
                case GET, HEAD -> doGetOrHead(request, response, HEAD.equals(method));
                case PUT -> doPut(request, response);
                case DELETE -> doDelete(request, response);
                case MOVE -> doMoveOrCopy(request, response, true);
                case COPY -> doMoveOrCopy(request, response, false);
                case LOCK -> doLock(request, response);
                case UNLOCK -> doUnlock(request, response);
                case MKCOL -> throw new DavException(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
                default -> {
                    // Logged so that an unexpected Office version shows up in the log rather than as silent breakage.
                    log.warn("Unsupported WebDAV method {} on {}", method, safePath(request));
                    throw new DavException(HttpServletResponse.SC_NOT_IMPLEMENTED);
                }
            }
        } catch (DavException e) {
            if (log.isDebugEnabled()) {
                log.debug("{} {} -> {} ({})", method, safePath(request), e.getStatus(), e.getMessage());
            }
            sendStatus(response, e.getStatus());
        } catch (DocumentSecurityException e) {
            log.debug("Access denied on {} {}", method, safePath(request), e);
            sendStatus(response, HttpServletResponse.SC_FORBIDDEN);
        } catch (MaximumTransientSpaceExceeded e) {
            log.error("Scratch storage is full, refusing {} {}", method, safePath(request), e);
            sendStatus(response, SC_INSUFFICIENT_STORAGE);
        } catch (RuntimeException e) {
            /*
             * Nothing must escape to the container: it would answer with an HTML error page, which a WebDAV client
             * cannot read and which may disclose more than it should.
             */
            log.error("Unexpected failure on {} {}", method, safePath(request), e);
            sendStatus(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    /* ==================== OPTIONS ==================== */

    /**
     * Answers without touching the repository. Word sends {@code OPTIONS} before it knows whether the resource exists,
     * and uses {@code DAV} and {@code MS-Author-Via} to decide between editing in place and downloading a read-only
     * copy. A 404 here loses the feature entirely.
     */
    protected void doDavOptions(HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setHeader(HEADER_DAV, DAV_COMPLIANCE);
        response.setHeader(HEADER_MS_AUTHOR_VIA, MS_AUTHOR_VIA_DAV);
        response.setHeader(HEADER_ALLOW, DavConstants.ALLOW);
        response.setHeader(HEADER_ACCEPT_RANGES, BYTES);
        response.setContentLength(0);
    }

    /* ==================== PROPFIND ==================== */

    protected void doPropfind(HttpServletRequest request, HttpServletResponse response) throws IOException {
        DavTarget target = target(request);
        boolean deep = !DEPTH_0.equals(StringUtils.defaultIfBlank(request.getHeader(HEADER_DEPTH), "1"));

        response.setStatus(SC_MULTI_STATUS);
        response.setContentType(XML_CONTENT_TYPE);

        if (target.isRoot()) {
            // The root is an opaque, empty collection: it is never enumerated, so no document is ever listed here.
            try (var writer = new MultiStatusWriter(response.getOutputStream())) {
                writer.startMultiStatus();
                writer.writeResource(rootInfo(request));
                writer.endMultiStatus();
            }
            return;
        }

        CoreSession session = session();
        DocumentModel doc = resolveDocument(session, target);
        Blob blob = requireBlob(doc);

        try (var writer = new MultiStatusWriter(response.getOutputStream())) {
            writer.startMultiStatus();
            if (target.isCollection()) {
                writer.writeResource(collectionInfo(request, target, doc));
                if (deep) {
                    writer.writeResource(fileInfo(request, new DavTarget(target.token(), blob.getFilename()), doc,
                            blob, session));
                }
            } else if (isCanonical(blob, target.name())) {
                writer.writeResource(fileInfo(request, target, doc, blob, session));
            } else {
                Blob scratch = scratchBlob(target, blob);
                if (scratch == null) {
                    throw new DavException(HttpServletResponse.SC_NOT_FOUND, "no such scratch entry");
                }
                writer.writeResource(scratchInfo(request, target, scratch));
            }
            writer.endMultiStatus();
        }
    }

    /* ==================== PROPPATCH ==================== */

    /**
     * Accepts every property and stores none.
     * <p>
     * Office sets four {@code Win32*} properties on each save and treats a failure as fatal, so refusing them breaks
     * saving outright. None of them maps to anything in Nuxeo.
     */
    protected void doProppatch(HttpServletRequest request, HttpServletResponse response) throws IOException {
        DavTarget target = target(request);
        if (target.isRoot()) {
            throw new DavException(HttpServletResponse.SC_FORBIDDEN, "cannot set properties on the root");
        }
        /*
         * Resolve before touching the body. This verb answers 207 unconditionally, which makes it the one place where
         * a caller could otherwise reach the parser without holding a valid capability, and it is also the verb that
         * reads an unbounded request body.
         */
        resolveDocument(session(), target);
        var properties = DavXmlParser.parsePropPatch(request.getInputStream());

        response.setStatus(SC_MULTI_STATUS);
        response.setContentType(XML_CONTENT_TYPE);
        try (var writer = new MultiStatusWriter(response.getOutputStream())) {
            writer.startMultiStatus();
            writer.writePropPatchResult(href(request, target), properties);
            writer.endMultiStatus();
        }
    }

    /* ==================== GET / HEAD ==================== */

    protected void doGetOrHead(HttpServletRequest request, HttpServletResponse response, boolean headOnly)
            throws IOException {
        DavTarget target = target(request);
        if (!target.isFile()) {
            // A collection has no representation here; there is nothing useful to browse.
            throw new DavException(HttpServletResponse.SC_FORBIDDEN, "collections are not browsable");
        }
        CoreSession session = session();
        DocumentModel doc = resolveDocument(session, target);
        Blob mainBlob = requireBlob(doc);

        Blob blob;
        if (isCanonical(mainBlob, target.name())) {
            blob = mainBlob;
            response.setHeader(HEADER_ETAG, "\"" + StringUtils.defaultString(doc.getChangeToken()) + "\"");
            response.setHeader(HEADER_LAST_MODIFIED, MultiStatusWriter.formatHttpDate(timeOf(doc, "dc:modified")));
        } else {
            blob = scratchBlob(target, mainBlob);
            if (blob == null) {
                throw new DavException(HttpServletResponse.SC_NOT_FOUND, "no such scratch entry");
            }
        }
        streamBlob(request, response, blob, headOnly);
    }

    /**
     * Streams a blob, honouring {@code Range}.
     * <p>
     * {@code DownloadService#downloadBlob} is deliberately not used: it redirects to the blob provider's own URI when
     * one exists, for instance a pre-signed S3 link, which would take Office out of its authenticated WebDAV session.
     */
    protected void streamBlob(HttpServletRequest request, HttpServletResponse response, Blob blob, boolean headOnly)
            throws IOException {
        long length = blob.getLength();
        String rangeHeader = request.getHeader(HEADER_RANGE);
        ByteRange byteRange = StringUtils.isBlank(rangeHeader) || length < 0 ? null
                : DownloadHelper.parseRange(rangeHeader, length);

        response.setHeader(HEADER_ACCEPT_RANGES, BYTES);
        response.setContentType(mimeTypeOf(blob));
        if (byteRange == null) {
            response.setStatus(HttpServletResponse.SC_OK);
            if (length >= 0) {
                response.setContentLengthLong(length);
            }
        } else {
            response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
            response.setHeader(HEADER_CONTENT_RANGE,
                    BYTES + " " + byteRange.getStart() + "-" + byteRange.getEnd() + "/" + length);
            response.setContentLengthLong(byteRange.getLength());
        }
        if (headOnly) {
            return;
        }
        // A large transfer must not hold the request's transaction open past its timeout.
        commitAndReopenTransaction();
        Framework.getService(DownloadService.class).transferBlobWithByteRange(blob, byteRange, () -> {
            try {
                return response.getOutputStream();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        response.flushBuffer();
    }

    /* ==================== PUT ==================== */

    protected void doPut(HttpServletRequest request, HttpServletResponse response) throws IOException {
        DavTarget target = target(request);
        if (!target.isFile()) {
            throw new DavException(HttpServletResponse.SC_METHOD_NOT_ALLOWED, "cannot PUT a collection");
        }
        CoreSession session = session();
        DocumentModel doc = resolveDocument(session, target);
        Blob mainBlob = requireBlob(doc);
        boolean canonical = isCanonical(mainBlob, target.name());

        /*
         * Authorise before reading a single byte. A scratch file exists only to become the document, so writing one
         * requires the same permission as writing the document itself; and a request that is going to be refused must
         * not first be spooled to disk.
         */
        checkWritable(session, doc);

        boolean existed = canonical || scratchStore.exists(target.token(), target.name());
        Blob uploaded = readBody(request);
        if (canonical) {
            commitToDocument(session, doc, uploaded, target.name());
        } else {
            noteTransactedSave("PUT", target.name());
            uploaded.setFilename(target.name());
            scratchStore.putBlob(target.token(), target.name(), uploaded);
        }
        sendStatus(response, existed ? HttpServletResponse.SC_NO_CONTENT : HttpServletResponse.SC_CREATED);
    }

    /* ==================== DELETE ==================== */

    protected void doDelete(HttpServletRequest request, HttpServletResponse response) {
        DavTarget target = target(request);
        if (!target.isFile()) {
            throw new DavException(HttpServletResponse.SC_FORBIDDEN, "collections cannot be deleted");
        }
        CoreSession session = session();
        DocumentModel doc = resolveDocument(session, target);
        Blob mainBlob = requireBlob(doc);

        if (isCanonical(mainBlob, target.name())) {
            // Refusing this is the point: Office must never be able to remove a Nuxeo document. Logged at warn
            // because it means a client asked for something we deliberately do not do.
            log.warn("Refused DELETE of the main file of document {}", doc.getId());
            throw new DavException(HttpServletResponse.SC_FORBIDDEN, "the document's file cannot be deleted");
        }
        scratchStore.remove(target.token(), target.name());
        sendStatus(response, HttpServletResponse.SC_NO_CONTENT);
    }

    /* ==================== MOVE / COPY ==================== */

    /**
     * Implements the middle of Office's transacted save.
     * <p>
     * Moving a scratch entry onto the canonical name is what commits the new content to the document. Moving the
     * canonical name aside leaves the document untouched and records a shadow, so the original stays readable until
     * Office deletes it.
     * <p>
     * <b>No client has yet been observed taking this path</b> — see "What a real Office client actually does" in
     * {@code AGENTS.md}. It is kept because unobserved is not unreachable, and {@link #noteTransactedSave} reports it
     * if that ever changes. Do not treat it as validated, and do not delete it as dead.
     */
    protected void doMoveOrCopy(HttpServletRequest request, HttpServletResponse response, boolean move) {
        DavTarget source = target(request);
        DavTarget destination = destination(request);
        noteTransactedSave(move ? "MOVE" : "COPY", source.name());
        if (!source.isFile() || !destination.isFile()) {
            throw new DavException(HttpServletResponse.SC_FORBIDDEN, "only files can be moved or copied");
        }
        if (!source.token().equals(destination.token())) {
            // Each collection holds a single document; moving between them would mean moving content across documents.
            throw new DavException(HttpServletResponse.SC_FORBIDDEN, "cannot move across collections");
        }
        CoreSession session = session();
        DocumentModel doc = resolveDocument(session, source);
        Blob mainBlob = requireBlob(doc);
        String sessionToken = source.token();

        boolean sourceIsCanonical = isCanonical(mainBlob, source.name());
        boolean destinationIsCanonical = isCanonical(mainBlob, destination.name());
        boolean destinationExisted = destinationIsCanonical
                || scratchStore.exists(sessionToken, destination.name());

        if (destinationExisted && "F".equalsIgnoreCase(request.getHeader(HEADER_OVERWRITE))) {
            throw new DavException(HttpServletResponse.SC_PRECONDITION_FAILED, "destination exists");
        }

        if (sourceIsCanonical && destinationIsCanonical) {
            throw new DavException(HttpServletResponse.SC_FORBIDDEN, "no-op on the document's file");
        } else if (sourceIsCanonical) {
            if (move) {
                // Step 2 of the save dance. No bytes are copied and the document is not modified.
                scratchStore.putShadow(sessionToken, destination.name());
            } else {
                scratchStore.putBlob(sessionToken, destination.name(), mainBlob);
            }
        } else if (destinationIsCanonical) {
            // Step 3 of the save dance: this is the commit.
            Blob scratch = scratchBlob(source, mainBlob);
            if (scratch == null) {
                throw new DavException(HttpServletResponse.SC_NOT_FOUND, "no such scratch entry");
            }
            commitToDocument(session, doc, scratch, destination.name());
            if (move) {
                scratchStore.remove(sessionToken, source.name());
            }
        } else {
            if (!scratchStore.exists(sessionToken, source.name())) {
                throw new DavException(HttpServletResponse.SC_NOT_FOUND, "no such scratch entry");
            }
            if (move) {
                scratchStore.move(sessionToken, source.name(), destination.name());
            } else {
                Blob scratch = scratchBlob(source, mainBlob);
                if (scratch != null) {
                    scratchStore.putBlob(sessionToken, destination.name(), scratch);
                }
            }
        }
        sendStatus(response,
                destinationExisted ? HttpServletResponse.SC_NO_CONTENT : HttpServletResponse.SC_CREATED);
    }

    /* ==================== LOCK / UNLOCK ==================== */

    /**
     * Locks the document. Office will not allow editing without this: an unlockable file is opened read-only.
     */
    protected void doLock(HttpServletRequest request, HttpServletResponse response) throws IOException {
        DavTarget target = target(request);
        if (target.isRoot()) {
            throw new DavException(HttpServletResponse.SC_FORBIDDEN, "the root cannot be locked");
        }
        CoreSession session = session();
        DocumentModel doc = resolveDocument(session, target);
        requireBlob(doc);
        String userName = session.getPrincipal().getName();

        if (!session.hasPermission(doc.getRef(), SecurityConstants.WRITE_PROPERTIES)) {
            throw new DavException(HttpServletResponse.SC_FORBIDDEN, "no write permission");
        }
        Lock existing = session.getLockInfo(doc.getRef());
        if (existing != null && !userName.equals(existing.getOwner())) {
            throw new DavException(SC_LOCKED, "locked by " + existing.getOwner());
        }
        if (existing == null) {
            try {
                session.setLock(doc.getRef());
                session.save();
            } catch (LockException e) {
                // Another request took the lock between getLockInfo and setLock. Office understands 423, not 500.
                log.debug("Concurrent lock on document {}", doc.getId(), e);
                throw new DavException(SC_LOCKED, "locked concurrently");
            }
        }
        String token = lockToken(doc.getId(), userName);

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(XML_CONTENT_TYPE);
        response.setHeader(HEADER_LOCK_TOKEN, "<" + token + ">");
        try (var writer = new MultiStatusWriter(response.getOutputStream())) {
            writer.writeLockProp(userName, token);
        }
    }

    protected void doUnlock(HttpServletRequest request, HttpServletResponse response) {
        DavTarget target = target(request);
        if (target.isRoot()) {
            throw new DavException(HttpServletResponse.SC_FORBIDDEN, "the root cannot be unlocked");
        }
        CoreSession session = session();
        DocumentModel doc = resolveDocument(session, target);
        String userName = session.getPrincipal().getName();

        Lock existing = session.getLockInfo(doc.getRef());
        if (existing != null) {
            if (!userName.equals(existing.getOwner())) {
                throw new DavException(SC_LOCKED, "locked by " + existing.getOwner());
            }
            session.removeLock(doc.getRef());
            session.save();
        }
        sendStatus(response, HttpServletResponse.SC_NO_CONTENT);
    }

    /**
     * Derives a stable lock token from the document and its owner.
     * <p>
     * Deriving rather than storing means the token survives a refresh, a restart and any cluster node, with no state
     * to keep. It grants nothing on its own: the caller still has to authenticate as the owner.
     */
    protected static String lockToken(String docId, String owner) {
        return "urn:uuid:"
                + UUID.nameUUIDFromBytes((docId + "|" + owner).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Reports, once per server lifetime, that a client used the transacted save rather than a plain {@code PUT}.
     * <p>
     * This exists to settle a question measurement has not: through the {@code ms-word:} protocol handler, both Word
     * for Mac and Word for Windows send a direct {@code PUT}. But the address can also be opened with File then Open,
     * or from a mapped drive, and on Windows that route goes through the WebClient service, which turns Word's
     * ordinary filesystem save into {@code MOVE}s. Excel, PowerPoint and older Office versions are untested too.
     * <p>
     * So the path is unobserved, not unreachable. One INFO line in a customer's log answers definitively what no
     * amount of local testing could.
     */
    protected static void noteTransactedSave(String verb, String name) {
        if (TRANSACTED_SAVE_SEEN.compareAndSet(false, true)) {
            log.info("Office edit: a client used the transacted save path ({} on {}). This is the branch neither Word"
                    + " for Mac nor Word for Windows took through the protocol handler when last measured; see"
                    + " AGENTS.md.", verb, name);
        }
    }

    /* ==================== Repository access ==================== */

    protected CoreSession session() {
        CoreSession session = CoreInstance.getCoreSession(null);
        NuxeoPrincipal principal = session.getPrincipal();
        if (principal == null || principal.isAnonymous()) {
            throw new DavException(HttpServletResponse.SC_UNAUTHORIZED, "a real user is required");
        }
        return session;
    }

    /**
     * Resolves the edit session in the URL to its document.
     * <p>
     * The token grants access to exactly one document, so there is no separate scope check to perform: the capability
     * <i>is</i> the address. The only extra check is that the caller is the user the session was issued for, which
     * matters when someone authenticated by other means presents somebody else's URL.
     */
    protected DocumentModel resolveDocument(CoreSession session, DavTarget target) {
        var editSession = sessionStore.resolve(target.token())
                                      .orElseThrow(() -> new DavException(HttpServletResponse.SC_NOT_FOUND,
                                              "unknown or expired edit session"));
        if (!session.getPrincipal().getName().equals(editSession.userName())) {
            log.warn("Edit session {} belongs to {} but was used by {}",
                    () -> OfficeEditSessionStore.mask(target.token()), editSession::userName,
                    () -> session.getPrincipal().getName());
            throw new DavException(HttpServletResponse.SC_FORBIDDEN, "session belongs to another user");
        }
        var ref = new IdRef(editSession.docId());
        try {
            if (!session.exists(ref)) {
                throw new DavException(HttpServletResponse.SC_NOT_FOUND, "no such document");
            }
            return session.getDocument(ref);
        } catch (DocumentNotFoundException e) {
            throw new DavException(HttpServletResponse.SC_NOT_FOUND, "no such document");
        }
    }

    protected static Blob requireBlob(DocumentModel doc) {
        BlobHolder blobHolder = doc.getAdapter(BlobHolder.class);
        Blob blob = blobHolder == null ? null : blobHolder.getBlob();
        if (blob == null || StringUtils.isBlank(blob.getFilename())) {
            throw new DavException(HttpServletResponse.SC_NOT_FOUND, "document has no main blob");
        }
        return blob;
    }

    protected static boolean isCanonical(Blob mainBlob, String name) {
        return name != null && name.equals(mainBlob.getFilename());
    }

    /**
     * Resolves a non-canonical name to its content: either a stored scratch blob, or the document's own blob when the
     * entry is a shadow left behind by the save dance.
     */
    protected Blob scratchBlob(DavTarget target, Blob mainBlob) {
        if (!scratchStore.exists(target.token(), target.name())) {
            return null;
        }
        if (scratchStore.isShadow(target.token(), target.name())) {
            return mainBlob;
        }
        return scratchStore.getBlob(target.token(), target.name());
    }

    /**
     * Refuses anything that must not be written: a lock held by somebody else, a missing permission, or a document
     * that has stopped being editable since its address was issued.
     * <p>
     * The lock is checked before the permission on purpose. {@code LockSecurityPolicy} denies write to anyone who is
     * not the lock owner, so checking the permission first would report 403 for what is really a 423, and Office
     * distinguishes the two: it offers to retry on a lock and gives up on a permission error.
     */
    protected void checkWritable(CoreSession session, DocumentModel doc) {
        String userName = session.getPrincipal().getName();
        Lock lock = session.getLockInfo(doc.getRef());
        if (lock != null && !userName.equals(lock.getOwner())) {
            throw new DavException(SC_LOCKED, "locked by " + lock.getOwner());
        }
        if (!session.hasPermission(doc.getRef(), SecurityConstants.WRITE_PROPERTIES)) {
            throw new DavException(HttpServletResponse.SC_FORBIDDEN, "no write permission");
        }
        /*
         * The service refuses these when it issues the address, but a document can be trashed, or turned into a
         * version or a proxy, while Office still holds an address for it. Re-check at the moment of writing.
         */
        if (doc.isVersion() || doc.isProxy() || doc.isTrashed()) {
            throw new DavException(HttpServletResponse.SC_FORBIDDEN, "document is no longer editable");
        }
    }

    /**
     * Writes a blob onto the document's main blob, which is the only mutation this endpoint ever performs.
     */
    protected void commitToDocument(CoreSession session, DocumentModel doc, Blob blob, String fileName) {
        // Deliberately re-checked here as well: doMoveOrCopy reaches this method by another route.
        checkWritable(session, doc);
        Blob current = doc.getAdapter(BlobHolder.class).getBlob();
        blob.setFilename(fileName);
        // The file type cannot change during an in-place edit, so the existing mime type is the best answer, and a
        // better one than the "application/octet-stream" Office tends to send.
        String mimeType = current == null ? null : current.getMimeType();
        if (StringUtils.isBlank(mimeType) || UNKNOWN_MIME_TYPE.equals(mimeType)) {
            mimeType = blob.getMimeType();
        }
        blob.setMimeType(StringUtils.isBlank(mimeType) ? null : mimeType);

        doc.getAdapter(BlobHolder.class).setBlob(blob);
        doc.putContextData(CoreSession.USER_CHANGE, Boolean.TRUE);
        session.saveDocument(doc);
        session.save();
        /*
         * At INFO, not DEBUG: this is the one event that matters operationally, it happens once per save, and without
         * it a whole editing session leaves no trace at all in the server log.
         */
        log.info("Office edit: updated main blob of document {} ({}, {} bytes)", doc.getId(), fileName,
                blob.getLength());
    }

    /**
     * Reads the request body into a blob, refusing anything past the configured ceiling.
     * <p>
     * {@code Content-Length} is checked first as a courtesy, but it is only a claim: chunked requests carry none and
     * a hostile one can lie. The stream itself is therefore bounded, which is what actually protects the disk.
     */
    protected static Blob readBody(HttpServletRequest request) throws IOException {
        long maxBytes = maxUploadBytes();
        if (request.getContentLengthLong() > maxBytes) {
            throw new DavException(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                    "declared body exceeds " + maxBytes + " bytes");
        }
        try (InputStream in = new BoundedInputStream(request.getInputStream(), maxBytes)) {
            // Spools to a temporary file rather than to memory, so a large document never sits on the heap.
            return Blobs.createBlob(in);
        }
    }

    protected static long maxUploadBytes() {
        ConfigurationService configurationService = Framework.getService(ConfigurationService.class);
        int megabytes = configurationService == null ? DEFAULT_MAX_UPLOAD_MB
                : configurationService.getInteger(MAX_UPLOAD_MB_PROP, DEFAULT_MAX_UPLOAD_MB);
        return (megabytes <= 0 ? DEFAULT_MAX_UPLOAD_MB : megabytes) * 1024L * 1024L;
    }

    /**
     * Fails the read once the ceiling is passed, rather than letting an unbounded body reach the disk.
     */
    protected static class BoundedInputStream extends FilterInputStream {

        protected final long max;

        protected long count;

        protected BoundedInputStream(InputStream in, long max) {
            super(in);
            this.max = max;
        }

        @Override
        public int read() throws IOException {
            int read = super.read();
            if (read >= 0) {
                check(1);
            }
            return read;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                check(read);
            }
            return read;
        }

        protected void check(long increment) {
            count += increment;
            if (count > max) {
                throw new DavException(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                        "body exceeds " + max + " bytes");
            }
        }
    }

    /* ==================== PROPFIND value objects ==================== */

    protected DavResourceInfo rootInfo(HttpServletRequest request) {
        long now = System.currentTimeMillis();
        return new DavResourceInfo(href(request, new DavTarget(null, null)), true, "officeedit", 0, null, now, now,
                null, null, null);
    }

    protected DavResourceInfo collectionInfo(HttpServletRequest request, DavTarget target, DocumentModel doc) {
        return new DavResourceInfo(href(request, new DavTarget(target.token(), null)), true, doc.getName(), 0, null,
                timeOf(doc, "dc:modified"), timeOf(doc, "dc:created"), null, null, null);
    }

    protected DavResourceInfo fileInfo(HttpServletRequest request, DavTarget target, DocumentModel doc, Blob blob,
            CoreSession session) {
        Lock lock = session.getLockInfo(doc.getRef());
        String token = lock == null ? null : lockToken(doc.getId(), lock.getOwner());
        return new DavResourceInfo(href(request, target), false, blob.getFilename(), blob.getLength(),
                mimeTypeOf(blob), timeOf(doc, "dc:modified"), timeOf(doc, "dc:created"), doc.getChangeToken(),
                lock == null ? null : lock.getOwner(), token);
    }

    protected DavResourceInfo scratchInfo(HttpServletRequest request, DavTarget target, Blob blob) {
        long now = System.currentTimeMillis();
        return new DavResourceInfo(href(request, target), false, target.name(), blob.getLength(), mimeTypeOf(blob),
                now, now, null, null, null);
    }

    protected static String mimeTypeOf(Blob blob) {
        String mimeType = blob.getMimeType();
        return StringUtils.isBlank(mimeType) || UNKNOWN_MIME_TYPE.equals(mimeType) ? DEFAULT_MIME_TYPE : mimeType;
    }

    /**
     * Reads a date property, falling back to now. A document type without the {@code dublincore} schema must not turn
     * a {@code PROPFIND} into a 500.
     */
    protected static long timeOf(DocumentModel doc, String xpath) {
        try {
            Object value = doc.getPropertyValue(xpath);
            return value instanceof Calendar calendar ? calendar.getTimeInMillis() : System.currentTimeMillis();
        } catch (PropertyException e) {
            return System.currentTimeMillis();
        }
    }

    /* ==================== Request plumbing ==================== */

    protected DavTarget target(HttpServletRequest request) {
        DavTarget target = DavTarget.parse(DavTarget.rawPathOf(request));
        if (target == null) {
            throw new DavException(HttpServletResponse.SC_NOT_FOUND, "malformed path");
        }
        return target;
    }

    protected DavTarget destination(HttpServletRequest request) {
        String destination = request.getHeader(HEADER_DESTINATION);
        if (StringUtils.isBlank(destination)) {
            throw new DavException(HttpServletResponse.SC_BAD_REQUEST, "missing Destination");
        }
        String path;
        try {
            path = URI.create(destination.trim()).getRawPath();
        } catch (IllegalArgumentException e) {
            throw new DavException(HttpServletResponse.SC_BAD_REQUEST, "malformed Destination");
        }
        String prefix = StringUtils.defaultString(request.getContextPath())
                + StringUtils.defaultString(request.getServletPath());
        if (path == null || !path.startsWith(prefix)) {
            // RFC 4918: the destination lies outside this server's namespace.
            throw new DavException(HttpServletResponse.SC_BAD_GATEWAY, "Destination outside this endpoint");
        }
        DavTarget target = DavTarget.parse(path.substring(prefix.length()));
        if (target == null) {
            throw new DavException(HttpServletResponse.SC_BAD_REQUEST, "malformed Destination");
        }
        return target;
    }

    protected String href(HttpServletRequest request, DavTarget target) {
        String prefix = StringUtils.defaultString(request.getContextPath())
                + StringUtils.defaultString(request.getServletPath());
        if (target.isRoot()) {
            return prefix + "/";
        }
        String base = prefix + "/" + DavTarget.encode(target.token());
        return target.isCollection() ? base + "/" : base + "/" + DavTarget.encode(target.name());
    }

    /**
     * Renders a request path for logging with the edit session token masked.
     * <p>
     * The token is a live credential. It travels in the URL because Microsoft Office has no other way to carry one,
     * but nothing this plugin writes to a log may contain it.
     */
    protected static String safePath(HttpServletRequest request) {
        DavTarget target = DavTarget.parse(DavTarget.rawPathOf(request));
        if (target == null || target.isRoot()) {
            return "/";
        }
        return "/" + OfficeEditSessionStore.mask(target.token())
                + (target.isCollection() ? "/" : "/" + target.name());
    }

    /**
     * Sets a bare status with no body, rather than {@code sendError}, which would hand the container's HTML error page
     * to a WebDAV client.
     */
    protected static void sendStatus(HttpServletResponse response, int status) {
        if (response.isCommitted()) {
            return;
        }
        response.reset();
        response.setStatus(status);
        response.setContentLength(0);
    }

    protected static void commitAndReopenTransaction() {
        if (TransactionHelper.isTransactionActiveOrMarkedRollback()) {
            TransactionHelper.commitOrRollbackTransaction();
            TransactionHelper.startTransaction();
        }
    }

    /** Unwinds a request with a given HTTP status. */
    protected static class DavException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        protected final int status;

        public DavException(int status) {
            this(status, null);
        }

        public DavException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int getStatus() {
            return status;
        }
    }
}
