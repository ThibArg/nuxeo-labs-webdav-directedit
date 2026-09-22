# Nuxeo Labs WebDAV Direct Edit

> [!IMPORTANT]
> This plugin is **WORK IN PROGRESS** do not use as is for now. It will be forked to the nuxeo-sandbox repository once ready. For now, we use GitHub mainly as a backup.

Adds an **Open in Microsoft Office** button to the Nuxeo Web UI. One click opens the current document
in Word, Excel or PowerPoint on the user's desktop. When the user saves from the Office application,
the changes go straight back to Nuxeo and update the document.

Works on both macOS and Windows, which support WebDAV natively.

## How it works

The plugin provides its **own WebDAV endpoint**, at `/nuxeo/site/officeedit`. It does not use, and does
not require, the `nuxeo-webdav` addon.

That endpoint is deliberately **not** a general purpose WebDAV server. It implements only the part of
RFC 4918 that Microsoft Office exercises, over a URL space that is two levels deep:

```
/nuxeo/site/officeedit/<sessionToken>/                  a collection holding exactly one file
/nuxeo/site/officeedit/<sessionToken>/<fileName>        the document's main file
```

The first segment is an opaque **edit session token**, minted when the user clicks the button. It
resolves server-side to exactly one document and one user — so it addresses the document *and*
authenticates the request. Addressing this way, rather than by repository path, is what makes the
feature work for *any* document, and what the rest of the design follows from.

The edit cycle itself is driven entirely by Office:

```
Web UI  --  ms-word:ofe|u|https://host/nuxeo/site/officeedit/<token>/Report.docx  -->  Word
                                                                                       |
Word  --  OPTIONS, PROPFIND, LOCK, GET  -->  /nuxeo/site/officeedit/...                |
                                                                                       |
user edits, then saves                                                                 |
                                                                                       |
Word  --  PUT  -->  /nuxeo/site/officeedit/...                     -->  Nuxeo document updated
                                                                                       |
Word  --  UNLOCK  -->  /nuxeo/site/officeedit/...                   -->  Nuxeo lock released
```

### One file, and nothing else

The endpoint enforces a single rule: **only the canonical file name maps to the Nuxeo document. Every
other name in the collection is scratch.**

Scratch files live in a transient store and expire on their own. That is how the temporary files Office
creates while it saves — `~$Report.docx`, `A1B2C3D4.tmp` — are handled without ever becoming Nuxeo
documents.

Everything else follows from that rule:

- the endpoint can never create, rename, trash or delete a document;
- `DELETE` on the document's own file is refused with `403`;
- `MKCOL` is refused with `405`;
- there is nothing to browse, and nothing is ever enumerated.

## Requirements

| | |
|---|---|
| Nuxeo | LTS 2025, 2025.24 or later |
| Addons | `nuxeo-web-ui` |
| Client | Microsoft Word, Excel or PowerPoint installed on the user's desktop |
| Transport | **HTTPS strongly recommended**, see Platform notes below |

## Installation

```bash
nuxeoctl mp-install nuxeo-labs-webdav-directedit-package-<version>.zip
```

## When the button appears

The button is shown only when all of the following hold:

- the document has a file in `file:content`;
- that file's mime type **or** its file name extension matches a Microsoft Office format;
- the user has the `Write` permission;
- the document is not a proxy, a version, or trashed.

Clicking then runs a server-side check that is authoritative, and reports a clear message when the
document cannot be opened.

## Configuration

All properties can be set in `nuxeo.conf`. Those prefixed with `org.nuxeo.web.ui.` are read by both the
browser and the server, so the two can never disagree about which files are Office documents.

