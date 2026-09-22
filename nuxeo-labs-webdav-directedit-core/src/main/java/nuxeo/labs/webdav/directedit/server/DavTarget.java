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

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;

/**
 * A parsed endpoint path.
 * <p>
 * The URL space is deliberately tiny and exactly two levels deep:
 *
 * <pre>
 * /site/officeedit/                      the root, an empty collection
 * /site/officeedit/&lt;token&gt;/              an edit session's virtual collection
 * /site/officeedit/&lt;token&gt;/&lt;name&gt;        a file inside it
 * </pre>
 *
 * The first segment is an opaque edit session token, not a document id. It resolves, through
 * {@link OfficeEditSessionStore}, to exactly one document and one user. That is what frees this endpoint from the
 * constraints of a path-based WebDAV tree, and it is also the credential that spares Microsoft Office a credentials
 * prompt it cannot satisfy under single sign-on.
 *
 * @param token the edit session token, {@code null} for the root
 * @param name the file name inside the collection, {@code null} for a collection
 * @since 2025.1
 */
public record DavTarget(String token, String name) {

    /** A token is a UUID; anything longer is not one, and is rejected before any lookup. */
    private static final int TOKEN_MAX_LENGTH = 64;

    /** Longest file name any common filesystem accepts, and far more than Office produces. */
    private static final int NAME_MAX_LENGTH = 255;

    public boolean isRoot() {
        return token == null;
    }

    public boolean isCollection() {
        return name == null;
    }

    public boolean isFile() {
        return name != null;
    }

    /**
     * Returns the request path below the servlet mount point, still percent-encoded.
     * <p>
     * {@code getPathInfo()} cannot be used: servlet containers strip everything after a semicolon in a path segment,
     * reading it as a matrix parameter, and file names do contain semicolons.
     */
    public static String rawPathOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return "";
        }
        String prefix = StringUtils.defaultString(request.getContextPath())
                + StringUtils.defaultString(request.getServletPath());
        return uri.length() > prefix.length() ? uri.substring(prefix.length()) : "";
    }

    /**
     * Returns the edit session token of a request, or {@code null}.
     * <p>
     * Used by the authentication plugin, which runs long before the servlet and so cannot go through
     * {@link #parse(String)}.
     */
    public static String firstSegmentOf(HttpServletRequest request) {
        DavTarget target = parse(rawPathOf(request));
        return target == null ? null : target.token();
    }

    /**
     * Parses the part of the request URI below the servlet mount point.
     *
     * @return {@code null} when the path has more than two segments, or carries an implausible token
     */
    public static DavTarget parse(String rawPath) {
        if (StringUtils.isEmpty(rawPath) || "/".equals(rawPath)) {
            return new DavTarget(null, null);
        }
        String trimmed = rawPath.startsWith("/") ? rawPath.substring(1) : rawPath;
        // A trailing slash marks a collection; it carries no segment of its own.
        boolean trailingSlash = trimmed.endsWith("/");
        if (trailingSlash) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isEmpty()) {
            return new DavTarget(null, null);
        }

        String[] segments = trimmed.split("/", -1);
        if (segments.length > 2) {
            return null;
        }
        String token = decode(segments[0]);
        if (StringUtils.isBlank(token) || token.length() > TOKEN_MAX_LENGTH || token.indexOf('/') >= 0) {
            return null;
        }
        if (segments.length == 1) {
            return new DavTarget(token, null);
        }
        String name = decode(segments[1]);
        if (StringUtils.isBlank(name)) {
            return new DavTarget(token, null);
        }
        /*
         * The name is attacker-controlled after percent-decoding, and it ends up in two places that must not receive
         * arbitrary bytes: a log statement, where a CR or LF would let a caller forge log lines, and a key in the
         * scratch store. Screen it once here rather than at each use.
         */
        if (name.length() > NAME_MAX_LENGTH || name.indexOf('/') >= 0 || containsControlCharacter(name)) {
            return null;
        }
        return new DavTarget(token, name);
    }

    protected static boolean containsControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Percent-decodes a single path segment.
     * <p>
     * {@code URLDecoder} cannot be used: it turns {@code +} into a space, which is correct for a query string and
     * wrong for a path segment, and file names do contain plus signs.
     */
    public static String decode(String segment) {
        if (segment == null || segment.indexOf('%') < 0) {
            return segment;
        }
        var bytes = new ByteArrayOutputStream(segment.length());
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c == '%' && i + 2 < segment.length()) {
                int high = Character.digit(segment.charAt(i + 1), 16);
                int low = Character.digit(segment.charAt(i + 2), 16);
                if (high >= 0 && low >= 0) {
                    bytes.write((high << 4) + low);
                    i += 2;
                    continue;
                }
            }
            bytes.writeBytes(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    /**
     * Percent-encodes a single path segment, for use in an {@code href}.
     * <p>
     * The multi-argument {@link URI} constructor quotes everything that is not legal in a path, and leaves the rest
     * alone. A slash inside the segment would be encoded, which is what we want.
     */
    public static String encode(String segment) {
        if (StringUtils.isEmpty(segment)) {
            return "";
        }
        try {
            return new URI(null, null, segment, null).toASCIIString().replace("/", "%2F");
        } catch (URISyntaxException e) {
            // Cannot happen for a plain string, but never let a file name break the response.
            return segment;
        }
    }
}
