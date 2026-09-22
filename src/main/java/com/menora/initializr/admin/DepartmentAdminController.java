package com.menora.initializr.admin;

import com.menora.initializr.db.entity.DepartmentEntity;
import com.menora.initializr.db.repository.DepartmentRepository;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Admin CRUD for the department list shared by the backend, frontend and fullstack screens.
 * Behind the {@code /admin/*} auth filter like the rest of the admin API.
 */
@RestController
@RequestMapping("/admin/departments")
public class DepartmentAdminController {

    private final DepartmentRepository departmentRepo;

    public DepartmentAdminController(DepartmentRepository departmentRepo) {
        this.departmentRepo = departmentRepo;
    }

    @GetMapping
    public List<DepartmentEntity> list() {
        return departmentRepo.findAllByOrderBySortOrderAsc();
    }

    @PostMapping
    @Transactional
    public DepartmentEntity create(@Valid @RequestBody DepartmentEntity department) {
        department.setId(null);
        if (department.isDefault()) clearOtherDefaults(null);
        return departmentRepo.save(department);
    }

    @PutMapping("/{id}")
    @Transactional
    public DepartmentEntity update(@PathVariable Long id, @Valid @RequestBody DepartmentEntity department) {
        department.setId(id);
        if (department.isDefault()) clearOtherDefaults(id);
        return departmentRepo.save(department);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        departmentRepo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /** At most one default department — flips the others off instead of failing the save. */
    private void clearOtherDefaults(Long keepId) {
        for (DepartmentEntity d : departmentRepo.findAll()) {
            if (d.isDefault() && (keepId == null || !d.getId().equals(keepId))) {
                d.setDefault(false);
                departmentRepo.save(d);
            }
        }
    }
}
