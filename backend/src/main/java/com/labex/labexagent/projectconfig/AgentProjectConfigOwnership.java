package com.labex.labexagent.projectconfig;

import com.labex.entity.StudentProject;
import com.labex.service.StudentProjectService;

/**
 * Single ownership gate for every project-configuration read and write in the control plane.
 *
 * <p>All configuration services and the controller derive the actor from the authenticated
 * principal and the project from the route; they must never accept a client-supplied student
 * ID, project ownership claim or task epoch. This gate forwards the request to
 * {@link StudentProjectService#getOwnedProject(Integer, Integer)} and treats a null or
 * inconsistent result as a uniformly safe {@link ProjectConfigNotFoundException} with a
 * generic message, so foreign projects are indistinguishable from not found.
 */
public final class AgentProjectConfigOwnership {

    /** The only not-found message ever surfaced; it leaks no existence information. */
    public static final String NOT_FOUND_MESSAGE = "Project not found";

    private AgentProjectConfigOwnership() {
    }

    /**
     * Returns the owned project for the authenticated owner and the route project ID, or
     * throws a safe not-found failure. The returned entity is re-checked so that a buggy
     * caller cannot pass a project that does not actually belong to the owner.
     */
    public static StudentProject requireOwned(Integer studentId, Integer projectId,
                                              StudentProjectService studentProjectService) {
        if (studentId == null || projectId == null || studentProjectService == null) {
            throw new ProjectConfigNotFoundException();
        }
        StudentProject project = studentProjectService.getOwnedProject(studentId, projectId);
        if (project == null
                || !Integer.valueOf(projectId).equals(project.getProjectId())
                || !Integer.valueOf(studentId).equals(project.getStudentId())
                || project.getWorkspacePath() == null
                || project.getWorkspacePath().isBlank()) {
            throw new ProjectConfigNotFoundException();
        }
        return project;
    }

    /** Safe, existence-neutral not-found failure for foreign or missing projects. */
    public static final class ProjectConfigNotFoundException extends RuntimeException {
        public ProjectConfigNotFoundException() {
            super(NOT_FOUND_MESSAGE);
        }
    }
}
