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

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import nuxeo.labs.webdav.directedit.server.MultiStatusWriter.PropertyName;

/**
 * Reads the little XML this endpoint needs from a request body.
 * <p>
 * Only {@code PROPPATCH} is actually parsed, to echo back the property names the client asked to set. {@code PROPFIND}
 * bodies are ignored on purpose: this endpoint always answers with the same fixed property set, which is what Office
 * asks for anyway.
 * <p>
 * The parser is hardened against XXE: external entities and DTDs are refused outright.
 *
 * @since 2025.1
 */
public class DavXmlParser {

    private static final Logger log = LogManager.getLogger(DavXmlParser.class);

    /** Guards against a hostile or runaway body; real Office requests hold four properties. */
    protected static final int MAX_PROPERTIES = 64;

    protected static XMLInputFactory newFactory() {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, Boolean.TRUE);
        return factory;
    }

    /**
     * Collects the qualified names of the properties listed under {@code propertyupdate/set/prop}.
     *
     * @return the property names, possibly empty, never {@code null}
     */
    public static List<PropertyName> parsePropPatch(InputStream in) {
        var properties = new ArrayList<PropertyName>();
        if (in == null) {
            return properties;
        }
        XMLStreamReader reader = null;
        try {
            reader = newFactory().createXMLStreamReader(in);
            boolean inProp = false;
            int depth = 0;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String local = reader.getLocalName();
                    if (inProp) {
                        depth++;
                        // Only direct children of <prop> are property names.
                        if (depth == 1 && properties.size() < MAX_PROPERTIES) {
                            properties.add(new PropertyName(namespaceOf(reader), local));
                        }
                    } else if ("prop".equals(local)) {
                        inProp = true;
                        depth = 0;
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if (inProp) {
                        if (depth == 0) {
                            inProp = false;
                        } else {
                            depth--;
                        }
                    }
                }
            }
        } catch (XMLStreamException e) {
            // A malformed body must not fail the save: Office treats a PROPPATCH error as fatal.
            log.debug("Could not parse the PROPPATCH body, answering with no properties", e);
        } finally {
            closeQuietly(reader);
        }
        return properties;
    }

    protected static String namespaceOf(XMLStreamReader reader) {
        String namespace = reader.getNamespaceURI();
        return namespace == null ? "" : namespace;
    }

    protected static void closeQuietly(XMLStreamReader reader) {
        if (reader != null) {
            try {
                reader.close();
            } catch (XMLStreamException e) {
                log.trace("Could not close the XML reader", e);
            }
        }
    }

    private DavXmlParser() {
        // utility class
    }
}
