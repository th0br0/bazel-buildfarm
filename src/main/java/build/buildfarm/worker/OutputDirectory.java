// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package build.buildfarm.worker;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

public class OutputDirectory {
  private final Map<String, OutputDirectory> children;

  private static final OutputDirectory defaultInstance = new OutputDirectory(ImmutableMap.<String, OutputDirectory>of());
  private static OutputDirectory getDefaultInstance() {
    return defaultInstance;
  }

  private static OutputDirectory getRecursiveInstance() {
    return new OutputDirectory(null) {
      @Override
      public OutputDirectory getChild(String directoryName) {
        return this;
      }
    };
  }

  private OutputDirectory(Map<String, OutputDirectory> children) {
    this.children = children;
  }

  private final static class OutputDirectoryEntry implements Comparable<OutputDirectoryEntry> {
    public final String outputDirectory;
    public final boolean isRecursive;

    OutputDirectoryEntry(String outputDirectory, boolean isRecursive) {
      this.outputDirectory = outputDirectory;
      this.isRecursive = isRecursive;
    }

    @Override
    public int compareTo(OutputDirectoryEntry other) {
      return outputDirectory.compareTo(other.outputDirectory);
    }
  }

  public static OutputDirectory parse(Iterable<String> outputFiles, Iterable<String> outputDirs) {
    return parseDirectories(Iterables.concat(
        Iterables.transform(
            Iterables.filter(outputFiles, (file) -> file.contains("/")),
            (file) -> new OutputDirectoryEntry("/" + file.substring(0, file.lastIndexOf('/') + 1), false)),
        Iterables.transform(
            outputDirs,
            (dir) -> new OutputDirectoryEntry(dir.isEmpty() ? "/" : "/" + dir + "/", true))));
  }

  private static class Builder {
    Map<String, Builder> children = new HashMap<>();
    boolean isRecursive = false;

    public Builder addChild(String name) {
      Preconditions.checkState(!isRecursive, "recursive builder already has all children");
      Builder childBuilder = new Builder();
      Preconditions.checkState(!children.containsKey(name), "Duplicate Output Directory Name");
      children.put(name, childBuilder);
      return childBuilder;
    }

    public void setIsRecursive(boolean isRecursive) {
      this.isRecursive = isRecursive;
    }

    public OutputDirectory build() {
      if (isRecursive) {
        return OutputDirectory.getRecursiveInstance();
      }
      if (children.isEmpty()) {
        return OutputDirectory.getDefaultInstance();
      }
      return new OutputDirectory(ImmutableMap.copyOf(Maps.transformValues(children, (v) -> v.build())));
    }
  };

  private static OutputDirectory parseDirectories(Iterable<OutputDirectoryEntry> outputDirs) {
    Builder builder = new Builder();
    Stack<Builder> stack = new Stack<>();

    List<OutputDirectoryEntry> sortedOutputDirs = new ArrayList<>();
    Iterables.addAll(sortedOutputDirs, outputDirs);
    Collections.sort(sortedOutputDirs);

    Builder currentBuilder = builder;
    String prefix = "/";
    for (OutputDirectoryEntry entry : sortedOutputDirs) {
      String outputDir = entry.outputDirectory;
      while (!outputDir.startsWith(prefix)) {
        currentBuilder = stack.pop();
        int upPathSeparatorIndex = prefix.lastIndexOf('/', prefix.length() - 2);
        prefix = prefix.substring(0, upPathSeparatorIndex + 1);
      }
      if (outputDir.length() == prefix.length()) {
        currentBuilder.setIsRecursive(entry.isRecursive);
        continue;
      }
      String prefixedFile = outputDir.substring(prefix.length(), outputDir.length());
      while (prefixedFile.length() > 0) {
        int separatorIndex = prefixedFile.indexOf('/');
        if (separatorIndex == 0) {
          throw new IllegalArgumentException("double separator in output directory");
        }

        String directoryName = prefixedFile.substring(0, separatorIndex);
        prefix += directoryName + '/';
        prefixedFile = prefixedFile.substring(separatorIndex + 1);
        stack.push(currentBuilder);
        currentBuilder = currentBuilder.addChild(directoryName);
      }
      currentBuilder.setIsRecursive(entry.isRecursive);
    }

    return builder.build();
  }

  public void stamp(Path root) throws IOException {
    if (children == null || children.isEmpty()) {
      Files.createDirectories(root);
    } else {
      for (Map.Entry<String, OutputDirectory> entry : children.entrySet()) {
        entry.getValue().stamp(root.resolve(entry.getKey()));
      }
    }
  }

  public OutputDirectory getChild(String directoryName) {
    return children.get(directoryName);
  }

  public boolean isLeaf() {
    return children != null && children.isEmpty();
  }
}
