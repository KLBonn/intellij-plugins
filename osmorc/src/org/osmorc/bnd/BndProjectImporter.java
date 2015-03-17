/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.osmorc.bnd;

import aQute.bnd.build.Container;
import aQute.bnd.build.Project;
import aQute.bnd.build.Workspace;
import aQute.bnd.header.Attrs;
import com.intellij.compiler.CompilerConfiguration;
import com.intellij.compiler.impl.javaCompiler.javac.JavacConfiguration;
import com.intellij.facet.impl.FacetUtil;
import com.intellij.ide.highlighter.ModuleFileType;
import com.intellij.notification.NotificationDisplayType;
import com.intellij.notification.NotificationGroup;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.ModifiableModuleModel;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.StdModuleTypes;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.projectRoots.JavaSdk;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.projectRoots.SdkModificator;
import com.intellij.openapi.roots.*;
import com.intellij.openapi.roots.impl.ModifiableModelCommitter;
import com.intellij.openapi.roots.impl.libraries.ProjectLibraryTable;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.roots.libraries.LibraryTable;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.io.FileUtilRt;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.util.PathUtil;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jps.model.java.compiler.JpsJavaCompilerOptions;
import org.jetbrains.osgi.jps.model.ManifestGenerationMode;
import org.jetbrains.osgi.jps.model.OutputPathType;
import org.osmorc.facet.OsmorcFacet;
import org.osmorc.facet.OsmorcFacetConfiguration;
import org.osmorc.facet.OsmorcFacetType;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.osmorc.i18n.OsmorcBundle.message;

public class BndProjectImporter {
  public static final String CNF_DIR = Workspace.CNFDIR;
  public static final String BUILD_FILE = Workspace.BUILDFILE;
  public static final String BND_FILE = Project.BNDFILE;
  public static final String BND_LIB_PREFIX = "bnd:";

  public static final NotificationGroup NOTIFICATIONS = new NotificationGroup("osgi.bnd", NotificationDisplayType.STICKY_BALLOON, true);

  private static final Logger LOG = Logger.getInstance(BndProjectImporter.class);

  private static final Key<Workspace> BND_WORKSPACE_KEY = Key.create("bnd.workspace.key");

  private static final String JAVAC_SOURCE = "javac.source";
  private static final String JAVAC_TARGET = "javac.target";
  private static final String SRC_ROOT = "OSGI-OPT/src";
  private static final String JDK_DEPENDENCY = "ee.j2se";

  private final com.intellij.openapi.project.Project myProject;
  private final Workspace myWorkspace;
  private final Collection<Project> myProjects;
  private final Map<String, String> mySourcesMap = ContainerUtil.newTroveMap(FileUtil.PATH_HASHING_STRATEGY);
  private final boolean myUnitTestMode = ApplicationManager.getApplication().isUnitTestMode();

  public BndProjectImporter(@NotNull com.intellij.openapi.project.Project project,
                            @NotNull Workspace workspace,
                            @NotNull Collection<Project> toImport) {
    myProject = project;
    myWorkspace = workspace;
    myProjects = toImport;
  }

  @NotNull
  public Module createRootModule(@NotNull ModifiableModuleModel model) {
    String rootDir = myProject.getBasePath();
    String imlPath = rootDir + File.separator + myProject.getName() + ModuleFileType.DOT_DEFAULT_EXTENSION;
    Module module = model.newModule(imlPath, StdModuleTypes.JAVA.getId());
    ModuleRootModificationUtil.addContentRoot(module, rootDir);
    ModuleRootModificationUtil.setSdkInherited(module);
    return module;
  }

  public void setupProject() {
    LanguageLevel sourceLevel = LanguageLevel.parse(myWorkspace.getProperty(JAVAC_SOURCE));
    if (sourceLevel == null) sourceLevel = LanguageLevel.JDK_1_6;
    LanguageLevelProjectExtension.getInstance(myProject).setLanguageLevel(sourceLevel);

    // compilation options (see Project#getCommonJavac())
    JpsJavaCompilerOptions javacOptions = JavacConfiguration.getOptions(myProject, JavacConfiguration.class);
    javacOptions.DEBUGGING_INFO = booleanProperty(myWorkspace.getProperty("javac.debug", "true"));
    javacOptions.DEPRECATION = booleanProperty(myWorkspace.getProperty("java.deprecation"));
    javacOptions.ADDITIONAL_OPTIONS_STRING = myWorkspace.getProperty("java.options", "");
  }

