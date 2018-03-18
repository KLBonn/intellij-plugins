// Copyright 2000-2018 JetBrains s.r.o.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package org.intellij.plugins.markdown.ui.split;

import com.intellij.openapi.fileEditor.AsyncFileEditorProvider;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

public class MarkdownEditorUtil {
  @NotNull
  public static AsyncFileEditorProvider.Builder getBuilderFromEditorProvider(@NotNull final FileEditorProvider provider,
                                                                             @NotNull final Project project,
                                                                             @NotNull final VirtualFile file) {
    if (provider instanceof AsyncFileEditorProvider) {
      return ((AsyncFileEditorProvider)provider).createEditorAsync(project, file);
    }
    else {
      return new AsyncFileEditorProvider.Builder() {
        @Override
        public FileEditor build() {
          return provider.createEditor(project, file);
        }
      };
    }
  }
}