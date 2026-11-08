package cz.domaci.odmeny.task;

import cz.domaci.odmeny.household.HouseholdService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class TaskService {

    private final TaskRepository repository;
    private final HouseholdService household;

    @Inject
    public TaskService(TaskRepository repository, HouseholdService household) {
        this.repository = repository;
        this.household = household;
    }

    /**
     * Active chores in display order. This is the only list children see.
     */
    public List<Task> listActive() {
        return repository.listTasks(household.id()).stream()
                .filter(Task::active)
                .sorted(Comparator.comparingInt(Task::sortOrder).thenComparing(Task::name))
                .toList();
    }

    public Optional<Task> findActive(String taskId) {
        return repository.findById(household.id(), taskId)
                .filter(Task::active);
    }

    public List<Task> listAll() {
        return repository.listTasks(household.id()).stream()
                .sorted(Comparator.comparingInt(Task::sortOrder).thenComparing(Task::name))
                .toList();
    }
}
