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

import org.nuxeo.ecm.automation.core.Constants;
import org.nuxeo.ecm.automation.core.annotations.Context;
import org.nuxeo.ecm.automation.core.annotations.Operation;
import org.nuxeo.ecm.automation.core.annotations.OperationMethod;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.Blobs;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.api.NuxeoPrincipal;
import org.nuxeo.ecm.tokenauth.service.TokenAuthenticationService;
import org.nuxeo.runtime.services.config.ConfigurationService;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Returns the credentials the current user can type into the Microsoft Office sign-in prompt: their user name, and a
 * Nuxeo authentication token to use as the password.
 * <p>
 * This is what makes the feature usable on SSO-only deployments. The token is bound to the current user only, and
 * {@code acquireToken} is idempotent, so calling this repeatedly always yields the same token.
 *
 * @since 2025.1
 */
@Operation(id = GetDirectEditCredentialsOp.ID, category = Constants.CAT_USERS_GROUPS, //
        label = "WebDAV Direct Edit: Get Credentials", //
        description = "Returns {username, token, enabled} for the current user. The token can be used as the password "
                + "in the Microsoft Office sign-in prompt, which is the only way to authenticate Office against an "
                + "SSO-protected Nuxeo. Returns enabled=false and a null token when the feature is disabled.")
public class GetDirectEditCredentialsOp {

    public static final String ID = "WebDavDirectEdit.GetCredentials";

    @Context
    protected NuxeoPrincipal principal;

    @Context
    protected TokenAuthenticationService tokenAuthenticationService;

    @Context
    protected ConfigurationService configurationService;

    @OperationMethod
    public Blob run() {
        ObjectNode json = JsonNodeFactory.instance.objectNode();

        boolean enabled = !configurationService.isBooleanFalse(WebDavDirectEditServiceImpl.ENABLED_PROP)
                && !configurationService.isBooleanFalse(DirectEditCredentials.TOKEN_AUTH_ENABLED_PROP)
                && !configurationService.isBooleanFalse(DirectEditCredentials.SHOW_CREDENTIALS_HELP_PROP);
        json.put("enabled", enabled);

        if (principal == null || principal.isAnonymous()) {
            throw new NuxeoException("A real user is required to acquire a direct edit token");
        }
        json.put("username", principal.getName());

        if (enabled) {
            // acquireToken already runs privileged internally, and the token is issued for the caller only.
            String token = tokenAuthenticationService.acquireToken(principal.getName(),
                    DirectEditCredentials.APPLICATION_NAME, DirectEditCredentials.DEVICE_ID,
                    DirectEditCredentials.DEVICE_DESCRIPTION, DirectEditCredentials.PERMISSION);
            json.put("token", token);
        } else {
            json.putNull("token");
        }

        return Blobs.createJSONBlob(json.toString());
    }
}
