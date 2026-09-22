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

import java.util.Arrays;
import java.util.Optional;

/**
 * The three Microsoft Office desktop applications this plugin can hand a document over to.
 * <p>
 * Each application registers an OS-level URI scheme. The URI format is {@code <scheme>ofe|u|<url>} where {@code ofe}
 * means "open for edit" and {@code |u|} introduces the URL. Nothing in the Nuxeo platform produces these URIs, so they
 * are built here.
 *
 * @since 2025.1
 */
public enum OfficeApp {

    WORD("word", "ms-word:"),

    EXCEL("excel", "ms-excel:"),

    POWERPOINT("powerpoint", "ms-powerpoint:");

    /** Lower-case identifier, also used as the configuration property infix and as the i18n key suffix. */
    protected final String id;

    /** The OS-registered URI scheme, colon included. */
    protected final String uriScheme;

    OfficeApp(String id, String uriScheme) {
        this.id = id;
        this.uriScheme = uriScheme;
    }

    public String getId() {
        return id;
    }

    public String getUriScheme() {
        return uriScheme;
    }

    /**
     * Builds the "open for edit" protocol handler URI for the given URL.
     * <p>
     * The pipe characters are deliberately left unencoded: this is the format Office expects, and browsers percent-encode
     * them on navigation anyway, which Office also accepts.
     */
    public String buildOpenForEditUri(String url) {
        return uriScheme + "ofe|u|" + url;
    }

    /**
     * Returns the application whose {@link #getId() id} matches, ignoring case.
     */
    public static Optional<OfficeApp> byId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(app -> app.id.equalsIgnoreCase(id.trim())).findFirst();
    }
}