  public void resolve() {
    if (!myUnitTestMode) {
      new Task.Backgroundable(myProject, message("bnd.import.resolve.task"), true) {
        @Override
        public void run(@NotNull ProgressIndicator indicator) {
          if (resolve(indicator)) {
            ApplicationManager.getApplication().invokeLater(new Runnable() {
              @Override
              public void run() {
                createProjectStructure();
              }
            }, ModalityState.NON_MODAL);
          }
        }
      }.queue();
    }
    else {
      resolve(null);
      createProjectStructure();
    }
  }

  private boolean resolve(@Nullable ProgressIndicator indicator) {
    int progress = 0;
    for (Project project : myProjects) {
      LOG.info("resolving: " + project.getBase());

      if (indicator != null) {
        indicator.checkCanceled();
        indicator.setText(project.getName());
      }

      try {
        project.prepare();
      }
      catch (Exception e) {
        checkErrors(project, e);
        return false;
      }

      List<String> errors = project.getErrors();
      if (errors != null && !errors.isEmpty()) {
        checkErrors(project, errors);
      }

      checkWarnings(project, project.getWarnings());

      findSources(project);

      if (indicator != null) {
        indicator.setFraction((double)(++progress) / myProjects.size());
      }
    }

    return true;
  }

  private void findSources(Project project) {
    try {
      findSources(project.getBootclasspath());
      findSources(project.getBuildpath());
      findSources(project.getTestpath());
    }
    catch (Exception ignored) { }
  }

  private void findSources(Collection<Container> classpath) {
    for (Container dependency : classpath) {
      Container.TYPE type = dependency.getType();
      if (type == Container.TYPE.REPO || type == Container.TYPE.EXTERNAL) {
        File file = dependency.getFile();
        if (file.isFile() && FileUtilRt.extensionEquals(file.getName(), "jar")) {
          String path = file.getPath();
          if (!mySourcesMap.containsKey(path)) {
            try {
              ZipFile zipFile = new ZipFile(file);
              try {
                ZipEntry srcRoot = zipFile.getEntry(SRC_ROOT);
                if (srcRoot != null) {
                  mySourcesMap.put(path, SRC_ROOT);
                }
              }
              finally {
                zipFile.close();
              }
            }
            catch (IOException e) {
              mySourcesMap.put(path, null);
            }
          }
        }
      }
    }
  }

  private void createProjectStructure() {
    if (myProject.isDisposed()) {
      return;
    }

    ApplicationManager.getApplication().runWriteAction(new Runnable() {
      @Override
      public void run() {
        LanguageLevel projectLevel = LanguageLevelProjectExtension.getInstance(myProject).getLanguageLevel();
        Map<Project, ModifiableRootModel> rootModels = ContainerUtil.newHashMap();
        ModifiableModuleModel moduleModel = ModuleManager.getInstance(myProject).getModifiableModel();
        LibraryTable.ModifiableModel libraryModel = ProjectLibraryTable.getInstance(myProject).getModifiableModel();
        try {
          for (Project project : myProjects) {
            try {
              rootModels.put(project, createModule(moduleModel, project, projectLevel));
            }
            catch (Exception e) {
              LOG.error(e);  // should not happen, since project.prepare() is already called
            }
          }
          for (Project project : myProjects) {
            try {
              setDependencies(moduleModel, libraryModel, rootModels.get(project), project);
            }
            catch (Exception e) {
              LOG.error(e);  // should not happen, since project.prepare() is already called
            }
          }
        }
        finally {
          libraryModel.commit();
          ModifiableModelCommitter.multiCommit(rootModels.values(), moduleModel);
        }
      }
    });
  }

