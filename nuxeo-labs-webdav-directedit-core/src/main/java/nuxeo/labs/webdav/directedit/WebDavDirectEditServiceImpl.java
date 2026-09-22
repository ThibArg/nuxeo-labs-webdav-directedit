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

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.blobholder.BlobHolder;
import org.nuxeo.ecm.core.api.blobholder.DocumentBlobHolder;
import org.nuxeo.ecm.core.api.security.SecurityConstants;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.model.ComponentContext;
import org.nuxeo.runtime.model.ComponentName;
import org.nuxeo.runtime.model.DefaultComponent;
import org.nuxeo.runtime.services.config.ConfigurationService;

import nuxeo.labs.webdav.directedit.server.DavTarget;
import nuxeo.labs.webdav.directedit.server.OfficeEditSessionStore;

/**
 * Default {@link WebDavDirectEditService} implementation.
 * <p>
 * The Office format lists are read from the configuration service rather than hard-coded, because the Web UI reads the
 * very same {@code org.nuxeo.web.ui.webdavDirectEdit.*} properties to decide whether to show the button. Sharing the
 * source guarantees the client and the server can never disagree.
 *
 * @since 2025.1
 */
public class WebDavDirectEditServiceImpl extends DefaultComponent implements WebDavDirectEditService {

    private static final Logger log = LogManager.getLogger(WebDavDirectEditServiceImpl.class);

    protected static final String CONFIG_PREFIX = "org.nuxeo.web.ui.webdavDirectEdit.";

    /**
     * Component name of the platform's WebDAV addon. Built from a string so this class can test for the addon without
     * loading any of its classes, and without depending on it.
     */
    protected static final ComponentName WEBDAV_ADDON_COMPONENT = new ComponentName("org.nuxeo.ecm.webdav.service");

    protected final OfficeEditSessionStore sessionStore = new OfficeEditSessionStore();

    /**
     * Warns when the platform's WebDAV addon is deployed alongside this plugin.
     * <p>
     * The addon contributes a {@code WebDAV_Root} authentication chain selected on the {@code User-Agent} header alone,
     * with no URL pattern at all. Specific chains are held in a {@code HashMap} and the first match in iteration order
     * wins, so requests from Word, which sends exactly one of the user agents that chain matches, may be handed to the
     * addon's chain instead of ours. Authentication for this endpoint would then silently stop working as designed.
     */
    @Override
    public void start(ComponentContext context) {
        super.start(context);
        if (Framework.getRuntime() != null && Framework.getRuntime().getComponent(WEBDAV_ADDON_COMPONENT) != null) {
            log.warn("The nuxeo-webdav addon is deployed alongside {}. Its WebDAV_Root authentication chain matches on"
                    + " the User-Agent header with no URL pattern, so it can take precedence over this plugin's own"
                    + " chain for {} and break its authentication. Uninstalling nuxeo-webdav is recommended.",
                    () -> "nuxeo-labs-webdav-directedit", () -> WEBDAV_PATH_PREFIX);
        }
    }

    protected static final String ENABLED_PROP = CONFIG_PREFIX + "enabled";

    protected static final String MAIN_BLOB_XPATH_PROP = CONFIG_PREFIX + "mainBlobXPath";

    protected static final String EXTENSIONS_PROP_SUFFIX = ".extensions";

    protected static final String MIME_TYPES_PROP_SUFFIX = ".mimeTypes";

    protected static final String BASE_URL_PROP = "nuxeo.labs.webdavDirectEdit.baseUrl";

    /** Set by nuxeo.conf; its out-of-the-box value points at localhost, which is useless for a desktop application. */
    protected static final String NUXEO_URL_PROP = "nuxeo.url";

    @Override
    public boolean isEnabled() {
        return !Framework.getService(ConfigurationService.class).isBooleanFalse(ENABLED_PROP);
    }