| Property | Default | Purpose |
|---|---|---|
| `org.nuxeo.web.ui.webdavDirectEdit.enabled` | `true` | Turns the whole feature off |
| `org.nuxeo.web.ui.webdavDirectEdit.mainBlobXPath` | `file:content` | The blob the endpoint exposes |
| `org.nuxeo.web.ui.webdavDirectEdit.word.extensions` | `doc,docx,docm,dot,dotx,dotm,rtf` | |
| `org.nuxeo.web.ui.webdavDirectEdit.excel.extensions` | `xls,xlsx,xlsm,xlsb,xlt,xltx,xltm,csv` | |
| `org.nuxeo.web.ui.webdavDirectEdit.powerpoint.extensions` | `ppt,pptx,pptm,pps,ppsx,ppsm,pot,potx,potm` | |
| `org.nuxeo.web.ui.webdavDirectEdit.<app>.mimeTypes` | see `webdav-directedit-properties.xml` | |
| `org.nuxeo.web.ui.webdavDirectEdit.showCredentialsHelp` | `true` | Shows the sign-in help section in the dialog |
| `org.nuxeo.web.ui.webdavDirectEdit.warnOnInsecureUrl` | `true` | Warns when the address is not HTTPS |
| `nuxeo.labs.webdavDirectEdit.baseUrl` | *empty* | Forces the base URL of the WebDAV addresses. Server-side only |
| `nuxeo.labs.webdavDirectEdit.tokenAuth.enabled` | `true` | Accepts a Nuxeo token as the WebDAV password. Server-side only |
| `nuxeo.labs.webdavDirectEdit.capabilityUrl.enabled` | `true` | The URL authenticates the request, so Office never prompts. Server-side only |
| `nuxeo.labs.webdavDirectEdit.capabilityUrl.ttlMinutes` | `480` | How long the URL keeps **authenticating**, in idle minutes. Server-side only |
| `nuxeo.labs.webdavDirectEdit.session.ttlMinutes` | `43200` | **Absolute** lifetime of an address, from issue. Never refreshed. Server-side only |
| `nuxeo.labs.webdavDirectEdit.maxUploadSizeMB` | `512` | Largest accepted `PUT`, hence the largest editable document. Server-side only |

`nuxeo.labs.webdavDirectEdit.baseUrl` is the one to set when Nuxeo is reached over plain HTTP but a
HTTPS entry point exists, since Office on Windows needs HTTPS. When it is empty, `nuxeo.url` is used,
unless it still points at localhost, in which case the address the browser is using is taken instead.

## Authentication, and single sign-on

**Microsoft Office cannot do SSO.** It drives WebDAV with its own HTTP stack: no browser, no way to
follow an identity provider redirect, and support for only the HTTP authentication schemes — Basic and
Digest. This is a Microsoft constraint and it applies to every WebDAV integration, not just this one.

The plugin deals with it in two layers.

### 1. The edit session token in the URL (default, no prompt)

The address the button builds carries an edit session token, and that token authenticates the request.
Office is never asked for credentials, so an SSO-only deployment works with no user interaction at all.

The token is put in the **path**, not in the query string, because Office builds the parent collection,
the temporary file names and the `Destination` header by manipulating the path. A query string would be
dropped on the second step of a save.

It is a deliberately narrow credential:

- it grants access to **one document**, for the user it was issued to, and nothing else;
- it is meaningless anywhere but `/nuxeo/site/officeedit`;
- it stops authenticating after `capabilityUrl.ttlMinutes` of inactivity, 8 hours by default.

**Two lifetimes, and the difference matters.** An address goes on *designating* its document
(`session.ttlMinutes`, 30 days) long after it has stopped *authenticating* (`capabilityUrl.ttlMinutes`,
8 hours of inactivity). So an address reopened from Office's recent files the next day asks for
credentials and then opens the document. Were there a single lifetime, entering a correct password
would still yield a 404 and the Basic fallback below would be useless.

The 30 days are an **absolute ceiling measured from the moment the address was issued**, deliberately
not extended by use. An issued address is a bearer credential that nothing else can revoke, so it must
expire on its own even if it is polled continuously.

