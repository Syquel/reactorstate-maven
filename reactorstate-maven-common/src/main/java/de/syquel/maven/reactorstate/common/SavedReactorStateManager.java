package de.syquel.maven.reactorstate.common;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.maven.RepositoryUtils;
import org.apache.maven.artifact.repository.metadata.ArtifactRepositoryMetadata;
import org.apache.maven.artifact.repository.metadata.GroupRepositoryMetadata;
import org.apache.maven.artifact.repository.metadata.SnapshotArtifactRepositoryMetadata;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.syquel.maven.reactorstate.common.data.MavenArtifactState;
import de.syquel.maven.reactorstate.common.data.MavenProjectState;
import de.syquel.maven.reactorstate.common.persistence.IReactorStateRepository;
import de.syquel.maven.reactorstate.common.persistence.json.JsonReactorStateRepository;

/**
 * The implementation of a Maven Reactor state manager which operates on the saved state of Maven modules within a Maven project.
 */
public class SavedReactorStateManager extends AbstractReactorStateManager {

	private static final Logger LOGGER = LoggerFactory.getLogger(SavedReactorStateManager.class);

	private SavedReactorStateManager(final Set<MavenProjectState> projectStates) {
		super(projectStates);
	}

	/**
	 * Instantiates a Reactor state manager based on the saved state of the Maven project and its Maven modules.
	 *
	 * @param session The current Maven execution for the Maven project build.
	 * @param projectBuilder The builder for Maven projects from POMs.
	 * @return A Reactor state manager with the saved state of the Maven project and its Maven modules.
	 * @throws ProjectBuildingException if an invalid POM is encountered.
	 * @throws IOException if an error occurred while reading the persisted state.
	 */
	public static SavedReactorStateManager create(final MavenSession session, final ProjectBuilder projectBuilder)
		throws ProjectBuildingException, IOException
	{
		final Set<MavenProject> projects = new HashSet<>(session.getProjects());

		LOGGER.info("Resolving Maven project tree");
		contributeWorkspaceProjects(session.getCurrentProject(), projects, session.getProjectBuildingRequest(), projectBuilder);

		final IReactorStateRepository reactorStateRepository = new JsonReactorStateRepository();

		final Set<MavenProjectState> projectStates = new HashSet<>();
		for (final MavenProject project : projects) {
			final MavenProjectState projectState = reactorStateRepository.read(project);
			if (projectState != null) {
				projectStates.add(projectState);
			}
		}

		return new SavedReactorStateManager(projectStates);
	}

	/**
	 * Restores the saved state of the Maven projects and its Maven modules within the current Maven execution.
	 *
	 * @param session The current Maven execution.
	 * @param projectHelper The helper for Maven-related operations on the current state.
	 */
	public void restoreProjectStates(final MavenSession session, final MavenProjectHelper projectHelper) {
		final Set<MavenProject> projects = new HashSet<>(session.getProjects());

		for (final MavenProject project : projects) {
			final MavenProjectState projectState = getProjectState(project);
			if (projectState == null) {
				LOGGER.warn("No saved state found for Maven project {}: Rebuild required.", project.getId());
				continue;
			}

			project.setPomFile(projectState.getPom().getFile());

			final MavenArtifactState mainArtifactState = projectState.getMainArtifactState();

			final org.apache.maven.artifact.Artifact mainArtifact = buildArtifact(mainArtifactState);
			project.getArtifact().setFile(mainArtifact.getFile());
			LOGGER.info("Restored main artifact {}", mainArtifact.getId());

			final Collection<org.apache.maven.artifact.Artifact> attachedArtifacts =
				projectState.getAttachedArtifactStates().stream().map(SavedReactorStateManager::buildArtifact).collect(Collectors.toList());
			project.getAttachedArtifacts().clear();
			for (final org.apache.maven.artifact.Artifact attachedArtifact : attachedArtifacts) {
				projectHelper.attachArtifact(project, attachedArtifact.getType(), attachedArtifact.getClassifier(), attachedArtifact.getFile());
				LOGGER.info("Restored attached artifact {}", attachedArtifact.getId());
			}
		}
	}

