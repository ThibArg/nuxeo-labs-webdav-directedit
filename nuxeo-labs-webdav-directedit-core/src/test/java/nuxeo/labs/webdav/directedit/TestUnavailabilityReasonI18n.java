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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Keeps the translations and {@link UnavailabilityReason} in step.
 * <p>
 * The Web UI resolves a reason id to the key {@code webdavDirectEdit.error.<id>} and falls back to a generic message
 * when the key is missing, so drift between the two is silent. Adding or removing a reason without touching the
 * translations fails here instead.
 *
 * @since 2025.1
 */
public class TestUnavailabilityReasonI18n {

    protected static final String I18N_PATH = "web/nuxeo.war/ui/i18n/";

    protected static final String KEY_PREFIX = "webdavDirectEdit.error.";

    @Test
    public void shouldTranslateEveryReasonInEveryLocale() throws IOException {
        for (String file : new String[] { "messages.json", "messages-fr.json" }) {
            JsonNode messages = load(file);
            var missing = new ArrayList<String>();
            for (UnavailabilityReason reason : UnavailabilityReason.values()) {
                if (!messages.has(KEY_PREFIX + reason.getId())) {
                    missing.add(reason.getId());
                }
            }
            assertTrue(file + " is missing translations for " + missing, missing.isEmpty());
        }
    }

    @Test
    public void shouldNotKeepTranslationsForReasonsThatNoLongerExist() throws IOException {
        var known = new ArrayList<String>();
        for (UnavailabilityReason reason : UnavailabilityReason.values()) {
            known.add(reason.getId());
        }
        // The Web UI uses these two itself, they map to no reason.
        known.add("title");
        known.add("unknown");

        for (String file : new String[] { "messages.json", "messages-fr.json" }) {
            JsonNode messages = load(file);
            var orphans = new ArrayList<String>();
            messages.fieldNames().forEachRemaining(key -> {
                if (key.startsWith(KEY_PREFIX) && !known.contains(key.substring(KEY_PREFIX.length()))) {
                    orphans.add(key);
                }
            });
            assertTrue(file + " still translates removed reasons " + orphans, orphans.isEmpty());
        }
    }

    protected JsonNode load(String fileName) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(I18N_PATH + fileName)) {
            assertNotNull("could not find " + I18N_PATH + fileName, in);
            return new ObjectMapper().readTree(in);
        }
    }
}
