# AGENTS.md — nuxeo-labs-webdav-directedit

Nuxeo LTS 2025 plugin: an **Open in Microsoft Office** button in Web UI that opens the document's main blob in
Word/Excel/PowerPoint over WebDAV; saving from Office updates the document.

It ships **its own WebDAV endpoint** and does **not** use the `nuxeo-webdav` addon, on purpose — see "Why not
nuxeo-webdav". Do not reintroduce it.

## Commands

```bash
mvn -o -pl nuxeo-labs-webdav-directedit-core test                          # whole suite, ~25 s
mvn -o -pl nuxeo-labs-webdav-directedit-core test -Dtest=TestOfficeEditEndpoint
mvn -o clean install                                                       # also builds the MP zip
```

- Use **`-o`**: the root pom sets `updatePolicy=always` on three remote repositories, so an online build re-checks
  every snapshot. Everything needed, including the `nuxeo-nxr-server` zip the MP assembly diffs against, is in `~/.m2`.
- Maven is the only gate: no CI workflows (`.github/` holds only a Java-upgrade tool hook), no lint/format step.
- Test output is noisy and **not** a failure signal: `NUXEO INSTANCE REGISTRATION FILE IS MISSING`, `Nuxeo Platform
  Started` (both at ERROR) and a `FulltextExtractorWork` / `NotOfficeXmlFileException` stack trace (fixtures store
  plain text under `.docx` names) are expected. Judge by `Tests run:` or `target/surefire-reports/*.txt`.
- After touching any pom, check the MP: `unzip -l nuxeo-labs-webdav-directedit-package/target/*.zip` must show
  **exactly one jar, ours**, and `package.xml` must depend **only on `nuxeo-web-ui`**. A non-`provided` dependency
  would end up in the zip.

## Map

| | |
|---|---|
| Parent | `org.nuxeo:nuxeo-parent:2025.24` (resolves `nuxeo-ecm:2025.24.15`) |
| groupId / package | `nuxeo.labs.webdavdirectedit` / `nuxeo.labs.webdav.directedit` (`.server`, `.auth`) |
| Bundle-SymbolicName | `nuxeo.labs.webdavdirectedit.nuxeo-labs-webdav-directedit-core` — prefix for test `@Deploy("<bsn>:test-x.xml")` |
| Endpoint | `/nuxeo/site/officeedit/<sessionToken>/<blobFileName>` |

- `server/OfficeEditServlet` — every WebDAV verb. `server/OfficeEditSessionStore` — token → `(docId, user)`.
  `server/ScratchStore` — Office temp files. `server/MultiStatusWriter` — hand-written StAX multistatus XML.
- `auth/OfficeEditCapabilityAuthenticator` (token in URL), `auth/WebDavTokenBasicAuthenticator` (Basic, password or
  Nuxeo token).
- `WebDavDirectEditServiceImpl` mints the session and builds the URL; Automation ops `WebDavDirectEdit.GetUrl`,
  `.GetCredentials`, `.RevokeCredentials`.
- Web UI element under `src/main/resources/web/nuxeo.war/ui/nuxeo-labs-webdav-directedit/`.

**Stale comments — trust the code:** `OfficeEditServlet`'s class Javadoc still shows `<docId>` as the first segment
(it is the session token); `OfficeEditSessionStore`'s says "both are idle timeouts, refreshed on use" (the session
lifetime is absolute, never refreshed); `WebDavDirectEditTestUtils` still says fixtures mirror the WebDAV backend.

## SSO: the URL is the credential

Office cannot do SSO: no browser, no IdP redirect, Basic and Digest only. So the first path segment is an opaque
**edit session token** (`KeyValueStore` `officeEditSession`, native per-entry TTL) resolving to one document and one
user. It addresses *and* authenticates.

