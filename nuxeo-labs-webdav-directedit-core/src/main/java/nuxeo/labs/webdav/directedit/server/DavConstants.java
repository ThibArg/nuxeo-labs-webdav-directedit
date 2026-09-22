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

/**
 * HTTP and WebDAV constants used by {@link OfficeEditServlet}.
 * <p>
 * Only the subset of RFC 4918 that Microsoft Office exercises is represented here. This endpoint is deliberately not a
 * general purpose WebDAV server.
 *
 * @since 2025.1
 */
public final class DavConstants {

    /** Servlet mount point, below the Nuxeo context path. */
    public static final String MOUNT_PATH = "/site/officeedit/";

    /* ==================== Methods ==================== */

    public static final String OPTIONS = "OPTIONS";

    public static final String HEAD = "HEAD";

    public static final String GET = "GET";

    public static final String PUT = "PUT";

    public static final String DELETE = "DELETE";

    public static final String MKCOL = "MKCOL";

    public static final String COPY = "COPY";

    public static final String MOVE = "MOVE";

    public static final String PROPFIND = "PROPFIND";

    public static final String PROPPATCH = "PROPPATCH";

    public static final String LOCK = "LOCK";

    public static final String UNLOCK = "UNLOCK";

    /** Advertised in the {@code Allow} header and on {@code OPTIONS}. */
    public static final String ALLOW = String.join(", ", OPTIONS, HEAD, GET, PUT, DELETE, COPY, MOVE, PROPFIND,
            PROPPATCH, LOCK, UNLOCK);

    /* ==================== Headers ==================== */

    public static final String HEADER_DAV = "DAV";

    /**
     * Microsoft Word issues {@code OPTIONS} before anything else and uses this header to decide whether the URL can be
     * edited in place or merely downloaded read-only. Nothing in the Nuxeo platform emits it.
     */
    public static final String HEADER_MS_AUTHOR_VIA = "MS-Author-Via";

    public static final String HEADER_ALLOW = "Allow";

    public static final String HEADER_DEPTH = "Depth";

    public static final String HEADER_DESTINATION = "Destination";

    public static final String HEADER_OVERWRITE = "Overwrite";

    public static final String HEADER_RANGE = "Range";

    public static final String HEADER_ACCEPT_RANGES = "Accept-Ranges";

    public static final String HEADER_CONTENT_RANGE = "Content-Range";

    public static final String HEADER_LOCK_TOKEN = "Lock-Token";

    public static final String HEADER_TIMEOUT = "Timeout";

    public static final String HEADER_IF = "If";

    public static final String HEADER_ETAG = "ETag";

    public static final String HEADER_LAST_MODIFIED = "Last-Modified";

    /* ==================== Values ==================== */

    /** Class 2 means locking is supported, which Office requires before it will allow editing. */
    public static final String DAV_COMPLIANCE = "1,2";

    public static final String MS_AUTHOR_VIA_DAV = "DAV";

    public static final String DEPTH_0 = "0";

    public static final String DEPTH_1 = "1";

    public static final String BYTES = "bytes";

    public static final String XML_CONTENT_TYPE = "application/xml; charset=UTF-8";

    public static final String DEFAULT_MIME_TYPE = "application/octet-stream";

    /** {@code jMimeMagic} returns this when it cannot identify a blob. */
    public static final String UNKNOWN_MIME_TYPE = "???";

    /* ==================== Status codes not in HttpServletResponse ==================== */

    public static final int SC_MULTI_STATUS = 207;

    public static final int SC_LOCKED = 423;

    public static final int SC_INSUFFICIENT_STORAGE = 507;

    /* ==================== Locking ==================== */

    /**
     * Lock timeout advertised to the client, in seconds. Office refreshes its lock well within this window, and the
     * real lifetime is governed by the Nuxeo document lock, which never expires on its own.
     */
    public static final long LOCK_TIMEOUT_SECONDS = 3600L;

    private DavConstants() {
        // constants holder
    }
}
