package cz.domaci.odmeny.completion;

/**
 * The selected chore does not exist, is inactive, or belongs to another
 * household.
 */
public class TaskNotAvailableException extends RuntimeException {

    public TaskNotAvailableException(String taskId) {
        super("Task is not available: " + taskId);
    }
}