> **What you are trading.** The URL is a bearer credential. Anyone holding it can read and write *that
> one document*, as that user, until its authentication window lapses. It is visible in Office's
> recent-files list, and wherever URLs are logged upstream — typically a reverse proxy; a stock Nuxeo
> writes no access log. There is **no way to revoke an individual address** short of waiting for it to
> expire, which is why the absolute ceiling exists. Set
> `nuxeo.labs.webdavDirectEdit.capabilityUrl.enabled=false` to turn the mechanism off entirely and
> require a credentials prompt instead.

The same approach is what the platform's own WOPI module uses for Office Online, for the same reason.

### 2. The credentials prompt (fallback)

If the session has expired, or capability URLs are disabled, Office falls back to a normal Basic
challenge. The server answers `401` with `WWW-Authenticate` rather than redirecting to the login page —
the scoped authentication chain removes the OIDC and SAML plugins entirely for these URLs, so Office can
never be sent to an identity provider it cannot use.

At the prompt the user can enter:

- **their Nuxeo authentication token**, shown in the dialog's *Trouble signing in?* section. This is what
  makes the fallback usable under SSO, where the user has no password. Token lookup is confined to this
  endpoint, and a secret is only ever looked up as a token if it has the shape of one, so a real password
  is never handed to the token service.
- **their Nuxeo password**, for deployments with local accounts.

> **Security note.** The token in the *Trouble signing in?* section is a **full-account credential**:
> Nuxeo records the `rw` permission on a token but never enforces it as a scope. It is shown only when the
> user explicitly asks for it, it is cleared from the page when the dialog closes, and it can be revoked
> from the same dialog. The edit session token in the URL is strictly narrower and is the normal path.

### 3. Kerberos or NTLM at the reverse proxy

In an Active Directory environment, a reverse proxy configured for Negotiate authentication lets Windows
sign Office in transparently. This is a deployment-level setup, outside the scope of this plugin.

## Platform notes

### Windows

The Windows `WebClient` service refuses to send Basic credentials over a plain HTTP connection by
default. The relevant registry value is:

```
HKLM\SYSTEM\CurrentControlSet\Services\WebClient\Parameters\BasicAuthLevel
    0 = Basic authentication disabled
    1 = Basic authentication enabled for SSL shares only   (default)
    2 = Basic authentication enabled for SSL and non-SSL shares
```

**Use HTTPS.** Changing the registry on every workstation is not a reasonable alternative. The dialog
warns when the address it built is not HTTPS.

Office opens the file in **Protected View** first; the user has to click *Enable Editing*. This is
visible in the request log: Word locks the document, reads it, then **releases the lock** and waits.
Between 10 and 45 seconds elapsed in the sessions measured — the time it takes someone to click.

The consequence is worth knowing: **during that pause the document is unlocked in Nuxeo**, so another
user can take it. Nothing in the plugin can prevent this; it is the client that releases the lock.
Adding the Nuxeo host to the Trusted Sites zone removes the Protected View step, and with it the pause.

### macOS

macOS supports WebDAV natively and Office for Mac registers the `ms-word:`, `ms-excel:` and
`ms-powerpoint:` URL schemes. HTTPS is recommended here too.

### URL length

The Office URI scheme caps the address at **256 characters, and at 216 for Excel**. The address this
plugin builds is `<base URL>/site/officeedit/<36-character token>/<file name>`, which leaves ample room
in practice, but a very long host name combined with a very long file name could reach the limit. The
session token replaces the document id rather than being added to it, so carrying the credential in the
URL costs nothing against this budget.

## Demonstrations and local testing

Both authentication paths are supported, and which one you get depends on whether the address is still
within its authentication window. On a demonstration machine the thing to avoid is being interrupted by
a credentials prompt you cannot answer.

### What works where

The only awkward case comes from Windows, not from this plugin. The `WebClient` service refuses to
**send** Basic credentials over an unencrypted connection when `BasicAuthLevel` is 1, its default.

| | HTTP | HTTPS |
|---|---|---|
| **macOS** — address still authenticates, no prompt | works | works |
| **macOS** — prompt, password or token | works | works |
| **Windows** — address still authenticates, no prompt | works | works |
| **Windows** — prompt, password or token | **blocked** by `BasicAuthLevel` | works |