- **Path, not query string.** Office derives the parent collection, `.tmp` names and the `Destination` header from
  the path; a query string is dropped mid-save. That is why stock `JWT_AUTH` is unusable (header or `?access_token`
  only, `JWTAuthenticator.retrieveToken`), besides an HMAC512 JWT (~183 chars) blowing Excel's 216-char URI cap.
- **Two lifetimes — do not collapse them.** `resolve()` (servlet) checks only the **absolute** ceiling
  `session.ttlMinutes` (30 days from issue, **never extended by use**). `resolveForAuthentication()` (auth plugin)
  also enforces the **idle** window `capabilityUrl.ttlMinutes` (8 h, refreshed). With a single lifetime an expired
  address would 404 even after a correct password, making the Basic fallback useless. Pinned by
  `shouldServeTheDocumentWithBasicOnceTheAuthWindowHasLapsed` and `shouldChallengeWithBasicOnceTheAuthWindowHasLapsed`.
- The capability authenticator returns `null`, never prompts, so an expired session falls through to the Basic
  plugin and Office gets `401 + WWW-Authenticate`.
- Stored value: `docId:issuedAtMillis:lastAuthenticatedMillis:userName`, read with `split(SEPARATOR, 4)` (user names
  may contain `:`). `SEPARATOR` is public only so tests can write a back-dated record.
- **An issued address cannot be revoked**: `revoke()` exists but nothing calls it, there is no user → tokens index.
  The absolute ceiling is the only bound, hence it must never be refreshed.
- The chain is a `replacementChain` of `NUXEO_LABS_OFFICEEDIT_CAPABILITY_AUTH` then `NUXEO_LABS_OFFICEEDIT_BASIC_AUTH`.
  It **discards the default chain** (`SpecificAuthChainDescriptor.computeResultingChain`), which is what keeps
  OIDC/SAML redirects and `ANONYMOUS_AUTH` away from Office.
- URL pattern `(.*)/site/officeedit(/.*)?`: matched with `matches()`, and the optional group is **required**, or the
  bare mount point falls to the default chain, i.e. into the SSO redirect.
- `WebDavTokenBasicAuthenticator` only treats a secret as a Nuxeo token on this endpoint and only if it is UUID-shaped,
  so real passwords never reach `TokenAuthenticationService.getUserName` (which logs its argument at DEBUG).

**Never log the token.** Use `OfficeEditServlet.safePath(request)` / `OfficeEditSessionStore.mask(token)`; never pass
`request.getRequestURI()` or a `ScratchStore` entry key (it starts with the token) to a logger. `TestTokenMasking` only
tests the helpers, not every call site — review new log statements yourself.

## The one invariant

**Only the canonical blob file name maps to the Nuxeo document; every other name in the collection is scratch**, kept
in the bounded `TransientStore` `officeEditScratch` and never materialised as a document. Hence, all tested:

- the endpoint never creates, renames, trashes or deletes a document; `DELETE` on the canonical name → `403` (WARN);
- `MKCOL` → `405`; `MOVE`/`COPY` across collections → `403`;
- nothing is enumerated: `PROPFIND` on the root is an empty collection; `Depth: 1` on a document lists only the
  canonical file.

## Write-path ordering (security hardening — keep the order)

- `doPut` calls `checkWritable` **before** `readBody`: a refused request must not be spooled to disk first. Writing a
  scratch file requires the same permission as writing the document.
- `checkWritable` checks the **lock before the permission**: `LockSecurityPolicy` denies write to non-owners, so the
  reverse would answer 403 where Office needs 423 (it retries on 423, gives up on 403). It also re-checks
  version/proxy/trashed, since the document can change after the address was issued.
- `commitToDocument` re-runs `checkWritable` because `MOVE` reaches it without going through `doPut`.
- `doProppatch` resolves the session before parsing the body (it is the one verb that otherwise answers 207 blindly).
- Upload cap `maxUploadSizeMB` is enforced by `BoundedInputStream`; `Content-Length` alone is not trusted.
- A full scratch store throws `MaximumTransientSpaceExceeded` → `507`.
- Nothing may escape `service()` (the container would return an HTML error page); `sendStatus` resets and sets a
  bare status instead of `sendError`.