  private ModifiableRootModel createModule(ModifiableModuleModel moduleModel, Project project, LanguageLevel projectLevel) throws Exception {
    String name = project.getName();
    Module module = moduleModel.findModuleByName(name);
    if (module == null) {
      String path = project.getBase().getPath() + File.separator + name + ModuleFileType.DOT_DEFAULT_EXTENSION;
      module = moduleModel.newModule(path, StdModuleTypes.JAVA.getId());
    }

    ModifiableRootModel rootModel = ModuleRootManager.getInstance(module).getModifiableModel();
    for (ContentEntry entry : rootModel.getContentEntries()) {
      rootModel.removeContentEntry(entry);
    }
    for (OrderEntry entry : rootModel.getOrderEntries()) {
      if (!(entry instanceof ModuleJdkOrderEntry || entry instanceof ModuleSourceOrderEntry)) {
        rootModel.removeOrderEntry(entry);
      }
    }
    rootModel.inheritSdk();

    ContentEntry contentEntry = rootModel.addContentEntry(url(project.getBase()));
    for (File src : project.getSourcePath()) {
      contentEntry.addSourceFolder(url(src), false);
    }
    File testSrc = project.getTestSrc();
    if (testSrc != null) {
      contentEntry.addSourceFolder(url(testSrc), true);
    }
    contentEntry.addExcludeFolder(url(project.getTarget()));

    LanguageLevel sourceLevel = LanguageLevel.parse(project.getProperty(JAVAC_SOURCE));
    if (sourceLevel == projectLevel) sourceLevel = null;
    rootModel.getModuleExtension(LanguageLevelModuleExtension.class).setLanguageLevel(sourceLevel);

    CompilerModuleExtension compilerExt = rootModel.getModuleExtension(CompilerModuleExtension.class);
    compilerExt.inheritCompilerOutputPath(false);
    compilerExt.setExcludeOutput(true);
    compilerExt.setCompilerOutputPath(url(project.getSrcOutput()));
    compilerExt.setCompilerOutputPathForTests(url(project.getTestOutput()));

    String targetLevel = project.getProperty(JAVAC_TARGET);
    CompilerConfiguration.getInstance(myProject).setBytecodeTargetLevel(module, targetLevel);

    OsmorcFacet facet = OsmorcFacet.getInstance(module);

    if (project.isNoBundles() && facet != null) {
      FacetUtil.deleteFacet(facet);
      facet = null;
    }
    else if (!project.isNoBundles() && facet == null) {
      facet = FacetUtil.addFacet(module, OsmorcFacetType.getInstance());
    }

    if (facet != null) {
      OsmorcFacetConfiguration facetConfig = facet.getConfiguration();

      facetConfig.setManifestGenerationMode(ManifestGenerationMode.Bnd);
      facetConfig.setBndFileLocation(FileUtil.getRelativePath(path(project.getBase()), path(project.getPropertiesFile()), '/'));

      Map.Entry<String, Attrs> bsn = project.getBundleSymbolicName();
      File bundle = project.getOutputFile(bsn != null ? bsn.getKey() : name, project.getBundleVersion());
      facetConfig.setJarFileLocation(path(bundle), OutputPathType.SpecificOutputPath);

      facetConfig.setDoNotSynchronizeWithMaven(true);
    }

    return rootModel;
  }

  private void setDependencies(ModifiableModuleModel moduleModel,
                               LibraryTable.ModifiableModel libraryModel,
                               ModifiableRootModel rootModel,
                               Project project) throws Exception {
    List<String> warnings = ContainerUtil.newArrayList();

    Collection<Container> boot = project.getBootclasspath();
    Set<Container> bootSet = Collections.emptySet();
    if (!boot.isEmpty()) {
      setDependencies(moduleModel, libraryModel, rootModel, project, boot, false, bootSet, warnings);
      bootSet = ContainerUtil.newHashSet(boot);

      OrderEntry[] entries = rootModel.getOrderEntries();
      if (entries.length > 2) {
        Arrays.sort(entries, new Comparator<OrderEntry>() {
          @Override
          public int compare(OrderEntry o1, OrderEntry o2) {
            return weight(o1) - weight(o2);
          }
        });
        rootModel.rearrangeOrderEntries(entries);
      }
    }

    setDependencies(moduleModel, libraryModel, rootModel, project, project.getBuildpath(), false, bootSet, warnings);
    setDependencies(moduleModel, libraryModel, rootModel, project, project.getTestpath(), true, bootSet, warnings);

    checkWarnings(project, warnings);
  }

