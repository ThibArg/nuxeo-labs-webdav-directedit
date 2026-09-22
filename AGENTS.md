# AGENTS.md — nuxeo-labs-webdav-directedit

## What this plugin is

A Nuxeo LTS 2025 plugin that adds an **Open in Microsoft Office** button to the Web UI. It opens the
current document in Word, Excel or PowerPoint over WebDAV; saving from Office updates the document.

It implements **its own WebDAV endpoint**. It does **not** depend on the `nuxeo-webdav` addon — that
dependency was removed deliberately, see "Why we left nuxeo-webdav" below. Do not reintroduce it.

## Commands

```bash
mvn -o -pl nuxeo-labs-webdav-directedit-core test                 # 71 tests, ~25 s
mvn -o -pl nuxeo-labs-webdav-directedit-core test -Dtest=TestOfficeEditEndpoint
mvn clean install                                                 # also builds the MP zip
```

- All dependencies are already in `~/.m2`, so **`-o` (offline) works** and is much faster: the root
  pom sets `updatePolicy=always` on three remote repositories, so an online build re-checks every
  snapshot.
- MP zip: `nuxeo-labs-webdav-directedit-package/target/...zip`. It must contain **exactly one jar,
  ours**, and declare **only `nuxeo-web-ui`** as a package dependency. Check with `unzip -l` after
  touching the pom.
- There is no git repository, no CI, and no lint/format/typecheck step. Maven is the only gate.

## Coordinates

| | |
|---|---|
| Parent | `org.nuxeo:nuxeo-parent:2025.24` (resolves `nuxeo-ecm:2025.24.15`) |
| groupId | `nuxeo.labs.webdavdirectedit` |
| Java package | `nuxeo.labs.webdav.directedit`, endpoint in `…directedit.server` |
| Bundle-SymbolicName | `nuxeo.labs.webdavdirectedit.nuxeo-labs-webdav-directedit-core` |
| Endpoint | `/nuxeo/site/officeedit/<sessionToken>/<blobFileName>` |

## SSO: the URL is the credential

**Office cannot do SSO.** No browser, no IdP redirect, Basic and Digest only (sabre/dav's field notes on
the Office client). Every WebDAV integration hits this, including `nuxeo-webdav`.

So the first path segment is not a document id — it is an opaque **edit session token**
(`OfficeEditSessionStore`, a `KeyValueStore` with native per-entry TTL) that resolves to exactly one
document and one user. It addresses *and* authenticates. Consequences:

- **Path, not query string.** Office derives the parent collection, the `.tmp` names and the
  `Destination` header by manipulating the path; a query string is dropped on step 2 of the save dance.
  This is the single reason stock `JWT_AUTH` cannot be reused — it reads a header or `?access_token`
  only (`JWTAuthenticator:115-122`) — and an HMAC512 JWT is ~183 chars, over Excel's 216 cap anyway.
- **No length cost.** The token *replaces* the doc id; both are one 36-char segment.
- **The capability is the address.** A token names one document, so there is no scope check to forget.
- **Two lifetimes, and do not collapse them.** `resolve()` (servlet) applies no *idle* check and lives
  until the **absolute** ceiling `session.ttlMinutes`, 30 days from issue, never extended by use.
  `resolveForAuthentication()` (auth plugin) additionally enforces the idle window
  `capabilityUrl.ttlMinutes`, 8 h, which it does refresh. With a single lifetime, an
  expired address returns **404 even after the user types a correct password**, which makes the Basic
  fallback useless — the whole point of supporting BASIC *and* SSO. Two mutation-checked tests pin this:
  `shouldServeTheDocumentWithBasicOnceTheAuthWindowHasLapsed` and
  `shouldChallengeWithBasicOnceTheAuthWindowHasLapsed`.
- `OfficeEditCapabilityAuthenticator` returns `null` rather than prompting, so an expired session falls
  through to `WebDavTokenBasicAuthenticator` and Office gets a normal `401 + WWW-Authenticate`.
- Stored record is `docId:issuedAtMillis:lastAuthenticatedMillis:userName`, `split(SEPARATOR, 4)`.
  `SEPARATOR` is public so a test can compose a back-dated record; there is no production API for that.
- **An issued address cannot be revoked.** `revoke()` exists but nothing calls it: there is no reverse
  index from user to tokens. The absolute ceiling is the only bound, which is why it must not be
  refreshed on use.

