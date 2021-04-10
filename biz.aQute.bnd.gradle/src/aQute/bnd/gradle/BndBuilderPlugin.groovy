/**
 * BndBuilderPlugin for Gradle.
 *
 * <p>
 * The plugin name is {@code biz.aQute.bnd.builder}.
 *
 * <p>
 * This plugin applies the java plugin to a project and modifies the jar
 * task by adding the properties from the {@link BundleTaskConvention},
 * setting the bndfile to 'bnd.bnd', if the file exists, and building the
 * jar file as a bundle.
 * <p>
 * This plugin also defines a 'baseline' configuration and a baseline task
 * of type {@link Baseline}. The baseline task will be set up with the
 * default of baselining the output of the jar task using the baseline
 * configuration. The baseline configuration default dependency
 * will use the prior version of the jar.
 */

package aQute.bnd.gradle

import static aQute.bnd.gradle.BndUtils.unwrap

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ExternalDependency
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.ResolveException
import org.gradle.api.file.RegularFile
import org.gradle.api.tasks.TaskContainer



public class BndBuilderPlugin implements Plugin<Project> {
  public static final String PLUGINID = 'biz.aQute.bnd.builder'

  /**
   * Apply the {@code biz.aQute.bnd.builder} plugin to the specified project.
   */
  @Override
  public void apply(Project project) {
    if (project.plugins.hasPlugin(BndPlugin.PLUGINID)) {
        throw new GradleException("Project already has '${BndPlugin.PLUGINID}' plugin applied.")
    }
    project.plugins.apply('java')

    TaskContainer tasks = project.tasks

    def jar = tasks.named('jar') { t ->
      t.description = 'Assembles a bundle containing the main classes.'
      t.convention.plugins.bundle = new BundleTaskConvention(t)
      RegularFile defaultBndfile = t.project.layout.projectDirectory.file('bnd.bnd')
      if (defaultBndfile.getAsFile().isFile()) {
        t.bndfile.convention(defaultBndfile)
      }
      t.doLast('buildBundle') { tt ->
        buildBundle()
      }
    }

    Configuration baseline = project.configurations.create('baseline')
    baseline.dependencies.all { Dependency dep ->
      if (dep instanceof ExternalDependency) {
        dep.version {
          strictly(dep.getVersion())
        }
      }
      if (dep instanceof ModuleDependency) {
        dep.transitive = false
      }
    }

    tasks.register('baseline', Baseline.class) { t ->
      t.description = 'Baseline the project bundle.'
      t.group = 'release'
      t.bundle = jar
      t.baseline = baseline
    }

    baseline.defaultDependencies { deps ->
      Task baselineTask = tasks.getByName('baseline')
      Task bundleTask = baselineTask.getBundleTask()
      if (bundleTask) {
        String archiveBaseName = unwrap(bundleTask.getArchiveBaseName())
        String archiveVersion = unwrap(bundleTask.getArchiveVersion(), true)
        String group = project.group.toString()
        baselineTask.logger.debug('Searching for default baseline {}:{}:(0,{}[', group, archiveBaseName, archiveVersion)
        Dependency baselineDep = project.dependencies.create('group': group, 'name': archiveBaseName) {
          version {
            strictly("(0,${archiveVersion}[")
          }
          transitive = false
        }
        try {
          Configuration detached = project.configurations.detachedConfiguration(baselineDep)
          detached.resolvedConfiguration.rethrowFailure()
        } catch(ResolveException e) {
          baselineTask.logger.debug('Baseline configuration resolve error {}, adding {} as baseline', e, baselineTask.bundle, e)
          baselineDep = project.dependencies.create(project.files(baselineTask.bundle))
        }
        deps.add(baselineDep)
      }
    }
  }
}