- A browser-supplied base URL is used only when `nuxeo.url` is localhost/blank, and only through `isAcceptableBaseUrl`.

## Microsoft details that are load-bearing

Verified against Microsoft's Office URI Schemes spec and sabre/dav's notes on the Office client:

- **`OPTIONS` answers `DAV: 1,2` and `MS-Author-Via: DAV` without resolving the document** — Word probes before the
  resource exists. Without them Word silently opens read-only.
- **`LOCK` must succeed** or Word opens read-only. `doLock` is **idempotent for the same owner** (200, same token):
  Windows Word sends 3 `LOCK`s per session. The lock token is *derived* from `(docId, owner)`, so there is no state.
- **No `lockroot` in `lockdiscovery`** (breaks several Office versions; `nuxeo-webdav` emits it). A test asserts it.
- **`PROPPATCH` must return 207 success**; the `Win32*` properties are accepted and discarded. They are in
  `urn:schemas-microsoft-com:` and StAX refuses an unbound namespace, hence the per-property prefix binding in
  `MultiStatusWriter.writePropPatchResult`. No measured client sends `PROPPATCH`, but keep it.
- **URI cap 256 chars, 216 for Excel**: keep the URL `<token>/<name>` with nothing else; the token *replaces* the doc
  id, both 36 chars.
- No `404` propstat is ever emitted, which sidesteps the `Brief: t` header.

## Streaming

Do **not** use `DownloadService.downloadBlob`: it redirects to the provider URI (S3 pre-signed link) with no opt-out
(`DownloadServiceImpl` ~548-558), taking Office out of its WebDAV session. `streamBlob` calls
`transferBlobWithByteRange` directly, after `commitAndReopenTransaction()` so a long transfer does not outlive the
request transaction. Uploads spool to a temp file via `Blobs.createBlob(InputStream)`. WebDAV has no partial write:
every save is a full upload.

## Wiring

- **A raw `HttpServlet`, not JAX-RS**; `service()` is overridden because `HttpServlet.service()` answers `501` to
  WebDAV verbs. No third-party WebDAV library: they are LGPL, and this is an Apache-2.0 MP.
- `javax.xml.stream` in `MultiStatusWriter`/`DavXmlParser` is the JDK's StAX — not a `javax`→`jakarta` violation.
- Mounted by `deployment-fragment.xml` (`web#SERVLET`, `/site/officeedit/*`). More specific than WebEngine's `/site/*`,
  so it wins while **inheriting** the auth, request-controller and header filters. **Do not add filter mappings**:
  a filter matched by both patterns runs twice.
- `webdav-directedit-requestcontroller-contrib.xml`: the attribute is spelled **`synchonize`** on purpose — that is
  the name `FilterConfigDescriptor` declares. Do not "fix" it.
- `AutoPrompt=true` on the Basic plugin gives `401 + WWW-Authenticate` instead of a login-page redirect.
- `MANIFEST.MF` lists seven components, `webdav-directedit-properties.xml` **first**.

## Why not nuxeo-webdav

Not history: it explains why old instincts are wrong here.

- Its `SearchRootBackend` only mounts top-level `Workspace`s (`select * from Workspace …`), so any other document
  404s; its resources call `BlobHolder.getBlob()` directly, so a custom backend cannot widen that. Siblings sharing a
  blob file name were ambiguous, and Office's `~$`/`.tmp` files became real documents.
- Addressing by token removed all three; `shouldOpenADocumentOutsideAnyWorkspace` and
  `shouldOpenBothDocumentsSharingABlobFileName` pin it. `UnavailabilityReason` deliberately no longer has
  `WEBDAV_NOT_INSTALLED` / `NOT_REACHABLE` / `AMBIGUOUS`, and `WebDavPathResolver` is gone — do not revive them.