**Never log the token.** `OfficeEditServlet.safePath()` exists for exactly this; `request.getRequestURI()`
must not reach a log statement. `OfficeEditSessionStore.mask()` is the helper.

The chain is `CAPABILITY_AUTH` then `BASIC_AUTH` in a `replacementChain`, which **discards the default
chain** (`SpecificAuthChainDescriptor:103-106`) — that is what makes OIDC/SAML unreachable here, and
`SAMLAuthenticationProvider:202`'s `sendRedirect` to the IdP with it. `ANONYMOUS_AUTH` is excluded too.

The URL pattern is `(.*)/site/officeedit(/.*)?`. The optional group is **required**: `matches()` is
whole-string, and without it the bare mount point falls through to the default chain, i.e. into the SSO
redirect.

## The one invariant

**Only the canonical blob file name maps to the Nuxeo document. Every other name in the collection is
scratch**, held in a `TransientStore` (`ScratchStore`) and never materialised as a document.

Everything else is a consequence, and each is enforced and tested:

- the endpoint can never create, rename, trash or delete a document;
- `DELETE` on the canonical name → `403` (logged at WARN);
- `MKCOL` → `405`; `MOVE`/`COPY` across collections → `403`;
- nothing is ever enumerated: `PROPFIND` on the root returns an empty collection, and `PROPFIND
  Depth 1` on a document's collection lists only the canonical file, never the scratch entries.

This is why Office's `~$Report.docx` and `A1B2C3D4.tmp` never reach the repository.

## Office's save may not be a PUT

Two save shapes exist, and both must keep working.

**Both measured clients send a plain `PUT` on the canonical name** — macOS and Windows alike, through
the `ms-word:` protocol handler. See "What a real Office client actually does". That is the simple path
and it commits directly.

**The transacted save** writes a temporary file, renames the original aside, renames the temporary into
place and deletes the leftover. `doMoveOrCopy` implements the middle two steps and is the least obvious
code in the plugin:

| Word | us |
|---|---|
| `PUT tmp1.tmp` | store in scratch |
| `MOVE Report.docx → tmp2.tmp` | record a **shadow**; the document is not touched and no bytes are copied |
| `MOVE tmp1.tmp → Report.docx` | **this is the commit** |
| `DELETE tmp2.tmp` | drop the scratch entry |

No client has been observed taking it yet, which is **not** the same as it being dead — see "Unobserved
is not unreachable" for the routes that are likely to use it, and for the instrumentation that will
tell us. `TestOfficeEditEndpoint.shouldReplayTheOfficeSaveDance` replays all four steps; do not weaken
it, and remember it is a **synthetic** replay.

## Microsoft-specific details that are load-bearing

Each of these was verified against Microsoft's Office URI Schemes spec or sabre/dav's field notes. They
look like trivia and are not:

- **`OPTIONS` must answer `DAV: 1,2` *and* `MS-Author-Via: DAV`**, and must answer **without resolving
  the document** — Word probes before it knows the resource exists. Nothing in the Nuxeo platform emits
  `MS-Author-Via`. Without these, Word silently opens read-only and the feature is gone.
- **`LOCK` must succeed or Word opens read-only.** Locking is not optional.
- **`lockdiscovery` must not contain `lockroot`.** Several Office versions break on it. `nuxeo-webdav`
  emits it; we deliberately do not, and a test asserts its absence.
- **`PROPPATCH` must return 207 success.** Office sets four `Win32*` properties on every save and
  treats a failure as fatal. None maps to anything in Nuxeo, so they are accepted and discarded.
  They live in `urn:schemas-microsoft-com:`, and StAX refuses `writeEmptyElement(uri, name)` for a
  namespace that is not bound — `MultiStatusWriter.writePropPatchResult` binds a prefix per property
  for exactly that reason. **Neither client measured sends `PROPPATCH` at all**, so this is defensive:
  an earlier note in this file claimed it was a guaranteed failure on Windows, which the Windows trace
  disproved. The handling is still correct and still worth keeping.
- **URI length cap: 256 characters, 216 for Excel.** That is why the mount path is short and why the
  URL is `<id>/<name>` with nothing else in it.
- We never emit a `404` propstat block, which sidesteps the `Brief: t` header entirely.

