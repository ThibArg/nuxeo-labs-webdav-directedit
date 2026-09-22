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

import java.util.Optional;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.kv.KeyValueService;
import org.nuxeo.runtime.kv.KeyValueStore;
import org.nuxeo.runtime.services.config.ConfigurationService;

/**
 * Holds the edit sessions: the mapping from the opaque token in a WebDAV URL to the document and the user it was
 * issued for.
 * <p>
 * Microsoft Office cannot perform an interactive single sign-on flow, so on an SSO deployment the user has no
 * password to type into its credentials prompt. A token carried in the URL removes the prompt altogether. It is put in
 * the <b>path</b> rather than in the query string because Office builds the parent collection, the temporary file
 * names and the {@code Destination} header by manipulating the path: a query string would be dropped on the second
 * step of a save, a path prefix rides along.
 * <p>
 * The token replaces the document id in the URL rather than being added to it, so the address is no longer than
 * before, which matters because the Office URI scheme caps it at 256 characters and at 216 for Excel.
 * <p>
 * <b>The capability is the address.</b> A token resolves to exactly one document, so there is no way to express
 * "some other document" with it, and no scope check to forget. It is also meaningless outside this plugin's endpoint,
 * because {@code OfficeEditCapabilityAuthenticator} is only ever in that endpoint's authentication chain. That makes
 * it a far narrower credential than the account-wide token the sign-in help offers as a fallback.
 * <p>
 * Two lifetimes apply, and keeping them apart is the point. The record goes on <b>designating</b> its document for
 * {@code session.ttlMinutes}, while the token only <b>authenticates</b> for {@code capabilityUrl.ttlMinutes}. Once the
 * shorter window lapses, Office asks for credentials — a password, or the account token from the sign-in help — and
 * the document still opens. Were there a single lifetime, entering correct credentials would still yield a 404, and
 * the fallback to Basic authentication would be useless.
 * <p>
 * Both are idle timeouts, refreshed on use.
 *
 * @since 2025.1
 */
public class OfficeEditSessionStore {

    /** Auto-created, namespaced from the default key/value store. No contribution is required. */
    public static final String STORE_NAME = "officeEditSession";

    /** How long the URL keeps <b>authenticating</b>, in minutes of inactivity. */
    public static final String AUTH_TTL_MINUTES_PROP = "nuxeo.labs.webdavDirectEdit.capabilityUrl.ttlMinutes";

    public static final int DEFAULT_AUTH_TTL_MINUTES = 480;

    /**
     * Absolute lifetime of an edit session, in minutes from the moment it was issued.
     * <p>
     * This is a hard ceiling, deliberately not refreshed on use. Without it a URL that is polled regularly would live
     * for ever, and since an issued URL is a bearer credential that nothing else can revoke, "for ever" is the wrong
     * answer.
     */
    public static final String SESSION_TTL_MINUTES_PROP = "nuxeo.labs.webdavDirectEdit.session.ttlMinutes";

    public static final int DEFAULT_SESSION_TTL_MINUTES = 43200;

    /** Public so tests can compose a stored record with a back-dated authentication stamp. */
    public static final String SEPARATOR = ":";

    /**
     * An issued edit session.
     *
     * @param docId the document the token grants access to, and the only one
     * @param userName the user the token was issued for
     * @param issuedAtMillis when the token was minted; fixed for the whole life of the session
     * @param lastAuthenticatedMillis when the token last authenticated a request
     */
    public record OfficeEditSession(String docId, String userName, long issuedAtMillis,
            long lastAuthenticatedMillis) {
    }

    protected KeyValueStore store() {
        return Framework.getService(KeyValueService.class).getKeyValueStore(STORE_NAME);
    }

    protected static int minutes(String property, int defaultValue) {
        ConfigurationService configurationService = Framework.getService(ConfigurationService.class);
        int value = configurationService == null ? defaultValue
                : configurationService.getInteger(property, defaultValue);
        return value <= 0 ? defaultValue : value;
    }

    /** Absolute ceiling, from issue. */
    protected long sessionLifetimeMillis() {
        return minutes(SESSION_TTL_MINUTES_PROP, DEFAULT_SESSION_TTL_MINUTES) * 60_000L;
    }

    /** Window during which the token authenticates, refreshed on every successful authentication. */
    protected long authWindowMillis() {
        return minutes(AUTH_TTL_MINUTES_PROP, DEFAULT_AUTH_TTL_MINUTES) * 60_000L;
    }