- Do not copy its `WebDAV_Root` auth chain: it selects on `User-Agent` with no URL pattern, i.e. server-wide.
- **Co-installed, it can hijack us**: `WebDAV_Root` matches Word's user agents, specific chains live in a `HashMap`,
  first match wins (`PluggableAuthenticationService.getSpecificAuthChainName`). `WebDavDirectEditServiceImpl.start()`
  WARNs if present, using a string `ComponentName` so we never link against it.

## Configuration

- `org.nuxeo.web.ui.webdavDirectEdit.*` — read by **both** the browser (`Nuxeo.UI.config`, prefix stripped) and the
  server (`ConfigurationService`, full name): `enabled`, `mainBlobXPath`, `<app>.extensions`, `<app>.mimeTypes`,
  `showCredentialsHelp`, `warnOnInsecureUrl`. Single source of truth for "is this an Office file".
- `nuxeo.labs.webdavDirectEdit.*` — server only, never shipped to browsers: `baseUrl`, `tokenAuth.enabled`,
  `capabilityUrl.enabled`, `capabilityUrl.ttlMinutes`, `session.ttlMinutes`, `maxUploadSizeMB`.
- Defaults live in `webdav-directedit-properties.xml`. The service has **no extension point on purpose**.
- Adding an `UnavailabilityReason` value requires keys in both `messages.json` and `messages-fr.json`;
  `TestUnavailabilityReasonI18n` fails otherwise (Web UI would fall back silently).

## Web UI

- Slot `DOCUMENT_ACTIONS`, `order="12"` (Edit 10, Delete 15). A **document** action, not a blob action: only the main
  blob is served. `xpath` defaults from `Nuxeo.UI.config` `webdavDirectEdit.mainBlobXPath`.
- `showLabel` is **mandatory**: `nuxeo-actions-menu` sets `show-label` when the action overflows into `⋮`.
- Legacy `<dom-module>` + `Polymer({...})` HTML imports; behaviors `Nuxeo.I18nBehavior`, `Nuxeo.FiltersBehavior`.
- Launch the `ms-word:` URL with a **detached anchor click**, never `window.location` (unregistered scheme = navigation
  error). A real `<a href>` is also rendered as a user-gesture fallback.
- The token is cleared from the DOM on `iron-overlay-closed` and never echoed in the toast.
- i18n: the fragment `<append>`s `messages-fr.json` to `messages-fr.json`, `-fr-FR.json` and `-fr-CA.json`.
  `AppendCommand` merges `.json` with Jackson, so each i18n file must be a **complete JSON object**.

## Tests

- `OfficeEditServerFeature` starts embedded Tomcat (real HTTP, jackrabbit WebDAV client); `WebDavDirectEditFeature`
  does not. Keep them separate: Tomcat costs seconds per class.
- **`test-servletcontainer-config.xml` must `<require>` `org.nuxeo.ecm.platform.ui.web.auth.defaultConfig`
  DIRECTLY.** A transitive require does not order registration, and the default chain overwrites ours.
- That file also narrows the **default** chain to our Basic plugin: `NuxeoAuthenticationFilter.initUnAuthenticatedURLPrefix`
  walks the default chain and throws on `OAUTH2_AUTH`, which is absent in tests — but after caching, so only the
  **first request of the run** fails. Order-dependent; do not undo.
- It restates the request-controller pattern too: production keys it off `${org.nuxeo.ecm.contextPath}`, which
  differs in the embedded container, and `RequestControllerService` denies by default.
- Config variants are small `test-*.xml` files deployed per class/method (`test-small-upload-limit.xml` sets 1 MB).
- Do not weaken: `shouldReplayTheOfficeSaveDance` (only proof of the transacted save, synthetic),
  `shouldCompleteAWholeSaveWithNoCredentialsAtAll` (the SSO proof: no `Authorization` header at all), the two
  auth-window tests above, `shouldOpenADocumentOutsideAnyWorkspace`, `shouldOpenBothDocumentsSharingABlobFileName`.