## Streaming, and why not `DownloadService.downloadBlob`

`downloadBlob(DownloadContext)` redirects to the blob provider's own URI when one exists — an S3
pre-signed link — at `DownloadServiceImpl:548-558`, with **no opt-out** from the builder. That would
take Office out of its authenticated WebDAV session. We call
`DownloadService.transferBlobWithByteRange` directly instead, and set the headers ourselves.

`streamBlob` calls `commitAndReopenTransaction()` before writing, the `BlobWriter:99-104` idiom, so a
large file does not hold the request's transaction past its 300 s timeout. `Blobs.createBlob(InputStream)`
spools to a temp file, so nothing is buffered in memory on upload either.

Note: WebDAV has **no partial write**. Every save is a full upload. There is no way around this short
of MS-FSSHTTP, which is SharePoint-only.

## Wiring

- **A raw `HttpServlet`, not JAX-RS.** `service()` is overridden because `HttpServlet.service()` answers
  `501` for every WebDAV method. No third-party WebDAV library: they are all LGPL
  (`org.jugs.webdav:webdav-jaxrs` is LGPL-3, verified), which would land inside an Apache-2.0
  marketplace zip. The multistatus XML is hand-written with StAX in `MultiStatusWriter`.
- `javax.xml.stream` in that class is the **JDK's** StAX, not a Jakarta EE package. It is correct and is
  not a `javax` → `jakarta` violation.
- Mounted from `deployment-fragment.xml` via `web#SERVLET` on `/site/officeedit/*`. That is more
  specific than the `/site/*` WebEngine mapping so it wins, while **inheriting** the auth, request
  controller and header-fix filters already bound to `/site/*`. **Do not add filter-mapping entries
  there**: a filter matched by both patterns runs twice.
- `AutoPrompt=true` on the Basic plugin is what produces `401 + WWW-Authenticate` instead of a redirect
  to the login page. We deliberately do **not** copy `nuxeo-webdav`'s `WebDAV_Root` chain: it selects on
  `User-Agent` with no URL pattern at all and so applies server-wide.
- **A co-installed `nuxeo-webdav` can hijack us.** Its `WebDAV_Root` chain matches the exact user agents
  Word sends, `specificAuthChains` is a `HashMap`, and `PluggableAuthenticationService:174` takes the
  first match in iteration order. `WebDavDirectEditServiceImpl.start()` logs a WARN when the addon is
  present; the `ComponentName` is built from a string so we never link against it.
- `MANIFEST.MF` lists seven components, `webdav-directedit-properties.xml` **first**.

## Why we left nuxeo-webdav

Not history — it explains constraints that no longer apply, so old comments and instincts are wrong.

`SearchRootBackend:28` runs `select * from Workspace …` and mounts each top-level Workspace as a
virtual root. Any document whose first path segment is not one of them returns 404. There is no way to
widen it: `RootResource:158`, `FileResource:77` and `ExistingResource:365` call
`BlobHolder.getBlob()` directly, bypassing the backend, so a custom `backendFactory` cannot intercept.

Addressing by document id removed three limitations at once, and the tests that used to assert them are
now positive tests — `shouldOpenADocumentOutsideAnyWorkspace`, `shouldOpenBothDocumentsSharingABlobFileName`:

| | nuxeo-webdav | now |
|---|---|---|
| Document must be under a Workspace | yes | no |
| Two siblings sharing a blob file name | one unreachable | both fine |
| `~$`/`.tmp` documents in the repository | yes | none |

`UnavailabilityReason` therefore no longer has `WEBDAV_NOT_INSTALLED`, `NOT_REACHABLE` or `AMBIGUOUS`,
and `WebDavPathResolver` is gone.

## Tests

- `TestOfficeEditEndpoint` — **real HTTP** against an embedded Tomcat, driven by the jackrabbit WebDAV
  client. The save dance, the non-Workspace document, temp files never becoming documents, and every
  refusal. `shouldCompleteAWholeSaveWithNoCredentialsAtAll` is the SSO proof: GET, LOCK, PUT, MOVE,
  MOVE, DELETE with **no `Authorization` header at all**. Do not weaken it.
- `TestWebDavDirectEditService` / `TestDirectEditOperations` — URL computation and the Automation
  contract. The URL is now a string concatenation; there is no resolution step left to test.