    @Override
    public Optional<OfficeApp> resolveOfficeApp(Blob blob) {
        if (blob == null) {
            return Optional.empty();
        }
        return resolveOfficeApp(blob.getMimeType(), blob.getFilename());
    }

    @Override
    public Optional<OfficeApp> resolveOfficeApp(String mimeType, String fileName) {
        // The mime type is authoritative when it is known; jMimeMagic returns "???" when it could not decide.
        String normalizedMimeType = normalize(mimeType);
        if (StringUtils.isNotEmpty(normalizedMimeType) && !"???".equals(normalizedMimeType)) {
            for (OfficeApp app : OfficeApp.values()) {
                if (getConfiguredSet(app, MIME_TYPES_PROP_SUFFIX).contains(normalizedMimeType)) {
                    return Optional.of(app);
                }
            }
        }
        String extension = getExtension(fileName);
        if (StringUtils.isNotEmpty(extension)) {
            for (OfficeApp app : OfficeApp.values()) {
                if (getConfiguredSet(app, EXTENSIONS_PROP_SUFFIX).contains(extension)) {
                    return Optional.of(app);
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<String> getMainBlobXPath(DocumentModel doc) {
        BlobHolder blobHolder = doc == null ? null : doc.getAdapter(BlobHolder.class);
        if (blobHolder instanceof DocumentBlobHolder documentBlobHolder) {
            return Optional.ofNullable(StringUtils.trimToNull(documentBlobHolder.getXpath()));
        }
        return Optional.empty();
    }

    @Override
    public DirectEditInfo getDirectEditInfo(DocumentModel doc, String xpath, String requestBaseUrl) {
        if (doc == null) {
            return DirectEditInfo.unavailable(UnavailabilityReason.NO_BLOB);
        }
        // The Web UI already hides the button when disabled, but an operation can be called directly.
        if (!isEnabled()) {
            return DirectEditInfo.unavailable(UnavailabilityReason.DISABLED);
        }
        // A version is immutable, a proxy is a pointer, and a trashed document is on its way out: Office would lock
        // and then fail to save any of them.
        if (doc.isVersion() || doc.isProxy() || doc.isTrashed()) {
            return DirectEditInfo.unavailable(UnavailabilityReason.NOT_EDITABLE);
        }

        /*
         * The endpoint reads and writes only the blob exposed by the document's BlobHolder. Pointing Office at any
         * other blob would make it edit, and on save overwrite, the main blob instead. So refuse anything else rather
         * than corrupt data.
         */
        String requestedXPath = StringUtils.defaultIfBlank(xpath, getDefaultBlobXPath());
        Optional<String> mainXPath = getMainBlobXPath(doc);
        if (mainXPath.isEmpty() || !mainXPath.get().equals(requestedXPath)) {
            log.debug("Requested xpath {} is not the main blob xpath {} of document {}", () -> requestedXPath,
                    () -> mainXPath.orElse("<none>"), () -> doc.getId());
            return DirectEditInfo.unavailable(UnavailabilityReason.NOT_MAIN_BLOB);
        }

        Blob blob = doc.getAdapter(BlobHolder.class).getBlob();
        if (blob == null) {
            return DirectEditInfo.unavailable(UnavailabilityReason.NO_BLOB);
        }
        String fileName = blob.getFilename();
        if (StringUtils.isBlank(fileName)) {
            return DirectEditInfo.unavailable(UnavailabilityReason.NO_BLOB);
        }

        Optional<OfficeApp> app = resolveOfficeApp(blob);
        if (app.isEmpty()) {
            return DirectEditInfo.unavailable(UnavailabilityReason.NOT_OFFICE_FILE, fileName);
        }

        // Office locks the document then saves it back, so read-only access would only fail late, after the user has
        // already made changes. This is the same permission the endpoint checks before committing.
        if (!doc.getCoreSession().hasPermission(doc.getRef(), SecurityConstants.WRITE_PROPERTIES)) {
            return DirectEditInfo.unavailable(UnavailabilityReason.NO_WRITE_PERMISSION, fileName);
        }

        /*
         * An edit session is minted per click. The token is what the URL is addressed by, so it stands in for the
         * document id: same length, but it also authenticates the request, which is the only way Microsoft Office can
         * be spared a credentials prompt it cannot answer under single sign-on.
         */
        String token = sessionStore.create(doc.getId(), doc.getCoreSession().getPrincipal().getName());
        String davPath = token + "/" + fileName;
        String davUrl = getBaseUrl(requestBaseUrl) + WEBDAV_PATH_PREFIX + DavTarget.encode(token) + "/"
                + DavTarget.encode(fileName);
        return DirectEditInfo.available(app.get(), fileName, davPath, davUrl);
    }

    /**
     * Returns the base URL to build absolute WebDAV URLs from, without a trailing slash.
     * <p>
     * An explicitly configured base URL always wins, so that an administrator can force HTTPS even when Nuxeo itself
     * is reached over plain HTTP, which Microsoft Office on Windows requires. Otherwise {@code nuxeo.url} is used,
     * unless it still points at localhost, in which case what the browser reports is the best available guess.
     */
    protected String getBaseUrl(String requestBaseUrl) {
        ConfigurationService configurationService = Framework.getService(ConfigurationService.class);
        String configured = configurationService.getString(BASE_URL_PROP, null);
        if (StringUtils.isNotBlank(configured)) {
            return removeTrailingSlash(configured.trim());
        }
        String nuxeoUrl = Framework.getProperty(NUXEO_URL_PROP);
        if (StringUtils.isNotBlank(nuxeoUrl) && !isLoopback(nuxeoUrl)) {
            return removeTrailingSlash(nuxeoUrl.trim());
        }
        // Caller-supplied, so only accept a plain absolute http(s) URL: it ends up in a link the user is invited to
        // click, and in the address Office is pointed at.
        if (isAcceptableBaseUrl(requestBaseUrl)) {
            return removeTrailingSlash(requestBaseUrl.trim());
        }
        return removeTrailingSlash(StringUtils.defaultIfBlank(nuxeoUrl, "http://localhost:8080/nuxeo").trim());
    }

    /**
     * Whether a caller-supplied base URL can be trusted enough to be built into the Office address.
     */
    protected static boolean isAcceptableBaseUrl(String url) {
        if (StringUtils.isBlank(url)) {
            return false;
        }
        try {
            URI uri = new URI(url.trim());
            return uri.isAbsolute() && ("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme())) && StringUtils.isNotBlank(uri.getHost())
                    && uri.getQuery() == null && uri.getFragment() == null && uri.getUserInfo() == null;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    protected String getDefaultBlobXPath() {
        return Framework.getService(ConfigurationService.class)
                        .getString(MAIN_BLOB_XPATH_PROP, DEFAULT_BLOB_XPATH)
                        .trim();
    }

    /**
     * Reads a comma-separated configuration list into a lower-case set.
     */
    protected Set<String> getConfiguredSet(OfficeApp app, String propertySuffix) {
        String raw = Framework.getService(ConfigurationService.class)
                             .getString(CONFIG_PREFIX + app.getId() + propertySuffix, "");
        Set<String> values = new LinkedHashSet<>();
        Arrays.stream(raw.split(","))
              .map(WebDavDirectEditServiceImpl::normalize)
              .filter(StringUtils::isNotEmpty)
              .forEach(values::add);
        return values;
    }

    protected static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ENGLISH);
    }

    protected static String getExtension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return normalize(fileName.substring(dot + 1));
    }

    protected static boolean isLoopback(String url) {
        try {
            String host = new URI(url.trim()).getHost();
            return host != null && ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                    || "[::1]".equals(host) || "::1".equals(host));
        } catch (URISyntaxException e) {
            return false;
        }
    }

    protected static String removeTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
