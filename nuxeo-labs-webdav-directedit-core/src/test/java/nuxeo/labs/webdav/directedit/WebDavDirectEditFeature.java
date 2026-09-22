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

import org.nuxeo.ecm.automation.test.AutomationFeature;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.RunnerFeature;

/**
 * Test feature deploying this plugin and what its service needs.
 * <p>
 * No servlet container is started here: these tests exercise the service and the Automation operations. The WebDAV
 * endpoint itself is covered over real HTTP by {@link OfficeEditServerFeature}.
 *
 * @since 2025.1
 */
@Features(AutomationFeature.class)
@Deploy("org.nuxeo.ecm.platform.types")
@Deploy("org.nuxeo.ecm.platform.filemanager")
@Deploy("org.nuxeo.ecm.platform.login.token")
@Deploy("nuxeo.labs.webdavdirectedit.nuxeo-labs-webdav-directedit-core")
public class WebDavDirectEditFeature implements RunnerFeature {

}
