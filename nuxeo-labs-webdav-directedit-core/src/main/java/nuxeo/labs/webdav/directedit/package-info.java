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

/**
 * Opens a Nuxeo document in the matching Microsoft Office desktop application, over WebDAV.
 * <p>
 * The plugin builds an Office protocol handler URI ({@code ms-word:ofe|u|<webdav-url>}) pointing at the WebDAV
 * endpoint this plugin provides itself, in {@code nuxeo.labs.webdav.directedit.server}. Office then performs the whole
 * edit cycle: {@code OPTIONS}, {@code LOCK}, {@code GET}, and on save the {@code PUT}/{@code MOVE} sequence of its
 * transacted save.
 * <p>
 * Documents are addressed by id, not by repository path, so any document can be opened wherever it lives.
 *
 * @since 2025.1
 */
package nuxeo.labs.webdav.directedit;