  private void setDependencies(ModifiableModuleModel moduleModel,
                               LibraryTable.ModifiableModel libraryModel,
                               ModifiableRootModel rootModel,
                               Project project,
                               Collection<Container> classpath,
                               boolean tests,
                               Set<Container> excluded,
                               List<String> warnings) throws Exception {
    DependencyScope scope = tests ? DependencyScope.TEST : DependencyScope.COMPILE;
    for (Container dependency : classpath) {
      if (excluded.contains(dependency)) {
        continue;  // skip boot path dependency
      }
      if (dependency.getType() == Container.TYPE.PROJECT && project == dependency.getProject()) {
        continue;  // skip self-reference
      }
      try {
        addEntry(moduleModel, libraryModel, rootModel, dependency, scope);
      }
      catch (IllegalArgumentException e) {
        warnings.add(e.getMessage());
      }
    }
  }

  private void addEntry(ModifiableModuleModel moduleModel,
                        LibraryTable.ModifiableModel libraryModel,
                        ModifiableRootModel rootModel,
                        Container dependency,
                        DependencyScope scope) throws IllegalArgumentException {
    File file = dependency.getFile();
    String bsn = dependency.getBundleSymbolicName();
    String version = dependency.getVersion();

    if (JDK_DEPENDENCY.equals(bsn)) {
      String name = BND_LIB_PREFIX + bsn + ":" + version;
      if (FileUtil.isAncestor(myWorkspace.getBase(), file, true)) {
        name += "-" + myProject.getName();
      }
      ProjectJdkTable jdkTable = ProjectJdkTable.getInstance();
      Sdk jdk = jdkTable.findJdk(name);
      if (jdk == null) {
        jdk = jdkTable.createSdk(name, JavaSdk.getInstance());
        SdkModificator jdkModel = jdk.getSdkModificator();
        jdkModel.setHomePath(file.getParent());
        jdkModel.setVersionString(version);
        VirtualFile root = VirtualFileManager.getInstance().findFileByUrl(url(file));
        assert root != null : file + " " + file.exists();
        jdkModel.addRoot(root, OrderRootType.CLASSES);
        VirtualFile srcRoot = VirtualFileManager.getInstance().findFileByUrl(url(file) + SRC_ROOT);
        if (srcRoot != null) jdkModel.addRoot(srcRoot, OrderRootType.SOURCES);
        jdkModel.commitChanges();
        jdkTable.addJdk(jdk);
      }
      rootModel.setSdk(jdk);
      return;
    }

    ExportableOrderEntry entry;

    switch (dependency.getType()) {
      case PROJECT: {
        String name = dependency.getProject().getName();
        Module module = moduleModel.findModuleByName(name);
        if (module == null) {
          throw new IllegalArgumentException("Unknown module '" + name + "'");
        }
        entry = rootModel.addModuleOrderEntry(module);
        break;
      }
      case REPO: {
        String name = BND_LIB_PREFIX + bsn + ":" + version;
        Library library = libraryModel.getLibraryByName(name);
        if (library == null) {
          library = libraryModel.createLibrary(name);
          Library.ModifiableModel model = library.getModifiableModel();
          model.addRoot(url(file), OrderRootType.CLASSES);
          String srcRoot = mySourcesMap.get(file.getPath());
          if (srcRoot != null) {
            model.addRoot(url(file) + srcRoot, OrderRootType.SOURCES);
          }
          model.commit();
        }
        entry = rootModel.addLibraryEntry(library);
        break;
      }
      case EXTERNAL: {
        Library library = rootModel.getModuleLibraryTable().createLibrary(file.getName());
        Library.ModifiableModel model = library.getModifiableModel();
        model.addRoot(url(file), OrderRootType.CLASSES);
        String srcRoot = mySourcesMap.get(file.getPath());
        if (srcRoot != null) {
          model.addRoot(url(file) + srcRoot, OrderRootType.SOURCES);
        }
        model.commit();
        entry = rootModel.findLibraryOrderEntry(library);
        assert entry != null : library;
        break;
      }
      default:
        throw new IllegalArgumentException("Unknown dependency '" + dependency + "' of type " + dependency.getType());
    }

    entry.setScope(scope);
  }

