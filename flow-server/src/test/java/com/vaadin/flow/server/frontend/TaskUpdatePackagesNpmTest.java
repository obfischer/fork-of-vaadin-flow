/*
 * Copyright 2000-2021 Vaadin Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.vaadin.flow.server.frontend;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import net.jcip.annotations.NotThreadSafe;
import org.apache.commons.io.FileUtils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

import com.vaadin.flow.server.frontend.scanner.ClassFinder;
import com.vaadin.flow.server.frontend.scanner.FrontendDependencies;

import elemental.json.Json;
import elemental.json.JsonObject;
import elemental.json.JsonValue;

import static com.vaadin.flow.server.Constants.PACKAGE_JSON;
import static com.vaadin.flow.server.frontend.NodeUpdater.DEPENDENCIES;

@NotThreadSafe
public class TaskUpdatePackagesNpmTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private File npmFolder;

    private ClassFinder finder;

    private File generatedPath;

    @Rule
    public ExpectedException exception = ExpectedException.none();

    @Before
    public void setUp() throws IOException {
        npmFolder = temporaryFolder.newFolder();
        generatedPath = new File(npmFolder, "generated");
        generatedPath.mkdir();
        finder = Mockito.mock(ClassFinder.class);
    }

    @Test
    public void passUnorderedApplicationDependenciesAndReadUnorderedPackageJson_resultingPackageJsonIsOrdered()
            throws IOException {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        map.put("foo", "bar");
        // "bar" is lexicographically before the "foo" but in the linked hash
        // map it's set after
        map.put("baz", "foobar");

        JsonObject packageJson = getOrCreatePackageJson();
        JsonObject dependencies = packageJson.getObject(DEPENDENCIES);
        // Json object preserve the order of keys
        dependencies.put("foo-pack", "bar");
        dependencies.put("baz-pack", "foobar");
        FileUtils.writeStringToFile(new File(npmFolder, PACKAGE_JSON),
                packageJson.toJson(), StandardCharsets.UTF_8);

        TaskUpdatePackages task = createTask(map);

        task.execute();

        // now read the package json file
        packageJson = getOrCreatePackageJson();

        checkOrder("", packageJson);
    }

    private void checkOrder(String path, JsonObject object) {
        String[] keys = object.keys();
        if (path.isEmpty()) {
            Assert.assertTrue("Keys in the package Json are not sorted",
                    isSorted(keys));
        } else {
            Assert.assertTrue(
                    "Keys for the object " + path
                            + " in the package Json are not sorted",
                    isSorted(keys));
        }
        for (String key : keys) {
            JsonValue value = object.get(key);
            if (value instanceof JsonObject) {
                checkOrder(path + "/" + key, (JsonObject) value);
            }
        }
    }

    private boolean isSorted(String[] array) {
        if (array.length < 2) {
            return true;
        }
        for (int i = 0; i < array.length - 1; i++) {
            if (array[i].compareTo(array[i + 1]) > 0) {
                return false;
            }
        }
        return true;
    }

    private TaskUpdatePackages createTask(
            Map<String, String> applicationDependencies) {
        return createTask(applicationDependencies, false);
    }

    private TaskUpdatePackages createTask(
            Map<String, String> applicationDependencies, boolean enablePnpm) {
        final FrontendDependencies frontendDependenciesScanner = Mockito
                .mock(FrontendDependencies.class);
        Mockito.when(frontendDependenciesScanner.getPackages())
                .thenReturn(applicationDependencies);
        return new TaskUpdatePackages(finder, frontendDependenciesScanner,
                npmFolder, generatedPath, false, enablePnpm) {
        };
    }

    private JsonObject getOrCreatePackageJson() throws IOException {
        File packageJson = new File(npmFolder, PACKAGE_JSON);
        if (packageJson.exists())
            return Json.parse(FileUtils.readFileToString(packageJson,
                    StandardCharsets.UTF_8));
        else {
            final JsonObject packageJsonJson = Json.createObject();
            packageJsonJson.put(DEPENDENCIES, Json.createObject());
            FileUtils.writeStringToFile(new File(npmFolder, PACKAGE_JSON),
                    packageJsonJson.toJson(), StandardCharsets.UTF_8);
            return packageJsonJson;
        }
    }

}
