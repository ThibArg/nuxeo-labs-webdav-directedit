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
 * Everything the Web UI needs to hand a document over to an Office desktop application, or to explain why it cannot.
 *
 * @param available whether the document can be opened
 * @param app the target Office application, {@code null} when not {@link #available}
 * @param fileName the blob file name, {@code null} when there is no blob
 * @param davPath {@code <docId>/<fileName>}, unencoded, {@code null} when not {@link #available}
 * @param davUrl the absolute WebDAV URL, {@code null} when not {@link #available}
 * @param protocolUrl the {@code ms-word:ofe|u|...} URI, {@code null} when not {@link #available}
 * @param secure whether {@link #davUrl} uses HTTPS; Microsoft Office on Windows refuses to send credentials over plain
 *            HTTP by default
 * @param reason why the document cannot be opened, {@code null} when {@link #available}
 * @since 2025.1
 */
public record DirectEditInfo(boolean available, OfficeApp app, String fileName, String davPath, String davUrl,
        String protocolUrl, boolean secure, UnavailabilityReason reason) {

    /**
     * Builds an unavailable result carrying the given reason.
     */
    public static DirectEditInfo unavailable(UnavailabilityReason reason) {
        return unavailable(reason, null);
    }

    /**
     * Builds an unavailable result that still reports the file name, so the Web UI can name the document in its error
     * message.
     */
    public static DirectEditInfo unavailable(UnavailabilityReason reason, String fileName) {
        return new DirectEditInfo(false, null, fileName, null, null, null, false, reason);
    }

    /**
     * Builds an available result.
     */
    public static DirectEditInfo available(OfficeApp app, String fileName, String davPath, String davUrl) {
        return new DirectEditInfo(true, app, fileName, davPath, davUrl, app.buildOpenForEditUri(davUrl),
                davUrl.regionMatches(true, 0, "https://", 0, 8), null);
    }
}