  private void checkErrors(Project project, Exception e) {
    if (!myUnitTestMode) {
      String text;
      LOG.error(e);
      text = message("bnd.import.resolve.error", project.getName(), e.getMessage());
      NOTIFICATIONS.createNotification(message("bnd.import.error.title"), text, NotificationType.ERROR, null).notify(myProject);
    }
    else {
      throw new AssertionError(e);
    }
  }

  private void checkErrors(Project project, List<String> errors) {
    if (errors != null && !errors.isEmpty()) {
      if (!myUnitTestMode) {
        LOG.error(errors.toString());
        String text = message("bnd.import.resolve.error", project.getName(), "<br>" + StringUtil.join(errors, "<br>"));
        NOTIFICATIONS.createNotification(message("bnd.import.error.title"), text, NotificationType.ERROR, null).notify(myProject);
      }
      else {
        throw new AssertionError(errors.toString());
      }
    }
  }

  private void checkWarnings(Project project, List<String> warnings) {
    if (warnings != null && !warnings.isEmpty()) {
      if (!myUnitTestMode) {
        LOG.warn(warnings.toString());
        String text = message("bnd.import.warn.text", project.getName(), "<br>" + StringUtil.join(warnings, "<br>"));
        NOTIFICATIONS.createNotification(message("bnd.import.warn.title"), text, NotificationType.WARNING, null).notify(myProject);
      }
      else {
        throw new AssertionError(warnings.toString());
      }
    }
  }

  private static boolean booleanProperty(String value) {
    return "on".equalsIgnoreCase(value) || "true".equalsIgnoreCase(value);
  }

  private static String path(File file) {
    return FileUtil.toSystemIndependentName(file.getPath());
  }

  private static String url(File file) {
    return VfsUtil.getUrlForLibraryRoot(file);
  }

  private static int weight(OrderEntry e) {
    return e instanceof JdkOrderEntry ? 2 :
           e instanceof ModuleSourceOrderEntry ? 0 :
           1;
  }

  @NotNull
  public static Collection<Project> getWorkspaceProjects(@NotNull Workspace workspace) throws Exception {
    return ContainerUtil.filter(workspace.getAllProjects(), Condition.NOT_NULL);
  }

  /**
   * Caches a workspace for methods below.
   */
  @Nullable
  public static Workspace findWorkspace(@NotNull com.intellij.openapi.project.Project project) {
    String basePath = project.getBasePath();
    if (basePath != null && new File(basePath, CNF_DIR).exists()) {
      try {
        Workspace ws = Workspace.getWorkspace(new File(basePath), CNF_DIR);
        BND_WORKSPACE_KEY.set(project, ws);
        return ws;
      }
      catch (Exception e) {
        LOG.error(e);
      }
    }

    return null;
  }

  @Nullable
  public static Workspace getWorkspace(@Nullable com.intellij.openapi.project.Project project) {
    return project == null || project.isDefault() ? null : BND_WORKSPACE_KEY.get(project);
  }

  public static void reimportWorkspace(@NotNull com.intellij.openapi.project.Project project) {
    Workspace workspace = getWorkspace(project);
    assert workspace != null : project;

    try {
      workspace.forceRefresh();
      Collection<Project> projects = getWorkspaceProjects(workspace);
      for (Project p : projects) p.forceRefresh();

      BndProjectImporter importer = new BndProjectImporter(project, workspace, projects);
      importer.setupProject();
      importer.resolve();
    }
    catch (Exception e) {
      LOG.error("ws=" + workspace.getBase(), e);
    }
  }

  public static void reimportProjects(@NotNull com.intellij.openapi.project.Project project, @NotNull Collection<String> projectDirs) {
    Workspace workspace = getWorkspace(project);
    assert workspace != null : project;

    try {
      Collection<Project> projects = ContainerUtil.newArrayListWithCapacity(projectDirs.size());
      for (String dir : projectDirs) {
        Project p = workspace.getProject(PathUtil.getFileName(dir));
        if (p != null) {
          p.forceRefresh();
          projects.add(p);
        }
      }

      new BndProjectImporter(project, workspace, projects).resolve();
    }
    catch (Exception e) {
      LOG.error("ws=" + workspace.getBase() + " pr=" + projectDirs, e);
    }
  }
}