    /**
     * Issues a token for a document and a user.
     *
     * @return the token, to be used as the first path segment of the WebDAV URL
     */
    public String create(String docId, String userName) {
        // UUID.randomUUID is backed by SecureRandom: 122 bits of entropy, and 36 characters, which is what the URL
        // length budget allows.
        String token = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        write(store(), token, new OfficeEditSession(docId, userName, now, now));
        return token;
    }

    /**
     * Resolves a token to its document, with no regard for the authentication window.
     * <p>
     * This is what the servlet calls. A token goes on designating its document long after it has stopped
     * authenticating, so that an address kept in Office's recent files still opens once the user has answered a
     * credentials prompt. Without that split, typing a correct password would still yield a 404.
     *
     * @return empty when the token is unknown, or past its absolute lifetime
     */
    public Optional<OfficeEditSession> resolve(String token) {
        KeyValueStore keyValueStore = store();
        Optional<OfficeEditSession> session = read(keyValueStore, token);
        if (session.isEmpty()) {
            return Optional.empty();
        }
        OfficeEditSession value = session.get();
        if (isExpired(value)) {
            keyValueStore.put(token, (String) null);
            return Optional.empty();
        }
        // Rewrite so the entry's own expiry tracks the remaining lifetime. Using a document keeps its address
        // readable, it never extends the ceiling.
        write(keyValueStore, token, value);
        return session;
    }

    /**
     * Resolves a token and tells whether it may still authenticate the request.
     * <p>
     * This is what the authentication plugin calls. The window is an idle timeout: a document that stays open in
     * Office never expires under the user, an abandoned one stops being a credential while remaining addressable
     * until the absolute lifetime runs out.
     *
     * @return empty when the token is unknown, expired, or outside its authentication window
     */
    public Optional<OfficeEditSession> resolveForAuthentication(String token) {
        KeyValueStore keyValueStore = store();
        Optional<OfficeEditSession> session = read(keyValueStore, token);
        if (session.isEmpty()) {
            return Optional.empty();
        }
        OfficeEditSession value = session.get();
        long now = System.currentTimeMillis();
        if (isExpired(value) || now - value.lastAuthenticatedMillis() > authWindowMillis()) {
            return Optional.empty();
        }
        var refreshed = new OfficeEditSession(value.docId(), value.userName(), value.issuedAtMillis(), now);
        write(keyValueStore, token, refreshed);
        return Optional.of(refreshed);
    }

    protected boolean isExpired(OfficeEditSession session) {
        return System.currentTimeMillis() - session.issuedAtMillis() >= sessionLifetimeMillis();
    }

    public void revoke(String token) {
        if (StringUtils.isNotBlank(token)) {
            store().put(token, (String) null);
        }
    }

    /**
     * Writes the record, giving the entry an expiry that matches what is left of the absolute lifetime, so the store
     * discards it on its own at the ceiling.
     */
    protected void write(KeyValueStore keyValueStore, String token, OfficeEditSession session) {
        long remaining = session.issuedAtMillis() + sessionLifetimeMillis() - System.currentTimeMillis();
        long ttlSeconds = Math.max(1L, remaining / 1000L);
        keyValueStore.put(token, session.docId() + SEPARATOR + session.issuedAtMillis() + SEPARATOR
                + session.lastAuthenticatedMillis() + SEPARATOR + session.userName(), ttlSeconds);
    }

    /**
     * Reads a record of the form {@code docId:issuedAtMillis:lastAuthenticatedMillis:userName}.
     * <p>
     * Only the first three separators are significant: a document id and a timestamp never contain one, a user name
     * might.
     */
    protected Optional<OfficeEditSession> read(KeyValueStore keyValueStore, String token) {
        if (StringUtils.isBlank(token)) {
            return Optional.empty();
        }
        String value = keyValueStore.getString(token);
        if (StringUtils.isBlank(value)) {
            return Optional.empty();
        }
        String[] parts = value.split(SEPARATOR, 4);
        if (parts.length != 4 || parts[0].isEmpty() || parts[3].isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(
                    new OfficeEditSession(parts[0], parts[3], Long.parseLong(parts[1]), Long.parseLong(parts[2])));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Masks a token so it can be written to a log. The token is a live credential; the document it points at is not.
     */
    public static String mask(String token) {
        if (StringUtils.isBlank(token)) {
            return "<none>";
        }
        return token.length() <= 8 ? "<token>" : "<token:" + token.substring(0, 4) + "…>";
    }
}