Since no credentials are sent while the address authenticates, plain HTTP is fine on Windows — right up
until the window lapses. Hence the longer window below.

### Demonstration machine over HTTP

Paste into `nuxeo.conf`:

```properties
# --- Nuxeo Labs WebDAV Direct Edit: demo machine over plain HTTP ---
# Authentication window raised to a week, so no prompt interrupts a demo
nuxeo.labs.webdavDirectEdit.capabilityUrl.ttlMinutes=10080
# The HTTPS warning is expected locally
org.nuxeo.web.ui.webdavDirectEdit.warnOnInsecureUrl=false
```

### Exercising the Basic path on purpose

```properties
# The URL only designates the document: Office asks for credentials
nuxeo.labs.webdavDirectEdit.capabilityUrl.enabled=false
```

Sign in with a Nuxeo user name and either their password, or the token from the dialog's
*Trouble signing in?* section. On Windows this requires HTTPS, or `BasicAuthLevel` set to 2 in
`HKLM\SYSTEM\CurrentControlSet\Services\WebClient\Parameters`.

Note that an address is personal: opening one issued to another user, while signed in as yourself,
returns `403` rather than the document.

### Reading the log

One `INFO` line is written per save, so a working installation is visible without any configuration:

```
INFO  [OfficeEditServlet] Office edit: updated main blob of document 1a2b3c4d-… (Report.docx, 24576 bytes)
```

For anything finer, raise this plugin's own logger to `DEBUG`. Add one line inside `<Loggers>` in
`log4j2.xml`; it covers every class in the plugin, and nothing else:

```xml
<Logger name="nuxeo.labs.webdav.directedit" level="debug" />
```

You then get one line per HTTP request, with the method and the path, plus a line per authentication.
Session tokens are masked everywhere, so a DEBUG log is safe to paste into a ticket.

Two things are worth watching:

- `WARN  Unsupported WebDAV method ... on ...` — a verb this endpoint does not implement, which is the
  most likely cause if Office behaves oddly;
- in a reverse proxy access log, any request Office makes **outside** `/nuxeo/site/officeedit/`. The
  servlet only answers under that path.

Note that a stock Nuxeo enables no Tomcat access log, so the address, and therefore the session token,
does not reach any Nuxeo log file. It will appear wherever URLs are logged upstream — typically a
reverse proxy.

## Validation status

Honest reporting of what has actually been exercised against real Office clients, as opposed to the
automated test suite. Measured on 22 September 2026, over plain HTTP, through the `ms-word:` protocol
handler — that is, by clicking the button.

The two clients do not behave identically, so they are reported separately.

| | macOS, Word | Windows, Word |
|---|---|---|
| Open, edit, save, close | works | works |
| Credentials prompt | **none** | **none** |
| How it saves | direct `PUT` | direct `PUT` |
| Temporary files created | none | none |
| `PROPPATCH` sent | no | no |
| Requests to open | 8 | 11, in two phases |
| `LOCK` per session | 1 | 3 |
| `GET` per session | 1 | 2 |
| Protected View pause | no | **yes**, 10 to 45 s |

Verbs actually seen, on both: `OPTIONS`, `HEAD`, `GET`, `LOCK`, `UNLOCK`, `PROPFIND`, `PUT`.
Never seen, on either: `PROPPATCH`, `MOVE`, `COPY`, `DELETE`, `MKCOL`.

Measured once, on macOS:

- a second user holding write permission **cannot modify** the document while it is open;
- **one version per editing session**, not per save;
- a file name containing spaces round-trips correctly.

**Not yet verified:**

- **opening the address with File then Open**, or from a mapped drive, rather than with the button.
  On Windows that route goes through the WebClient service, which is likely to produce a different
  request sequence — see below. The dialog advertises this route, so it matters;
- Excel and PowerPoint. Microsoft's applications demonstrably differ: the 216-character URI limit is
  specific to Excel;
