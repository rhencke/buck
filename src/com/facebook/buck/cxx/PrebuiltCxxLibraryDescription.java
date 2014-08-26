/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.python.PythonPackageComponents;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.ConstructorArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.nio.file.Paths;

public class PrebuiltCxxLibraryDescription
    implements Description<PrebuiltCxxLibraryDescription.Arg> {

  public static final BuildRuleType TYPE = new BuildRuleType("prebuilt_cxx_library");

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> CxxLibrary createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      final A args) {

    final BuildTarget target = params.getBuildTarget();

    final boolean headerOnly = args.headerOnly.or(false);
    final boolean provided = args.provided.or(false);
    final boolean linkWhole = args.linkWhole.or(false);
    final String libDir = args.libDir.or("lib");
    final String libName = args.libName.or(target.getShortNameOnly());
    final String soname = args.soname.or(String.format("lib%s.so", libName));

    final Path sharedLibraryPath = target.getBasePath().resolve(libDir).resolve(
        String.format("lib%s.so", libName));
    final Path staticLibraryPath = target.getBasePath().resolve(libDir).resolve(
        String.format("lib%s.a", libName));

    Function<String, Path> fullPathFn = new Function<String, Path>() {
      @Override
      public Path apply(String input) {
        return target.getBasePath().resolve(input);
      }
    };

    // Resolve all the target-base-path-relative include paths to their full paths.
    final ImmutableList<Path> includeDirs = FluentIterable
        .from(args.includeDirs.or(ImmutableList.of("include")))
        .transform(fullPathFn)
        .toList();

    return new CxxLibrary(params) {

      @Override
      public CxxPreprocessorInput getCxxPreprocessorInput() {
        return new CxxPreprocessorInput(
            /* targets */ ImmutableSet.<BuildTarget>of(),
            /* cppflags */ ImmutableList.<String>of(),
            /* cxxppflags */ ImmutableList.<String>of(),
            /* includes */ ImmutableList.<Path>of(),
            // Just pass the include dirs as system includes.
            /* systemIncludes */ includeDirs);
      }

      @Override
      public NativeLinkableInput getNativeLinkableInput(Type type) {

        // We only want to link the whole archive if we're not doing a shared link AND
        // this library isn't available externally at runtime.
        boolean wholeArchive = linkWhole && !provided && type != Type.SHARED;

        // Used the shared library if the input params has requested it, or if the
        // library is externally available at runtime as a shared lib.
        Path library = provided || type == Type.SHARED ?
            sharedLibraryPath :
            staticLibraryPath;

        // Build the library path and linker arguments that we pass through the
        // {@link NativeLinkable} interface for linking.
        ImmutableList.Builder<Path> librariesBuilder = ImmutableList.builder();
        ImmutableList.Builder<String> linkerArgsBuilder = ImmutableList.builder();
        if (!headerOnly) {
          if (wholeArchive) {
            linkerArgsBuilder.add("--whole-archive");
          }
          librariesBuilder.add(library);
          linkerArgsBuilder.add(library.toString());
          if (wholeArchive) {
            linkerArgsBuilder.add("--no-whole-archive");
          }
        }
        final ImmutableList<Path> libraries = librariesBuilder.build();
        final ImmutableList<String> linkerArgs = linkerArgsBuilder.build();

        return new NativeLinkableInput(
            /* targets */ ImmutableSet.<BuildTarget>of(),
            /* inputs */ libraries,
            /* args */ linkerArgs);
      }

      @Override
      public PythonPackageComponents getPythonPackageComponents() {

        // Build up the shared library list to contribute to a python executable package.
        ImmutableMap.Builder<Path, SourcePath> nativeLibrariesBuilder = ImmutableMap.builder();
        if (!headerOnly && !provided) {
          nativeLibrariesBuilder.put(
              Paths.get(soname),
              new PathSourcePath(sharedLibraryPath));
        }
        ImmutableMap<Path, SourcePath> nativeLibraries = nativeLibrariesBuilder.build();

        return new PythonPackageComponents(
            /* modules */ ImmutableMap.<Path, SourcePath>of(),
            /* resources */ ImmutableMap.<Path, SourcePath>of(),
            nativeLibraries);
      }

    };
  }

  @SuppressFieldNotInitialized
  public static class Arg implements ConstructorArg {
    public Optional<ImmutableList<String>> includeDirs;
    public Optional<String> libName;
    public Optional<String> libDir;
    public Optional<Boolean> headerOnly;
    public Optional<Boolean> provided;
    public Optional<Boolean> linkWhole;
    public Optional<String> soname;
    public Optional<ImmutableSortedSet<BuildRule>> deps;
  }

}
