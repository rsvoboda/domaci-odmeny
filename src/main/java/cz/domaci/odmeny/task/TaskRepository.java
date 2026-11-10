package cz.domaci.odmeny.task;

import cz.domaci.odmeny.audit.AuditEvent;

import java.util.List;
import java.util.Optional;

/**
 * Storage-agnostic access to the chore catalog.
 */
public interface TaskRepository {

    List<Task> listTasks(String householdId);

    Optional<Task> findById(String householdId, String taskId);

    /**
     * Creates or replaces a task. Used by the seeders.
     */
    void put(Task task);

    /**
     * Creates a new task and writes the audit event in the same transaction.
     *
     * @throws IllegalStateException when the task already exists
     */
    void create(Task task, AuditEvent audit);

    /**
     * Updates an existing task and writes the audit event in the same
     * transaction.
     *
     * @throws IllegalStateException when the task no longer exists
     */
    void update(Task task, AuditEvent audit);
}
