package cz.domaci.odmeny.task;

import java.util.List;
import java.util.Optional;

/**
 * Storage-agnostic access to the chore catalog.
 */
public interface TaskRepository {

    List<Task> listTasks(String householdId);

    Optional<Task> findById(String householdId, String taskId);

    void put(Task task);
}