- older Office versions;
- HTTPS;
- the credentials-prompt fallback against a real Office client, once the authentication window has
  lapsed — covered by automated tests only;
- a large document.

### About the transacted save

Word is documented, and widely reported, to save by writing a temporary file, renaming the original
aside, renaming the temporary into place and deleting the leftover. The plugin implements that
sequence, and the automated test suite exercises it.

**Neither client measured here does it.** Both send a plain `PUT`. That does not make the code dead:
the protocol handler is only one way in, and the routes listed above as unverified are precisely the
ones most likely to use it. **Unobserved is not unreachable.**

Rather than keep guessing, the plugin reports it. The first time any client takes that path, on any
deployment, one line appears in the log:

```
INFO  Office edit: a client used the transacted save path (MOVE on Report.docx). ...
```

If you ever see it, the sequence is in use somewhere, and the answer is settled.

## Limitations

- **Main file only.** The endpoint reads and writes only the blob returned by the document's
  `BlobHolder`, that is `file:content` for File, Picture, Video and Audio. Attachments stored in
  `files:files` are not exposed, and the plugin refuses them rather than let Office edit one file and
  overwrite another. This is why the button is a document action and not a blob action.
- **Maximum document size.** A single `PUT` is capped by `maxUploadSizeMB`, 512 MB by default, which is
  therefore also the largest document editable through this endpoint.
- **An issued address cannot be revoked individually.** It stops authenticating after its idle window
  and dies at the absolute ceiling, but there is no "revoke this address" action.
- **On Windows the document is briefly unlocked** while Protected View waits for *Enable Editing*.
- **Full download and full upload.** WebDAV has no partial write: every save uploads the entire file.
  This is a property of the protocol, not of this plugin. The server side streams in both directions
  and supports HTTP `Range`, so nothing is ever held in memory.
- **One version per editing session.** Nuxeo's versioning policy creates a version on the first save
  after the document is opened, not on every save, so frequent saving does not pile up versions.
- **Stale locks.** Office locks the document while it is open. If Office crashes, the Nuxeo lock
  remains; it can be cleared with the standard Lock toggle button in the Web UI.
- **Single repository.** Documents are resolved in the default repository.
- **Addresses expire.** After `session.ttlMinutes` of inactivity, 30 days by default, an address kept in
  Office's recent files stops working entirely and the user clicks the button again. Between the
  authentication window and that limit, it works but asks for credentials.
- **Do not install the `nuxeo-webdav` addon alongside this plugin.** It contributes an authentication
  chain selected on the `User-Agent` header with no URL pattern at all, matching exactly the user agents
  Word and Excel send. It can take precedence over this plugin's own chain and break its authentication.
  The plugin logs a warning at startup when it detects the addon.

## Automation operations

| Operation | Input | Output | Purpose |
|---|---|---|---|
| `WebDavDirectEdit.GetUrl` | Document | JSON Blob | `{available, app, fileName, davPath, davUrl, protocolUrl, secure, reason}` |
| `WebDavDirectEdit.GetCredentials` | void | JSON Blob | `{username, token, enabled}` |
| `WebDavDirectEdit.RevokeCredentials` | void | void | Revokes the caller's own token |

When `available` is `false`, `reason` is one of `disabled`, `noBlob`, `notMainBlob`, `notOfficeFile`,
`notEditable`, `noWritePermission`.

## Build

```bash
mvn clean install
```

The marketplace package is produced at
`nuxeo-labs-webdav-directedit-package/target/nuxeo-labs-webdav-directedit-package-<version>.zip`.

## Support

These features are not part of the Nuxeo Production platform. They are not supported, and are provided
without warranty of any kind. Use them at your own risk.

## License

[Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0)

## About Nuxeo

[Nuxeo](https://www.hyland.com/en/products/nuxeo-platform), developed by [Hyland](https://www.hyland.com),
is a Content Services platform. It provides an easy-to-use, highly customizable and extensible
architecture for building content-centric applications.
