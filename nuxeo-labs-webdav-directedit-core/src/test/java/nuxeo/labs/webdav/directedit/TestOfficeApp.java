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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pure unit tests on the Office application enum: no Nuxeo runtime involved.
 *
 * @since 2025.1
 */
public class TestOfficeApp {

    @Test
    public void shouldExposeTheOfficeUriSchemes() {
        assertEquals("ms-word:", OfficeApp.WORD.getUriScheme());
        assertEquals("ms-excel:", OfficeApp.EXCEL.getUriScheme());
        assertEquals("ms-powerpoint:", OfficeApp.POWERPOINT.getUriScheme());
    }

    @Test
    public void shouldBuildOpenForEditUri() {
        String uri = OfficeApp.WORD.buildOpenForEditUri("https://host/nuxeo/site/officeedit/id/Report.docx");
        assertEquals("ms-word:ofe|u|https://host/nuxeo/site/officeedit/id/Report.docx", uri);
    }

    @Test
    public void shouldKeepPipesUnencodedInTheUri() {
        // Office expects the literal pipe form; browsers percent-encode it on navigation, which Office also accepts.
        String uri = OfficeApp.EXCEL.buildOpenForEditUri("https://host/nuxeo/site/officeedit/id/Budget.xlsx");
        assertTrue(uri.startsWith("ms-excel:ofe|u|https://"));
        assertFalse(uri.contains("%7C"));
    }

    @Test
    public void shouldResolveApplicationById() {
        assertEquals(OfficeApp.WORD, OfficeApp.byId("word").orElseThrow());
        assertEquals(OfficeApp.EXCEL, OfficeApp.byId("EXCEL").orElseThrow());
        assertEquals(OfficeApp.POWERPOINT, OfficeApp.byId("  powerpoint ").orElseThrow());
    }

    @Test
    public void shouldNotResolveUnknownId() {
        assertTrue(OfficeApp.byId("visio").isEmpty());
        assertTrue(OfficeApp.byId("").isEmpty());
        assertTrue(OfficeApp.byId(null).isEmpty());
    }
}
