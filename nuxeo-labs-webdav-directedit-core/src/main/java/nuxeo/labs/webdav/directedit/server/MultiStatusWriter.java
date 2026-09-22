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

import java.io.OutputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import org.apache.commons.lang3.StringUtils;
import org.nuxeo.ecm.core.api.NuxeoException;

/**
 * Writes the WebDAV XML this endpoint produces: {@code PROPFIND} and {@code PROPPATCH} multistatus bodies, and the
 * {@code LOCK} property body.
 * <p>
 * The XML is generated directly rather than through a WebDAV library, for three reasons. The bodies are small and
 * entirely fixed in shape; it keeps the plugin free of any third-party WebDAV dependency, all of which are LGPL; and
 * it gives exact control over the details Microsoft Office is known to be sensitive about, in particular the absence
 * of {@code lockroot} and the precise date formats below.
 * <p>
 * Note on imports: {@code javax.xml.stream} is the JDK's own StAX API, part of the {@code java.xml} module. It is not
 * a Jakarta EE package and is unaffected by the {@code javax} to {@code jakarta} migration.
 *
 * @since 2025.1
 */
public class MultiStatusWriter implements AutoCloseable {

    protected static final String DAV_NS = "DAV:";

    protected static final String DAV_PREFIX = "D";

    /**
     * {@code getlastmodified} is an HTTP-date, which requires a two-digit day. {@code DateTimeFormatter#RFC_1123_DATE_TIME}
     * emits a single digit below the tenth of the month, which some clients reject, so the pattern is spelled out.
     */
    protected static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.ofPattern(
            "EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH);

    /** {@code creationdate} is ISO 8601, not an HTTP-date. */
    protected static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'",
            Locale.ENGLISH);

    protected static final ZoneId GMT = ZoneId.of("GMT");

    protected final XMLStreamWriter writer;

    public MultiStatusWriter(OutputStream out) {
        try {
            XMLOutputFactory factory = XMLOutputFactory.newInstance();
            writer = factory.createXMLStreamWriter(out, "UTF-8");
            writer.writeStartDocument("UTF-8", "1.0");
        } catch (XMLStreamException e) {
            throw new NuxeoException("Could not start the WebDAV response", e);
        }
    }

    /* ==================== Multistatus ==================== */

    public void startMultiStatus() {
        run(() -> {
            writer.setPrefix(DAV_PREFIX, DAV_NS);
            writer.writeStartElement(DAV_NS, "multistatus");
            writer.writeNamespace(DAV_PREFIX, DAV_NS);
        });
    }

    public void endMultiStatus() {
        run(() -> {
            writer.writeEndElement();
            writer.writeEndDocument();
            writer.flush();
        });
    }

    /**
     * Writes one {@code response} element describing a resource.
     */
    public void writeResource(DavResourceInfo info) {
        run(() -> {
            writer.writeStartElement(DAV_NS, "response");
            element("href", info.href());
            writer.writeStartElement(DAV_NS, "propstat");
            writer.writeStartElement(DAV_NS, "prop");

            element("displayname", info.displayName());

            writer.writeStartElement(DAV_NS, "resourcetype");
            if (info.collection()) {
                emptyElement("collection");
            }
            writer.writeEndElement();

            if (!info.collection()) {
                element("getcontentlength", Long.toString(Math.max(info.contentLength(), 0L)));
                element("getcontenttype", info.contentType());
                if (StringUtils.isNotBlank(info.etag())) {
                    element("getetag", quote(info.etag()));
                }
            }
            element("getlastmodified", formatHttpDate(info.lastModified()));
            element("creationdate", formatIsoDate(info.creationDate()));

            writeSupportedLock();
            writeLockDiscovery(info.lockOwner(), info.lockToken());

            writer.writeEndElement();
            element("status", "HTTP/1.1 200 OK");
            writer.writeEndElement();
            writer.writeEndElement();
        });
    }

    /**
     * Writes the multistatus answer to a {@code PROPPATCH}, claiming success for every property the client sent.
     * <p>
     * Office sets the four {@code Win32*} properties every time it saves and treats a failure as fatal. None of them
     * has a counterpart in Nuxeo, so they are accepted and discarded, which is also what the platform's own WebDAV
     * module does.
     */
    public void writePropPatchResult(String href, List<PropertyName> properties) {
        run(() -> {
            writer.writeStartElement(DAV_NS, "response");
            element("href", href);
            writer.writeStartElement(DAV_NS, "propstat");
            writer.writeStartElement(DAV_NS, "prop");
            int index = 0;
            for (PropertyName property : properties) {
                String namespace = property.namespace();
                if (StringUtils.isEmpty(namespace)) {
                    writer.writeEmptyElement(property.name());
                } else if (DAV_NS.equals(namespace)) {
                    writer.writeEmptyElement(DAV_NS, property.name());
                } else {
                    /*
                     * Office sets its four Win32 properties in urn:schemas-microsoft-com:, and StAX refuses to write
                     * an element in a namespace that is not bound to a prefix. Bind one per property rather than let
                     * the whole response fail: Office treats a PROPPATCH error as fatal to the save.
                     */
                    String prefix = "P" + index++;
                    writer.writeEmptyElement(prefix, property.name(), namespace);
                    writer.writeNamespace(prefix, namespace);
                }
            }
            writer.writeEndElement();
            element("status", "HTTP/1.1 200 OK");
            writer.writeEndElement();
            writer.writeEndElement();
        });
    }