- `TestWebDavTokenBasicAuthenticator` — token vs password, wrong user, revoked, disabled, non-endpoint
  URL, and a probe asserting a non-UUID secret never reaches the token service.
- `TestUnavailabilityReasonI18n` — guards both directions of drift between the enum and the two
  `messages*.json`. The Web UI falls back silently on a missing key, so drift is otherwise invisible.
- `TestOfficeApp` — pure unit, no runtime.

`OfficeEditServerFeature` starts the container; `WebDavDirectEditFeature` does not. Keep them separate,
Tomcat costs a few seconds per class.

**`test-servletcontainer-config.xml` must `<require>` `org.nuxeo.ecm.platform.ui.web.auth.defaultConfig`
DIRECTLY.** Requiring it transitively does not order extension registration, and the platform's default
auth chain then overwrites ours. That chain names `OAUTH2_AUTH`, which is not on the test classpath, and
`NuxeoAuthenticationFilter.initUnAuthenticatedURLPrefix` throws on a missing plugin — *after* assigning
its cache, so only the **first** request of the run fails and every later one passes. A one-test,
order-dependent failure. This cost real time; do not undo the direct require.

## Configuration split

- `org.nuxeo.web.ui.webdavDirectEdit.*` — read by **both** the browser (`Nuxeo.UI.config`, prefix
  stripped) and the server (`ConfigurationService`, full name). Single source of truth.
- `nuxeo.labs.webdavDirectEdit.*` — server only (`baseUrl`, `tokenAuth.enabled`).

The service has **no extension point on purpose**: format lists, main blob xpath and the kill switch all
come from those properties, which is the same source the browser reads.

## Web UI

- Slot `DOCUMENT_ACTIONS`, `order="12"` (Edit is 10, Delete is 15). It is a **document** action, not a
  blob action, because only the main blob is served.
- The slot passes `document` and `user` only; the element defaults `xpath` from
  `Nuxeo.UI.config.get('webdavDirectEdit.mainBlobXPath', 'file:content')`.
- `showLabel` is **mandatory**: `nuxeo-actions-menu` sets `show-label` when an action overflows into the
  `⋮` dropdown.
- Legacy `<dom-module>` + `Polymer({...})` HTML imports. Globals: `Nuxeo.I18nBehavior`,
  `Nuxeo.FiltersBehavior`.
- The protocol is launched with a **detached anchor click**, never `window.location`: browsers silently
  ignore an anchor click on an unregistered scheme, whereas assigning `location` raises a navigation
  error. A real `<a href>` is also rendered as a guaranteed user-gesture fallback.
- The token is cleared from the DOM on `iron-overlay-closed` and never echoed in the toast.
- `deployment-fragment.xml` `<require>org.nuxeo.web.ui</require>` and `<append>`s the i18n files. Web UI's
  own fragment copies `messages-fr.json` to `messages-fr-FR.json` and `messages-fr-CA.json`; ours appends
  to all three. `AppendCommand` does a Jackson `ObjectNode.setAll` merge for `.json`, so these files are
  complete JSON objects.

## What a real Office client actually does

Measured 22/09/2026 against **Word for Mac and Word for Windows**, over plain HTTP, through the
`ms-word:` protocol handler, using the plugin's own DEBUG log and the Nuxeo audit trail. This replaces
guesswork; do not re-introduce assumptions that contradict it.

Verbs seen on both: `OPTIONS`, `HEAD`, `GET`, `LOCK`, `UNLOCK`, `PROPFIND`, `PUT`.
**Never seen on either: `PROPPATCH`, `MOVE`, `COPY`, `DELETE`, `MKCOL`.**

**Both send a direct `PUT`.** No `MOVE`, no temporary name, no `~$` resource. The transacted save does
not happen on this route, on either platform — see the next section before concluding anything from it.

The two clients are otherwise different, and the differences matter:

| | macOS | Windows |
|---|---|---|
| Requests to open | 8 | 11, in two phases |
| Before the `PUT` | `OPTIONS` ×2 | nothing, bare `PUT` |
| `LOCK` per session | 1 | 3 |
| `GET` per session | 1 | 2 |

**Windows opens in Protected View, and that shapes the trace.** Word locks, reads, `PROPFIND`s, locks
again, then `UNLOCK`s and stops. Ten to forty-five seconds later — someone clicking *Enable Editing* —
it locks again, re-reads, and only then edits. Two consequences:

