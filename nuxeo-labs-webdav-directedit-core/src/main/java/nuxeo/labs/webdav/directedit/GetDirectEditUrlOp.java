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

import jakarta.servlet.http.HttpServletRequest;

import org.nuxeo.ecm.automation.core.Constants;
import org.nuxeo.ecm.automation.core.annotations.Context;
import org.nuxeo.ecm.automation.core.annotations.Operation;
import org.nuxeo.ecm.automation.core.annotations.OperationMethod;
import org.nuxeo.ecm.automation.core.annotations.Param;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.Blobs;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.platform.web.common.vh.VirtualHostHelper;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Returns the WebDAV URL and the Office protocol handler URI for a document, or the reason why it cannot be opened.
 *
 * @since 2025.1
 */
@Operation(id = GetDirectEditUrlOp.ID, category = Constants.CAT_DOCUMENT, label = "WebDAV Direct Edit: Get URL", //
        description = "Returns the WebDAV URL and the ms-word:/ms-excel:/ms-powerpoint: protocol URI allowing the "
                + "input document to be opened in the matching Microsoft Office desktop application. Returns a JSON "
                + "blob: {available, app, fileName, davPath, davUrl, protocolUrl, secure, reason}. When available is "
                + "false, reason tells why: disabled, noBlob, notMainBlob, notOfficeFile, notEditable or "
                + "noWritePermission.")
public class GetDirectEditUrlOp {

    public static final String ID = "WebDavDirectEdit.GetUrl";

    @Context
    protected WebDavDirectEditService service;

    /**
     * Injected so the base URL can be derived from the request rather than trusted from a parameter. May be
     * {@code null} when the operation is run outside an HTTP request, for instance from a script.
     */
    @Context
    protected HttpServletRequest request;

    @Param(name = "xpath", required = false, description = "Blob xpath. Defaults to the configured main blob xpath "
            + "(file:content). WebDAV can only address the document's main blob.")
    protected String xpath;

    @Param(name = "baseUrl", required = false, description = "Base URL as seen by the browser, for example "
            + "https://host/nuxeo. Ignored when the operation runs over HTTP, where the server determines it "
            + "itself. Used only when the server has no configured base URL.")
    protected String baseUrl;

    @OperationMethod
    public Blob run(DocumentModel doc) {
        /*
         * The returned address carries a live credential, so its host must never be dictated by the caller: that
         * would hand a valid edit session to any server they name. VirtualHostHelper is the platform's own answer,
         * and it is hardened against the forwarding headers a caller can set.
         */
        String effectiveBaseUrl = request == null ? baseUrl : VirtualHostHelper.getBaseURL(request);
        DirectEditInfo info = service.getDirectEditInfo(doc, xpath, effectiveBaseUrl);

        ObjectNode json = JsonNodeFactory.instance.objectNode();
        json.put("available", info.available());
        putOrNull(json, "app", info.app() == null ? null : info.app().getId());
        putOrNull(json, "fileName", info.fileName());
        putOrNull(json, "davPath", info.davPath());
        putOrNull(json, "davUrl", info.davUrl());
        putOrNull(json, "protocolUrl", info.protocolUrl());
        json.put("secure", info.secure());
        putOrNull(json, "reason", info.reason() == null ? null : info.reason().getId());

        return Blobs.createJSONBlob(json.toString());
    }

    protected static void putOrNull(ObjectNode json, String field, String value) {
        if (value == null) {
            json.putNull(field);
        } else {
            json.put(field, value);
        }
    }
}
