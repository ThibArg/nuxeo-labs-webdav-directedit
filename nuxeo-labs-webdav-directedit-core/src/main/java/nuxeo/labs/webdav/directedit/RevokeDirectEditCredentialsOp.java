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

import org.apache.commons.lang3.StringUtils;
import org.nuxeo.ecm.automation.core.Constants;
import org.nuxeo.ecm.automation.core.annotations.Context;
import org.nuxeo.ecm.automation.core.annotations.Operation;
import org.nuxeo.ecm.automation.core.annotations.OperationMethod;
import org.nuxeo.ecm.core.api.NuxeoPrincipal;
import org.nuxeo.ecm.tokenauth.service.TokenAuthenticationService;

/**
 * Revokes the direct edit token of the current user.
 * <p>
 * Only the caller's own token can be revoked: the token is looked up from the caller's name, never taken as a
 * parameter, so this cannot be used to revoke someone else's credentials.
 *
 * @since 2025.1
 */
@Operation(id = RevokeDirectEditCredentialsOp.ID, category = Constants.CAT_USERS_GROUPS, //
        label = "WebDAV Direct Edit: Revoke Credentials", //
        description = "Revokes the current user's WebDAV direct edit token. Microsoft Office will ask for credentials "
                + "again the next time it opens a document. Does nothing when no token was issued.")
public class RevokeDirectEditCredentialsOp {

    public static final String ID = "WebDavDirectEdit.RevokeCredentials";

    @Context
    protected NuxeoPrincipal principal;

    @Context
    protected TokenAuthenticationService tokenAuthenticationService;

    @OperationMethod
    public void run() {
        if (principal == null || principal.isAnonymous()) {
            return;
        }
        // Both calls already run privileged internally, and the token is looked up from the caller's own name.
        String token = tokenAuthenticationService.getToken(principal.getName(),
                DirectEditCredentials.APPLICATION_NAME, DirectEditCredentials.DEVICE_ID);
        if (StringUtils.isNotBlank(token)) {
            tokenAuthenticationService.revokeToken(token);
        }
    }
}