- the document is **unlocked in Nuxeo during that pause**, so another user can take it. Not our doing;
- **Windows sends several `LOCK`s per session.** `doLock` is idempotent for the same owner: it returns
  200 and the same token without touching the lock. That is what makes Windows work, and it is why
  deriving the lock token from `(docId, owner)` rather than storing a random one pays off — there is no
  state to look up and a re-`LOCK` is free.

Also measured, on macOS only: a second user holding write permission is correctly refused
(`LockSecurityPolicy`); **one version per editing session, not per save** (the first save fires
`documentCheckedIn`, later ones do not); a file name with spaces round-trips through
`DavTarget.encode`/`decode`.

**`PROPFIND` only ever targets the file, never the collection.** The `Depth: 1` collection listing is
therefore unexercised too.

Still unverified: Excel, PowerPoint, older Office versions, HTTPS, a large document, the Basic fallback
against a real client, and **the File-then-Open route** — see below. The `README` carries the same
tables for users.

## Unobserved is not unreachable

`doMoveOrCopy`, `ScratchStore` and the shadow mechanism are taken by **no client measured so far**. Do
not conclude they are dead, and do not delete them. The measurement covers one route only: the
`ms-word:` protocol handler.

Reachable by other routes, in decreasing order of likelihood:

1. **File then Open, or a mapped drive.** On Windows this goes through the WebClient service, which
   presents the resource as a filesystem; Word then applies its ordinary filesystem save, and the
   redirector turns the renames into `MOVE`s. **The dialog advertises this route** — the
   `webdavDirectEdit.dialog.urlHint` message tells users to paste the address into File then Open.
2. **Excel and PowerPoint.** Microsoft's applications demonstrably differ: the 216-character URI cap is
   specific to Excel.
3. **Older Office versions.** The transacted sequence is documented for the 2010–2013 era, and
   `nuxeo-webdav`'s own comment describes all four steps with the precision of something observed.
4. A mounted drive from Finder or Explorer, or any other WebDAV client.

Because the path is reachable, **its test coverage matters more, not less**:
`TestOfficeEditEndpoint.shouldReplayTheOfficeSaveDance` is the only thing proving a route we advertise.
Do not weaken it. It remains a synthetic replay.

`OfficeEditServlet.noteTransactedSave` settles the question in the field rather than by further local
testing: the first time any client takes the path, on any deployment, one `INFO` line is written, once
per server lifetime. If that line never appears anywhere, the code can eventually be retired on
evidence rather than on assumption.

## Observability

A complete edit session used to leave **no trace whatsoever** in `server.log`, which made the feature
undiagnosable in the field. Now:

- **one `INFO` line per save**, in `commitToDocument`. Do not demote it back to `DEBUG`;
- **one `INFO` line, once per server lifetime**, from `noteTransactedSave`, the first time a client
  takes the `MOVE`/scratch path. That line is a measurement instrument, not a diagnostic: see
  "Unobserved is not unreachable";
- one `DEBUG` line per request at the top of `service()`, which is the only way to learn what verbs a
  given Office version sends — the platform enables no Tomcat access log, and the handlers only log
  failures.

**No log statement may carry a raw session token.** `safePath()` and `OfficeEditSessionStore.mask()`
exist for this, and `TestTokenMasking` enforces it. `ScratchStore` leaked the token through its entry
key once; do not reintroduce that by logging a key built from the token.

## Conventions

Follow the global Nuxeo rules: `jakarta.*` (including `jakarta.inject.Inject` in tests), Log4j2
`LogManager.getLogger()`, JUnit 4 + `FeaturesRunner`, `var`/records/pattern matching, 4-space indent,
K&R, ~120 columns, no wildcard imports, `Framework.getService()`, `@since 2025.1` on new public API.

Log4j2 forbids mixing plain values and `Supplier`s in one varargs call — use all of one kind.

## Notes

- **`AGENTS.md` is committed and pushed here**, unlike most Nuxeo Labs plugins. It is therefore public
  once the repository is. On top of that, `nuxeo-parent` copies every root-level `*.md` into the built
  jar (`doc-parent/AGENTS.md`), which then ships inside the marketplace zip. Two reasons, not one, to
  never put anything private in this file: no credentials, no customer data, no local paths, no
  document ids from a real repository.