    /* ==================== Lock ==================== */

    /**
     * Writes the standalone {@code prop} body returned by {@code LOCK}.
     */
    public void writeLockProp(String owner, String token) {
        run(() -> {
            writer.setPrefix(DAV_PREFIX, DAV_NS);
            writer.writeStartElement(DAV_NS, "prop");
            writer.writeNamespace(DAV_PREFIX, DAV_NS);
            writeLockDiscovery(owner, token);
            writer.writeEndElement();
            writer.writeEndDocument();
            writer.flush();
        });
    }

    protected void writeSupportedLock() throws XMLStreamException {
        writer.writeStartElement(DAV_NS, "supportedlock");
        writer.writeStartElement(DAV_NS, "lockentry");
        writer.writeStartElement(DAV_NS, "lockscope");
        emptyElement("exclusive");
        writer.writeEndElement();
        writer.writeStartElement(DAV_NS, "locktype");
        emptyElement("write");
        writer.writeEndElement();
        writer.writeEndElement();
        writer.writeEndElement();
    }

    /**
     * Writes {@code lockdiscovery}, empty when the resource is not locked.
     * <p>
     * {@code lockroot} is deliberately omitted. It is optional in RFC 4918 and several Office versions are known to
     * fail when it is present.
     */
    protected void writeLockDiscovery(String owner, String token) throws XMLStreamException {
        if (StringUtils.isBlank(token)) {
            emptyElement("lockdiscovery");
            return;
        }
        writer.writeStartElement(DAV_NS, "lockdiscovery");
        writer.writeStartElement(DAV_NS, "activelock");
        writer.writeStartElement(DAV_NS, "locktype");
        emptyElement("write");
        writer.writeEndElement();
        writer.writeStartElement(DAV_NS, "lockscope");
        emptyElement("exclusive");
        writer.writeEndElement();
        element("depth", DavConstants.DEPTH_0);
        element("owner", StringUtils.defaultString(owner));
        element("timeout", "Second-" + DavConstants.LOCK_TIMEOUT_SECONDS);
        writer.writeStartElement(DAV_NS, "locktoken");
        element("href", token);
        writer.writeEndElement();
        writer.writeEndElement();
        writer.writeEndElement();
    }

    /* ==================== Helpers ==================== */

    protected void element(String name, String value) throws XMLStreamException {
        writer.writeStartElement(DAV_NS, name);
        writer.writeCharacters(StringUtils.defaultString(value));
        writer.writeEndElement();
    }

    protected void emptyElement(String name) throws XMLStreamException {
        writer.writeEmptyElement(DAV_NS, name);
    }

    protected static String quote(String value) {
        return "\"" + value.replace("\"", "") + "\"";
    }

    public static String formatHttpDate(long millis) {
        return HTTP_DATE.format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), GMT));
    }

    public static String formatIsoDate(long millis) {
        return ISO_DATE.format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), GMT));
    }

    @Override
    public void close() {
        try {
            writer.close();
        } catch (XMLStreamException e) {
            throw new NuxeoException("Could not close the WebDAV response", e);
        }
    }

    protected void run(XmlAction action) {
        try {
            action.run();
        } catch (XMLStreamException e) {
            throw new NuxeoException("Could not write the WebDAV response", e);
        }
    }

    @FunctionalInterface
    protected interface XmlAction {
        void run() throws XMLStreamException;
    }

    /** A qualified XML property name, as sent by the client in a {@code PROPPATCH}. */
    public record PropertyName(String namespace, String name) {
    }

    /**
     * Everything needed to describe one resource in a {@code PROPFIND} answer.
     *
     * @param href the percent-encoded URL of the resource
     * @param collection whether it is a collection
     * @param displayName the human readable name
     * @param contentLength the size in bytes, ignored for a collection
     * @param contentType the mime type, ignored for a collection
     * @param lastModified last modification, in milliseconds since the epoch
     * @param creationDate creation, in milliseconds since the epoch
     * @param etag an opaque validator, or {@code null}
     * @param lockOwner the lock owner, or {@code null} when not locked
     * @param lockToken the lock token, or {@code null} when not locked
     */
    public record DavResourceInfo(String href, boolean collection, String displayName, long contentLength,
            String contentType, long lastModified, long creationDate, String etag, String lockOwner,
            String lockToken) {
    }
}
