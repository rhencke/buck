/*
 * Copyright 2012-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.android;

import com.android.common.annotations.NonNull;
import com.android.manifmerger.ICallback;
import com.android.manifmerger.IMergerLog;
import com.android.manifmerger.ManifestMerger;
import com.android.manifmerger.MergerLog;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Charsets;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.io.Files;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public class GenerateManifestStep implements Step {

  private static final int BASE_SDK_LEVEL = 1;

  private final ProjectFilesystem filesystem;
  private final Path skeletonManifestPath;
  private final ImmutableSet<Path> libraryManifestPaths;
  private Path outManifestPath;

  public GenerateManifestStep(
      ProjectFilesystem filesystem,
      Path skeletonManifestPath,
      ImmutableSet<Path> libraryManifestPaths,
      Path outManifestPath) {
    this.filesystem = filesystem;
    this.skeletonManifestPath = skeletonManifestPath;
    this.libraryManifestPaths = ImmutableSet.copyOf(libraryManifestPaths);
    this.outManifestPath = outManifestPath;
  }

  @Override
  public int execute(ExecutionContext context) {

    if (skeletonManifestPath.getNameCount() == 0) {
      throw new HumanReadableException("Skeleton manifest filepath is missing");
    }

    if (outManifestPath.getNameCount() == 0) {
      throw new HumanReadableException("Output Manifest filepath is missing");
    }

    outManifestPath = filesystem.resolve(outManifestPath);
    try {
      Files.createParentDirs(outManifestPath.toFile());
    } catch (IOException e) {
      e.printStackTrace(context.getStdErr());
      return 1;
    }

    List<File> libraryManifestFiles = Lists.newArrayList();

    for (Path path : libraryManifestPaths) {
      Path manifestPath = filesystem.getPathForRelativeExistingPath(path).toAbsolutePath();
      libraryManifestFiles.add(manifestPath.toFile());
    }

    File skeletonManifestFile =
        filesystem.getPathForRelativeExistingPath(skeletonManifestPath).toAbsolutePath().toFile();

    ICallback callback = new ICallback() {
      @Override
      public int queryCodenameApiLevel(@NonNull String codename) {
        return BASE_SDK_LEVEL;
      }
    };

    IMergerLog log = MergerLog.wrapSdkLog(new BuckEventAndroidLogger(context.getBuckEventBus()));

    ManifestMerger merger = new ManifestMerger(log, callback);

    File outManifestFile = outManifestPath.toFile();
    if (!merger.process(
        outManifestFile,
        skeletonManifestFile,
        Iterables.toArray(libraryManifestFiles, File.class))) {
      throw new HumanReadableException("Error generating manifest file");
    }

    if (context.getPlatform() == Platform.WINDOWS) {
      // Convert line endings to Lf on Windows.
      try {
        String xmlText = Files.toString(outManifestFile, Charsets.UTF_8);
        xmlText = xmlText.replace("\r\n", "\n");
        Files.write(xmlText.getBytes(Charsets.UTF_8), outManifestFile);
      } catch (IOException e) {
        throw new HumanReadableException("Error converting line endings of manifest file");
      }
    }

    return 0;
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return String.format("generate-manifest %s", skeletonManifestPath);
  }

  @Override
  public String getShortName() {
    return "generate_manifest";
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof GenerateManifestStep)) {
      return false;
    }

    GenerateManifestStep that = (GenerateManifestStep) obj;
    return Objects.equal(this.skeletonManifestPath, that.skeletonManifestPath) &&
        Objects.equal(this.libraryManifestPaths, that.libraryManifestPaths) &&
        Objects.equal(this.outManifestPath, that.outManifestPath);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(
        skeletonManifestPath,
        libraryManifestPaths,
        outManifestPath);
  }
}