- Fixtures still build `/workspaces/<ws>` out of history; nothing requires a Workspace any more.

## The transacted save path (keep it)

Measured clients send a plain `PUT` on the canonical name. The other shape, implemented by `doMoveOrCopy` +
`ScratchStore` shadows:

| Office | us |
|---|---|
| `PUT tmp1.tmp` | store in scratch |
| `MOVE Report.docx → tmp2.tmp` | record a **shadow**; document untouched, no bytes copied |
| `MOVE tmp1.tmp → Report.docx` | **the commit** |
| `DELETE tmp2.tmp` | drop the scratch entry |

**Unobserved is not unreachable — do not delete it.** Likely routes: File › Open or a mapped drive (Windows WebClient
turns filesystem renames into `MOVE`s — and the dialog's `urlHint` advertises this route), Excel/PowerPoint, older
Office, Finder/Explorer mounts. `noteTransactedSave` logs one INFO line, once per server lifetime, the first time any
client takes it: that is the field measurement that may one day justify retiring it.

## What a real Office client actually does

(Heading referenced from `ScratchStore` and `OfficeEditServlet` Javadoc and log text — keep it.) Measured 22/09/2026,
Word for Mac and Word for Windows, via the `ms-word:` protocol handler. Windows over plain HTTP locally; macOS also
over HTTPS on a hosted instance with the full hardening, no regression. Do not reintroduce assumptions that
contradict it.

- Seen on both: `OPTIONS`, `HEAD`, `GET`, `LOCK`, `UNLOCK`, `PROPFIND`, `PUT`. **Never: `PROPPATCH`, `MOVE`, `COPY`,
  `DELETE`, `MKCOL`.** `PROPFIND` targets only the file, never the collection.
- Windows opens in **Protected View**: lock, read, `UNLOCK`, then 10–45 s later (*Enable Editing*) lock again and edit.
  The document is unlocked in Nuxeo during that pause — client behaviour, not ours.
- macOS only: a second writer is refused (`LockSecurityPolicy`); **one version per editing session**, not per save;
  file names with spaces round-trip through `DavTarget.encode`/`decode`; **reopening from recent files works**
  (that is what the two lifetimes are for).
- Unverified: Excel, PowerPoint, older Office, Windows over HTTPS, a large document, the Basic fallback with a real
  client, File › Open. `README.md` "Validation status" holds the user-facing tables — keep both in sync.

## A new address per click

`getDirectEditInfo` mints a fresh session on every call. Costs: Office's recent-files list grows one entry per click;
Windows *Trusted Documents* (keyed on the address) never kicks in, so Protected View returns each time (consistent with
the trace, not proven); more live credentials than needed. Reusing a valid session per `(docId, userName)` would fix
all three and give revocation its index — not implemented. If added, keep `issuedAtMillis` from the **first** issue.

## Observability

- **One INFO line per save** in `commitToDocument` — do not demote it; without it a session leaves no trace.
- One INFO line per server lifetime from `noteTransactedSave` (a measurement instrument, see above).
- One DEBUG line per request at the top of `service()` — the only record of which verbs a client sends (no Tomcat
  access log by default).

## Conventions

- Global Nuxeo rules apply; this repo uses **`@since 2025.1`** on new public API.
- Log4j2: use all plain args or all `Supplier`s in one call — a lambda cannot bind to the `Object...` overload.
- Keep `README.md` in step with behaviour changes: it documents config, limits and validation status for users.

## This file is public

`AGENTS.md` is committed here (unlike most Nuxeo Labs plugins), **and** `nuxeo-parent` copies root `*.md` into the
jar as `doc-parent/AGENTS.md`, so it ships inside the MP zip. No credentials, customer data, local paths or real
document ids.