	/**
	 * Discovers Maven modules within the workspace of a Maven project recursively.
	 *
	 * The main use-case for this functionality is to be able to restore the state of other Maven modules, which are not being built in the current
	 * Maven execution, but belong to the same Maven workspace, to enable standalone builds of submodules.
	 *
	 * @param project The Maven module to search for parent and child modules.
	 * @param discoveredProjects The already discovered Maven modules within the Maven workspace.
	 * @param buildingRequest The Maven building request of the current Maven execution.
	 * @param projectBuilder The builder for Maven projects from POMs.
	 * @throws ProjectBuildingException if an invalid POM is encountered.
	 */
	private static void contributeWorkspaceProjects(
		final MavenProject project, final Set<MavenProject> discoveredProjects,
		final ProjectBuildingRequest buildingRequest, final ProjectBuilder projectBuilder
	) throws ProjectBuildingException
	{
		final Path projectBasePath = project.getBasedir().toPath();

		// Discover downstream Maven projects within workspace
		for (final String module : project.getModules()) {
			final Path modulePomPath = projectBasePath.resolve(module).resolve("pom.xml");

			final MavenProject moduleProject = projectBuilder.build(modulePomPath.toFile(), buildingRequest).getProject();
			if (discoveredProjects.contains(moduleProject)) {
				continue;
			}

			LOGGER.info("Discovered upstream Maven project {}", moduleProject.getId());
			discoveredProjects.add(moduleProject);

			contributeWorkspaceProjects(moduleProject, discoveredProjects, buildingRequest, projectBuilder);
		}

		// Discover upstream Maven projects within workspace
		final MavenProject parentProject = project.getParent();
		if (parentProject != null && !discoveredProjects.contains(parentProject) && isWorkspaceProject(parentProject)) {
			LOGGER.info("Discovered Maven parent project {}", parentProject.getId());
			discoveredProjects.add(parentProject);

			contributeWorkspaceProjects(parentProject, discoveredProjects, buildingRequest, projectBuilder);
		}
	}

	/**
	 * Determines if a Maven module belongs to the current Maven workspace.
	 *
	 * @param project The Maven module to check.
	 * @return Whether the Maven module belongs to the current Maven workspace.
	 */
	private static boolean isWorkspaceProject(final MavenProject project) {
		return project.getBasedir() != null;
	}

	/**
	 * Builds a Maven artifact based on its saved state.
	 *
	 * @param artifactState The Maven artifact state to build the Maven Artifact for.
	 * @return The Maven artifact based on its saved state.
	 */
	private static org.apache.maven.artifact.Artifact buildArtifact(final MavenArtifactState artifactState) {
		final org.apache.maven.artifact.Artifact repositoryArtifact = RepositoryUtils.toArtifact(artifactState.getArtifact());

		if (artifactState.getArtifactRepositoryMetadata() != null) {
			final ArtifactRepositoryMetadata artifactRepositoryMetadata = new ArtifactRepositoryMetadata(repositoryArtifact);
			artifactRepositoryMetadata.setMetadata(artifactState.getArtifactRepositoryMetadata());

			repositoryArtifact.addMetadata(artifactRepositoryMetadata);
		}

		if (artifactState.getGroupRepositoryMetadata() != null) {
			final GroupRepositoryMetadata groupRepositoryMetadata = new GroupRepositoryMetadata(artifactState.getGroupRepositoryMetadata().getGroupId());
			groupRepositoryMetadata.setMetadata(artifactState.getGroupRepositoryMetadata());

			repositoryArtifact.addMetadata(groupRepositoryMetadata);
		}

		if (artifactState.getSnapshotRepositoryMetadata() != null) {
			final SnapshotArtifactRepositoryMetadata snapshotRepositoryMetadata = new SnapshotArtifactRepositoryMetadata(repositoryArtifact);
			snapshotRepositoryMetadata.setMetadata(artifactState.getSnapshotRepositoryMetadata());

			repositoryArtifact.addMetadata(snapshotRepositoryMetadata);
		}

		return repositoryArtifact;
	}

}
